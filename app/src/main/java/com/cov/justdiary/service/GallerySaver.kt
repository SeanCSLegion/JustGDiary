package com.cov.justdiary.service

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * "保存到相册"。对应 iOS 分享面板里的「存储图像」（`UIActivityViewController` 的系统动作）。
 *
 * ## 与 iOS 的差异（平台事实）
 *
 * iOS 的系统动作区里自带"存储图像"，Android 的系统 Sharesheet **只有分享**，
 * 没有"存到相册"这一项，所以规范 §4.2 让面板自己给一个「保存到相册」按钮
 * （`docs/android-design/04-diary.md` §7 第 4 条）。
 *
 * ## 实现走 MediaStore（不是直接写文件）
 *
 * `minSdk 33`，因此可以只用 `MediaStore.Images.Media.insert` 那一套：
 * - `RELATIVE_PATH = Pictures/JustDiary`（规范指定的位置）；
 * - `IS_PENDING = 1` → 写完置 0，避免相册扫到半张图。
 *
 * 直接往 `/sdcard/Pictures` 写文件在 `targetSdk 36` 上已经不可行
 * （分区存储），且写进去相册也未必立刻可见。
 */
object GallerySaver {

    /** 相册里的子目录（规范 §4.2：`Pictures/JustDiary`）。 */
    val RELATIVE_PATH: String = "${Environment.DIRECTORY_PICTURES}/JustDiary"

    /**
     * 把 [file] 写进相册，返回它的 `content://` URI；失败返回 null。
     *
     * 这里是同步 IO，调用方负责放到 IO 线程（`DiaryPageViewModel` 走 `Dispatchers.IO`）。
     */
    fun saveToGallery(context: Context, file: File, displayName: String): Uri? {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, ShareIntents.MIME_PNG)
            put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
        }
        // 同名图片在相册里会变成 "name (1).png"：这里每次都是新插入，与 iOS 的
        // "照片 App 里可以有多张同名图"一致（不覆盖用户可能已经编辑过的那张）。
        val uri = runCatching {
            resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        }.onFailure { Log.backup.w("插入相册失败：${it.message}") }.getOrNull() ?: return null

        return try {
            resolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { input -> input.copyTo(out) }
            } ?: error("openOutputStream 返回 null")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                resolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) },
                    null,
                    null,
                )
            }
            uri
        } catch (error: Throwable) {
            Log.backup.e("写入相册失败", error)
            // 写了一半的条目要撤掉，否则相册里会留一张打不开的图
            runCatching { resolver.delete(uri, null, null) }
            null
        }
    }
}
