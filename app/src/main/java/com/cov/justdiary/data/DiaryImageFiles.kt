package com.cov.justdiary.data

import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.ImagePathUtil
import com.cov.justdiary.service.Log
import com.cov.justdiary.service.SearchUtil
import java.io.File

/**
 * `images/` 文件的三件事：清理孤儿、解析路径、删除已不引用的文件。
 *
 * 从 [DiaryRepository] 拆出；全部只依赖 SQLiteDatabase / 目录，不持有仓库状态，
 * 因此可以单独阅读，也方便在测试里用临时目录直接驱动。
 */

/** 删除 [imagesDir] 下不再被任何片段（或受保护草稿）引用的文件，返回删除数量。 */
internal fun cleanupOrphanImageFiles(
    db: android.database.sqlite.SQLiteDatabase,
    imagesDir: File,
    protectedSrcs: Set<String>,
): Int {
    val files = imagesDir.listFiles() ?: return 0
    val referenced = db.rawQuery(JustDiarySchema.SELECT_ALL_BLOCKS, null).use { c ->
        buildSet {
            while (c.moveToNext()) {
                val json = c.getString(c.getColumnIndexOrThrow("content_json")) ?: "[]"
                addAll(ImagePathUtil.collectImageSrcs(json))
            }
        }
    }
    var deleted = 0
    for (file in files) {
        val src = ImagePathUtil.IMAGE_DIR_PREFIX + file.name
        if (src !in referenced && src !in protectedSrcs) {
            if (runCatching { file.delete() }
                    .onFailure { Log.db.w("删除孤立图片失败：${file.name}") }
                    .getOrDefault(false)
            ) {
                deleted += 1
            }
        }
    }
    return deleted
}

/** `images/<name>` → 私有目录里的真实文件。 */
internal fun imageFileOf(imagesDir: File, src: String): File =
    File(imagesDir, ImagePathUtil.normalizeSrcKey(src).removePrefix(ImagePathUtil.IMAGE_DIR_PREFIX))

/** 新旧内容差集里的图片 src（删除动作在事务提交后做）。 */
internal fun removedImageSrcsBetween(oldJson: String, newJson: String): List<String> {
    val removed = ImagePathUtil.collectImageSrcs(oldJson).toSet() -
        ImagePathUtil.collectImageSrcs(newJson).toSet()
    return removed.toList()
}

/** 事务提交后删除不再被任何片段引用的图片文件。 */
internal fun deleteImagesIfUnreferenced(
    db: android.database.sqlite.SQLiteDatabase,
    imagesDir: File,
    srcs: List<String>,
) {
    if (srcs.isEmpty()) return
    for (src in srcs.distinct()) {
        // LIKE 的通配符要转义：图片名形如 img_<ms>.jpg，`_` 是 LIKE 的单字符通配符
        val stillReferenced = db.rawQuery(
            "SELECT 1 FROM edit_block WHERE content_json LIKE ? ESCAPE '\\' LIMIT 1;",
            arrayOf("%${SearchUtil.escapeLikeTerm(src)}%"),
        ).use { it.moveToFirst() }
        if (!stillReferenced) {
            runCatching { imageFileOf(imagesDir, src).delete() }
                .onFailure { Log.db.w("删除图片失败：$src") }
        }
    }
}
