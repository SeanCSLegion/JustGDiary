package com.cov.justdiary.service

import android.content.Context
import com.cov.justdiary.data.db.JustDiarySchema
import java.io.File

/**
 * P2-5：设置页“存储占用”的纯计算。
 *
 * 只统计应用私有目录里真实存在的内容：
 * - 数据库：`just_diary.db` + `-wal` / `-shm`（WAL 下未 checkpoint 的数据也在里面）；
 * - 图片：`filesDir/images`；
 * - 备份：`cacheDir` 里的 `.jdiary`（导出/导入暂存）。
 */
data class StorageUsage(
    val databaseBytes: Long = 0,
    val imagesBytes: Long = 0,
    val backupBytes: Long = 0,
) {
    val totalBytes: Long get() = databaseBytes + imagesBytes + backupBytes

    companion object {
        fun measure(context: Context, imagesDir: File): StorageUsage {
            val db = context.getDatabasePath(JustDiarySchema.DB_NAME)
            return measureFiles(
                databaseFiles = listOf(db, File("${db.path}-wal"), File("${db.path}-shm")),
                imagesDir = imagesDir,
                cacheDir = context.cacheDir,
            )
        }

        /** 文件列表可注入，便于 JVM 测试不碰真实 `Context`。 */
        internal fun measureFiles(
            databaseFiles: List<File>,
            imagesDir: File?,
            cacheDir: File?,
        ): StorageUsage = StorageUsage(
            databaseBytes = databaseFiles.filter { it.isFile }.sumOf { it.length() },
            imagesBytes = imagesDir?.listFiles()?.filter { it.isFile }?.sumOf { it.length() } ?: 0L,
            backupBytes = cacheDir?.listFiles()
                ?.filter { it.isFile && it.name.endsWith(".jdiary") }
                ?.sumOf { it.length() }
                ?: 0L,
        )
    }
}
