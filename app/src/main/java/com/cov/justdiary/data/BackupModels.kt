package com.cov.justdiary.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 备份包的 `manifest.json`。字段名、类型、顺序与 iOS `BackupService.swift:5-29` 一致。
 *
 * 注意**不追求逐字节一致**：iOS 用无选项的 `JSONEncoder()`（键序不保证），
 * 所以这里只要保证字段名与类型可互相解析即可。真正要求字节级一致的是
 * `content_json`（见 `ContentFormatTest`）。
 */
@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: Long,
    val includeSettings: Boolean,
    val diaryCount: Int,
    val blockCount: Int,
    val imageCount: Int,
    val images: List<BackupImageRef>,
    val settingsJson: String,
) {
    companion object {
        /** 当前支持的备份格式版本；导入时要求严格相等（与 iOS 一致）。 */
        const val FORMAT_VERSION: Int = 1
    }
}

/** 备份包内一张图片的映射：`src` 是库里的键，`file` 是 zip 内的路径。 */
@Serializable
data class BackupImageRef(
    val src: String,
    val file: String,
)

/**
 * 导入结果统计。对应 iOS `BackupStats`。
 *
 * 与 iOS 的差异：iOS 用 `inout BackupStats` 跨线程回填，Kotlin 没有等价写法，
 * 因此这里作为**返回值**（见方案 §4.7.4）。
 */
data class BackupStats(
    val importedDays: Int = 0,
    val skippedDays: Int = 0,
    val overwrittenDays: Int = 0,
    val importedBlocks: Int = 0,
    val importedImages: Int = 0,
    val settingsRestored: Boolean = false,
)

/** 导入方式。iOS 侧是字符串 `"skip"` / 其它（视为覆盖）。 */
enum class ImportMode {
    /** 已存在的日期整天跳过。 */
    SKIP,

    /** 已存在的日期整天替换。 */
    OVERWRITE,
    ;

    companion object {
        fun fromWire(value: String): ImportMode =
            if (value == "skip") SKIP else OVERWRITE
    }
}

/** 备份/导入过程中的错误，对应 iOS `BackupError`。 */
sealed class BackupException(message: String) : Exception(message) {
    class InvalidManifest(message: String = "备份包缺少 manifest.json") : BackupException(message)
    class InvalidVersion(version: Int) :
        BackupException("不支持的备份格式版本：$version（需要 ${BackupManifest.FORMAT_VERSION}）")

    class Database(message: String) : BackupException(message)
}
