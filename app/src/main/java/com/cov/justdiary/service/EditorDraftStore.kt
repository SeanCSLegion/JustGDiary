package com.cov.justdiary.service

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * 编辑器草稿（P0-1）。
 *
 * 只存**未保存**的内容；保存 / 放弃 / 空内容退出时由 `DiaryPageViewModel` 清掉。
 * 键由调用方给出（`dayKey#blockId` / `dayKey#new`），落盘文件名用 SHA-256，避免
 * 日期或 id 里的字符出现在路径里。
 */
@Serializable
data class EditorDraft(
    val contentJson: String,
    val startTimeUtc: Long,
    val lineIndex: Int = 0,
    val selectionStart: Int = 0,
    val selectionEnd: Int = 0,
    val endLineIndex: Int = 0,
    val savedAt: Long = System.currentTimeMillis(),
)

/**
 * 编辑器草稿的磁盘存储。
 *
 * 设计要点：
 * - **同步写**：内容最大也就几十 KB；在 `Dispatchers.IO` 上调用，磁盘可靠。
 * - **临时文件 + rename**：进程在写一半时被杀，也不会留下半截 JSON 被当成草稿。
 * - **坏文件自愈**：解析失败按“没有草稿”处理并删除，绝不让一个坏草稿卡住编辑页。
 * - 目录可注入，JVM/Robolectric 测试不必碰真实 `filesDir`。
 */
class EditorDraftStore(
    context: Context,
    private val root: File = File(context.applicationContext.filesDir, "drafts"),
) {

    private val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }
    private val lock = Any()

    fun load(key: String): EditorDraft? {
        if (key.isBlank()) return null
        val file = fileFor(key)
        if (!file.exists()) return null
        return runCatching {
            json.decodeFromString<EditorDraft>(file.readText())
        }.onFailure {
            Log.editor.w("草稿解析失败，已丢弃：${it.message}")
            file.delete()
        }.getOrNull()
    }

    fun save(key: String, draft: EditorDraft) {
        if (key.isBlank()) return
        synchronized(lock) {
            root.mkdirs()
            val target = fileFor(key)
            val temp = File(root, "${target.name}.tmp")
            runCatching {
                temp.writeText(json.encodeToString(draft))
                if (!temp.renameTo(target)) {
                    // 某些文件系统上 rename 不能覆盖；先删目标再重试。
                    target.delete()
                    if (!temp.renameTo(target)) {
                        // 仍然失败：退化为覆盖写，至少不因为 rename 丢掉刚写的草稿。
                        temp.copyTo(target, overwrite = true)
                        temp.delete()
                    }
                }
            }.onFailure {
                Log.editor.w("写草稿失败：${it.message}")
                temp.delete()
            }
        }
    }

    fun clear(key: String) {
        if (key.isBlank()) return
        synchronized(lock) {
            fileFor(key).delete()
        }
    }

    /** 清掉超过 [maxAgeMillis] 的旧草稿；启动时调用，避免目录无限增长。 */
    fun clearStale(maxAgeMillis: Long = DEFAULT_MAX_AGE_MS) {
        synchronized(lock) {
            val cutoff = System.currentTimeMillis() - maxAgeMillis
            root.listFiles()?.forEach { file ->
                if (file.lastModified() < cutoff) file.delete()
            }
        }
    }

    private fun fileFor(key: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(key.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        return File(root, "$digest.json")
    }

    companion object {
        /** 草稿保留 14 天；超过这个时间的未保存内容基本不会再被恢复。 */
        const val DEFAULT_MAX_AGE_MS: Long = 14L * 24 * 60 * 60 * 1000
    }
}
