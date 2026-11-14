package com.cov.justdiary.data

import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.ImagePathUtil

/**
 * 备份合并的**读取侧**：老 schema 回落、图片路径校验、列探测。
 *
 * 从 [DiaryRepository] 拆出；写入侧仍留在仓库里（它依赖事务与 FTS 写入）。
 */

/** 某一天全部片段的 `content_json` 里引用到的图片键（OVERWRITE 前用来清理旧图）。 */
internal fun imageSrcsOfDiary(db: SQLiteDatabase, diaryId: Long): List<String> =
    db.rawQuery(
        "SELECT content_json FROM edit_block WHERE diary_id = ?;",
        arrayOf(diaryId.toString()),
    ).use { c ->
        buildList {
            while (c.moveToNext()) {
                addAll(ImagePathUtil.collectImageSrcs(c.getString(0) ?: "[]"))
            }
        }
    }

/** 导入前置校验：备份库中每个 `content_json.src` 都必须是 `images/<safe-name>`。 */
internal fun validateBackupImageSources(backupDb: SQLiteDatabase) {
    backupDb.rawQuery("SELECT content_json FROM edit_block;", null).use { cursor ->
        while (cursor.moveToNext()) {
            val raw = cursor.getString(0) ?: continue
            val srcs = runCatching { ImagePathUtil.collectImageSrcs(raw) }.getOrElse {
                throw BackupException.InvalidManifest("备份里有无法解析的 content_json：${it.message}")
            }
            for (src in srcs) {
                if (!ImagePathUtil.isValidImportSrc(src)) {
                    throw BackupException.InvalidManifest("备份包含非法图片路径：$src")
                }
            }
        }
    }
}

/** 读备份库里某一天的片段；老 schema 缺列时回落到 `province/city/district`。 */
internal fun readBackupBlocks(
    backupDb: SQLiteDatabase,
    diaryId: Long,
    hasRegion: Boolean,
    hasQuality: Boolean,
    hasCountryCode: Boolean,
): List<BackupBlockRow> = backupDb.rawQuery(
    JustDiarySchema.SELECT_BACKUP_BLOCKS_OF_DIARY,
    arrayOf(diaryId.toString()),
).use { c ->
    buildList {
        while (c.moveToNext()) {
            add(
                BackupBlockRow(
                    startTimeUtc = c.longOrNull("start_time_utc") ?: 0L,
                    locText = c.stringOrNull("loc_text") ?: "",
                    latitude = c.doubleOrNull("latitude") ?: 0.0,
                    longitude = c.doubleOrNull("longitude") ?: 0.0,
                    contentJson = c.stringOrNull("content_json") ?: "[]",
                    locPrecision = c.stringOrNull("loc_precision") ?: "none",
                    locQuality = if (hasQuality) c.stringOrNull("loc_quality") ?: "" else "",
                    country = c.stringOrNull("country") ?: "",
                    countryCode = if (hasCountryCode) c.stringOrNull("country_code") ?: "" else "",
                    region1 = if (hasRegion) c.stringOrNull("region1") ?: "" else c.stringOrNull("province") ?: "",
                    region2 = if (hasRegion) c.stringOrNull("region2") ?: "" else c.stringOrNull("city") ?: "",
                    region3 = if (hasRegion) c.stringOrNull("region3") ?: "" else c.stringOrNull("district") ?: "",
                    createdUtc = c.longOrNull("created_utc") ?: 0L,
                    updatedUtc = c.longOrNull("updated_utc") ?: 0L,
                ),
            )
        }
    }
}

internal fun tableColumnsOf(db: SQLiteDatabase, table: String): Set<String> =
    db.rawQuery("PRAGMA table_info($table);", null).use { c ->
        val index = c.getColumnIndex("name")
        buildSet { while (c.moveToNext()) if (index >= 0) add(c.getString(index)) }
    }

/**
 * 备份库里一条片段的中间表示。
 *
 * 备份库可能是**老 schema**：缺 `region1/2/3` 时回落读 `province/city/district`，
 * 缺 `loc_quality` 时由坐标推导 —— 这些回落必须在读取阶段做完。
 */
internal data class BackupBlockRow(
    val startTimeUtc: Long,
    val locText: String,
    val latitude: Double,
    val longitude: Double,
    val contentJson: String,
    val locPrecision: String,
    val locQuality: String,
    val country: String,
    val countryCode: String,
    val region1: String,
    val region2: String,
    val region3: String,
    val createdUtc: Long,
    val updatedUtc: Long,
)

/** 列不存在或值为 NULL 时返回 null（老 schema 的兼容读取需要它）。 */
internal fun Cursor.stringOrNull(name: String): String? {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) null else getString(index)
}

internal fun Cursor.longOrNull(name: String): Long? {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

internal fun Cursor.doubleOrNull(name: String): Double? {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) null else getDouble(index)
}
