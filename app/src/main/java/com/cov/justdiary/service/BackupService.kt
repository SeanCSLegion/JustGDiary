package com.cov.justdiary.service

import android.content.Context
import com.cov.justdiary.data.BackupException
import com.cov.justdiary.data.BackupImageRef
import com.cov.justdiary.data.BackupManifest
import com.cov.justdiary.data.BackupStats
import com.cov.justdiary.data.DiaryRepository
import com.cov.justdiary.data.ImportMode
import com.cov.justdiary.model.AppSettingsJson
import com.cov.justdiary.model.ImagePathUtil
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 备份包的导出与导入。对应 iOS `Services/BackupService.swift`。
 *
 * ## 包结构（跨端契约，不能改）
 *
 * ```
 * JustDiary_yyyyMMdd_HHmmss.jdiary   （= 标准 zip）
 * ├── manifest.json       BackupManifest
 * ├── db/just_diary.db    SQLite 快照（iOS 用 sqlite3_backup，这里用 VACUUM INTO）
 * └── images/<name>       被引用的图片
 * ```
 *
 * 导入兼容旧布局 `jdiary_export/…` 前缀（与 iOS 一致）。
 *
 * ## 与 iOS 的差异（都是为了不在 Android 上 OOM）
 *
 * - 导出**流式**写 zip（iOS 把整个包拼成 `Data`）；
 * - 导入用 [ZipArchive.Reader] 按需读 entry、流式落盘（iOS 解成 `[String: Data]`）。
 *
 * ## 注意
 *
 * `settingsJson` 只含 12 个业务设置项；`search_index_version` / `content_format_version`
 * **刻意不在其中**（它们是数据版本而非偏好，恢复备份不能重置它们）。
 */
class BackupService(
    context: Context,
    private val repository: DiaryRepository,
    private val settings: SettingsStore,
) {

    private val appContext: Context = context.applicationContext

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
        prettyPrint = false
    }

    private val imagesDir: File get() = repository.imagesDir

    // ------------------------------------------------------------------ 导出

    /**
     * 导出备份包，返回落在缓存目录里的 `.jdiary` 文件。
     *
     * @param includeSettings 是否把设置写进 `settingsJson`（对应 iOS 的"仅数据 / 数据+设置"）。
     */
    suspend fun exportBackup(includeSettings: Boolean): File = withContext(Dispatchers.IO) {
        repository.ensureSearchIndex()

        val stamp = System.currentTimeMillis()
        val workDir = File(appContext.cacheDir, "jdiary_export_$stamp").apply { mkdirs() }
        try {
            val dbDir = File(workDir, "db").apply { mkdirs() }
            val imagesWorkDir = File(workDir, "images").apply { mkdirs() }

            val snapshot = File(dbDir, "just_diary.db")
            repository.snapshotDatabaseTo(snapshot)

            val imageRefs = collectImageRefs(imagesWorkDir)

            val manifest = BackupManifest(
                formatVersion = BackupManifest.FORMAT_VERSION,
                appVersion = appVersionName(),
                exportedAt = stamp,
                includeSettings = includeSettings,
                diaryCount = repository.countDiaries(),
                blockCount = repository.countBlocks(),
                imageCount = imageRefs.size,
                images = imageRefs,
                settingsJson = if (includeSettings) {
                    json.encodeToString(AppSettingsJson.from(settings.load()))
                } else {
                    ""
                },
            )

            val sources = buildList {
                add(ZipArchive.Source.Bytes(MANIFEST_NAME, json.encodeToString(manifest).toByteArray()))
                add(ZipArchive.Source.FileSource(DATABASE_NAME, snapshot))
                imagesWorkDir.listFiles()
                    ?.sortedBy { it.name }
                    ?.forEach { add(ZipArchive.Source.FileSource("images/${it.name}", it)) }
            }

            val outName = "JustDiary_${timestampForFileName()}.jdiary"
            val outFile = File(appContext.cacheDir, outName)
            if (outFile.exists()) outFile.delete()
            ZipArchive.create(outFile, sources)
            Log.backup.i("导出完成：${manifest.diaryCount} 天 / ${manifest.blockCount} 片段 / ${imageRefs.size} 图")
            outFile
        } finally {
            workDir.deleteRecursively()
        }
    }

    /** 收集被引用的图片，按 basename 去重改名后复制到工作目录。 */
    private suspend fun collectImageRefs(imagesWorkDir: File): List<BackupImageRef> {
        val refs = mutableListOf<BackupImageRef>()
        val usedNames = mutableSetOf<String>()

        for ((_, contentJson) in repository.rawBlocksForBackup()) {
            for (rawSrc in ImagePathUtil.collectImageSrcs(contentJson)) {
                val key = ImagePathUtil.normalizeSrcKey(rawSrc)
                val source = repository.resolveImagePath(key)
                if (!source.exists()) continue

                val target = uniqueTarget(imagesWorkDir, key.substringAfterLast('/'), usedNames)
                try {
                    source.copyTo(target, overwrite = true)
                    refs.add(BackupImageRef(src = key, file = "images/${target.name}"))
                } catch (error: Throwable) {
                    // 单张图失败不中断整包导出（与 iOS 一致）
                    Log.backup.e("复制图片失败：$key", error)
                }
            }
        }
        return refs
    }

    private fun uniqueTarget(dir: File, fileName: String, usedNames: MutableSet<String>): File {
        var name = fileName
        var target = File(dir, name)
        var counter = 1
        while (target.name in usedNames) {
            val base = name.substringBeforeLast('.', name)
            val ext = name.substringAfterLast('.', "")
            name = if (ext.isEmpty()) "${base}_$counter" else "${base}_$counter.$ext"
            target = File(dir, name)
            counter++
        }
        usedNames.add(target.name)
        return target
    }

    // ------------------------------------------------------------------ 导入

    /**
     * 导入备份包。`mode` 决定已存在的日期是**整天跳过**还是**整天替换**
     * （没有块级合并 —— 与 iOS 一致）。
     */
    suspend fun importBackup(backupFile: File, mode: ImportMode): BackupStats = withContext(Dispatchers.IO) {
        val stamp = System.currentTimeMillis()
        val workDir = File(appContext.cacheDir, "jdiary_import_$stamp").apply { mkdirs() }
        try {
            ZipArchive.open(backupFile).use { zip ->
                val manifest = readManifest(zip)
                if (manifest.formatVersion != BackupManifest.FORMAT_VERSION) {
                    throw BackupException.InvalidVersion(manifest.formatVersion)
                }

                val backupDb = File(workDir, "backup.db")
                val dbExtracted = zip.extractTo(DATABASE_NAME, backupDb) ||
                    zip.extractTo("$LEGACY_PREFIX$DATABASE_NAME", backupDb)
                if (!dbExtracted) throw BackupException.InvalidManifest("备份包里没有数据库")

                // 先校验 manifest 里的图片映射，任何可疑路径都整包拒绝（P0-0）。
                val srcMap = validatedImageEntries(zip, manifest)
                val copied = mutableMapOf<String, String>()
                // P1-15：本次导入新落盘的图片。DB 事务失败时整批删掉，不留"半套图片"。
                val createdImages = mutableListOf<File>()
                var importedImages = 0

                val copyImage: (String) -> String = { rawSrc ->
                    if (!ImagePathUtil.isValidStoredSrc(rawSrc)) {
                        throw BackupException.InvalidManifest("备份包含非法图片路径：$rawSrc")
                    }
                    val key = ImagePathUtil.normalizeSrcKey(rawSrc)
                    copied[key] ?: run {
                        val entryName = srcMap[key]
                        var result = key
                        if (entryName != null) {
                            val ext = imageExtension(entryName)
                            // 自增序号 + 存在性检查：同名旧文件绝不被覆盖（备份可重复导入）。
                            var slot = importedImages + 1
                            var target = File(imagesDir, "imp_${stamp}_$slot.$ext")
                            while (target.exists()) {
                                slot += 1
                                target = File(imagesDir, "imp_${stamp}_$slot.$ext")
                            }
                            if (!ImagePathUtil.isInside(imagesDir, target)) {
                                throw BackupException.InvalidManifest("图片目标路径越界：${target.name}")
                            }
                            // 先登记再解：`extractTo` 中途抛异常时也在这批清理名单里。
                            createdImages += target
                            if (zip.extractTo(entryName, target)) {
                                importedImages += 1
                                result = ImagePathUtil.IMAGE_DIR_PREFIX + target.name
                                copied[key] = result
                            } else {
                                target.delete()
                                createdImages.remove(target)
                            }
                        }
                        result
                    }
                }

                val merged = try {
                    repository.mergeBackupDatabase(backupDb.absolutePath, mode, copyImage)
                } catch (error: Throwable) {
                    // 整个 DB 合并在一个事务里（P1-15），回滚后这些图片已经没有任何引用。
                    createdImages.forEach { image ->
                        runCatching { image.delete() }
                            .onFailure { Log.backup.w("清理未提交的导入图片失败：${image.name}") }
                    }
                    throw error
                }
                var stats = merged.copy(importedImages = importedImages)

                if (manifest.settingsJson.isNotEmpty()) {
                    val current = settings.load()
                    val element = runCatching {
                        json.parseToJsonElement(manifest.settingsJson)
                    }.getOrNull() as? JsonObject
                    val presentKeys = element?.keys ?: emptySet()
                    val parsed = runCatching {
                        json.decodeFromString(AppSettingsJson.serializer(), manifest.settingsJson)
                    }.getOrNull()
                    if (parsed != null) {
                        // 服务层**不写**设置：换日时刻要先问用户，语言/主题也要走
                        // `SettingsViewModel` + `AppSettingsApplier` 的副作用。
                        // 这里只把解析结果交回调用方。
                        val restored = parsed.toSettings(current, presentKeys)
                        stats = stats.copy(
                            settingsRestored = presentKeys.any { it in AppSettingsJson.BUSINESS_KEYS },
                            restoredSettings = restored,
                        )
                    }
                }

                Log.backup.i(
                    "导入完成：+${stats.importedDays} 天 / ~${stats.skippedDays} 跳过 / " +
                        "${stats.overwrittenDays} 覆盖 / ${stats.importedBlocks} 片段 / ${stats.importedImages} 图",
                )
                stats
            }
        } finally {
            workDir.deleteRecursively()
        }
    }

    /**
     * 校验 manifest 里的图片映射（P0-0）。
     *
     * 规则：
     * - `src` 归一后必须是 `images/<safe-name>`；
     * - `file` 必须是 `images/<safe-name>`（允许旧布局的 `jdiary_export/` 前缀）；
     * - 扩展名必须在白名单里（备份只可能有图片）；
     * - 同一个 `src` 可以出现多次（导出时两个片段共用一张图会产生多条），取第一条。
     *
     * 返回 `src -> zip entry`（entry 取实际存在的那个；缺失图片允许，后续只指向原 key）。
     */
    private fun validatedImageEntries(
        zip: ZipArchive.Reader,
        manifest: BackupManifest,
    ): Map<String, String> {
        val result = LinkedHashMap<String, String>()
        for (ref in manifest.images) {
            val src = normalizedManifestSrc(ref.src)
                ?: throw BackupException.InvalidManifest("备份包含非法图片 src：${ref.src}")
            val entry = validatedImageEntry(ref.file)
                ?: throw BackupException.InvalidManifest("备份包含非法图片路径：${ref.file}")
            // 同一个 src 可能有多条（两个片段共用一张图），取第一条即可；
            // 不能因此拒绝整包，否则本应用自己导出的包都导不回来。
            result.putIfAbsent(src, zipEntryFor(zip, entry))
        }
        return result
    }

    /** manifest 的 `src` 允许省略 `images/` 前缀（旧包），但归一后必须安全。 */
    private fun normalizedManifestSrc(raw: String): String? {
        val candidate = if (raw.startsWith(ImagePathUtil.IMAGE_DIR_PREFIX)) {
            raw
        } else {
            ImagePathUtil.IMAGE_DIR_PREFIX + raw
        }
        return candidate.takeIf(ImagePathUtil::isValidStoredSrc)
    }

    /** manifest 的 `file` 必须是 zip 内的 `images/<safe-name>`；允许旧布局前缀。 */
    private fun validatedImageEntry(raw: String): String? {
        val stripped = raw.removePrefix(LEGACY_PREFIX)
        if (!stripped.startsWith(ImagePathUtil.IMAGE_DIR_PREFIX)) return null
        val name = stripped.removePrefix(ImagePathUtil.IMAGE_DIR_PREFIX)
        if (!ImagePathUtil.isSafeImageFileName(name)) return null
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        if (ext !in IMAGE_EXTENSIONS) return null
        return ImagePathUtil.IMAGE_DIR_PREFIX + name
    }

    /** 实际 zip entry：优先无前缀布局，再兼容 `jdiary_export/` 前缀。 */
    private fun zipEntryFor(zip: ZipArchive.Reader, entry: String): String =
        if (zip.contains(entry)) entry
        else if (zip.contains(LEGACY_PREFIX + entry)) LEGACY_PREFIX + entry
        else entry

    private fun imageExtension(entry: String): String {
        val ext = entry.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return if (ext in IMAGE_EXTENSIONS) ext else DEFAULT_IMAGE_EXT
    }

    private fun readManifest(zip: ZipArchive.Reader): BackupManifest {
        val bytes = zip.readBytes(MANIFEST_NAME)
            ?: zip.readBytes("$LEGACY_PREFIX$MANIFEST_NAME")
            ?: throw BackupException.InvalidManifest()
        return runCatching {
            json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
        }.getOrElse { throw BackupException.InvalidManifest("manifest.json 解析失败：${it.message}") }
    }

    // ------------------------------------------------------------------ 工具

    private fun appVersionName(): String = runCatching {
        @Suppress("DEPRECATION")
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName
    }.getOrNull() ?: DEFAULT_APP_VERSION

    private fun timestampForFileName(): String =
        SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())

    companion object {
        const val MANIFEST_NAME: String = "manifest.json"
        const val DATABASE_NAME: String = "db/just_diary.db"
        const val LEGACY_PREFIX: String = "jdiary_export/"
        const val DEFAULT_IMAGE_EXT: String = "jpg"
        val IMAGE_EXTENSIONS: Set<String> = setOf("jpg", "jpeg", "png", "webp", "heic", "heif")
        const val DEFAULT_APP_VERSION: String = "1.0"
        const val BACKUP_EXTENSION: String = "jdiary"
        const val BACKUP_MIME: String = "application/octet-stream"
    }
}
