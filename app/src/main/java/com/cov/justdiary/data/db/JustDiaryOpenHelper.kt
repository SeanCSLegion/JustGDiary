package com.cov.justdiary.data.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import com.cov.justdiary.service.Log

/**
 * 库的打开与迁移。对应 iOS `Data/SQLite.swift` 的打开流程 + `DiaryRepository.migrateSchema`
 * / `migrateLocationMeta` / `ensureFtsTable`。
 *
 * 迁移链与 iOS 完全一致（`PRAGMA user_version`）：
 * ```
 * v0 --建三表 + 4 索引--> v1 --位置元数据(v1→v3)--> v3
 * ```
 * fresh install 也走同一条路径（先建 v1、再执行 v3 迁移步骤），这样这段代码
 * 在任何来源的库上都被走到，不会有"只有老库才测过"的分支。
 *
 * Android 与 iOS 的三处平台差异（有意为之，已记录）：
 *  1. `busy_timeout` 由 Android 框架自己管理（无公开 API 可设），不强行对齐 iOS 的 3000ms；
 *  2. WAL 通过 [setWriteAheadLoggingEnabled] 开启，与 iOS 的 `journal_mode=WAL` 一致；
 *  3. 迁移失败**向上抛异常**（已确认的决策 Q13：不保留 iOS 的静默降级）。
 */
class JustDiaryOpenHelper(context: Context) : SQLiteOpenHelper(
    context.applicationContext,
    JustDiarySchema.DB_NAME,
    null,
    JustDiarySchema.VERSION,
) {

    @Volatile
    var ftsSupported: Boolean = false
        private set

    init {
        // 与 iOS 的 journal_mode=WAL 对齐；WAL 下事务用 beginTransactionNonExclusive()（= BEGIN IMMEDIATE）
        setWriteAheadLoggingEnabled(true)
    }

    override fun onConfigure(db: SQLiteDatabase) {
        // iOS: PRAGMA foreign_keys=OFF —— 全库没有外键约束，一致性由代码保证
        db.setForeignKeyConstraintsEnabled(false)
    }

    override fun onCreate(db: SQLiteDatabase) {
        migrate(db, fromVersion = 0)
    }

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        migrate(db, fromVersion = oldVersion)
    }

    override fun onOpen(db: SQLiteDatabase) {
        if (!db.isReadOnly) ensureFtsTable(db)
    }

    // ------------------------------------------------------------------ 迁移

    private fun migrate(db: SQLiteDatabase, fromVersion: Int) {
        var version = fromVersion
        if (version < 1) {
            createV1(db)
            version = 1
        }
        if (version < 3) {
            migrateLocationMeta(db)
            version = 3
        }
        ensureFtsTable(db)
        Log.db.i("schema ready at user_version=$version (fts5=${ftsSupported})")
    }

    private fun createV1(db: SQLiteDatabase) {
        db.execSQL(JustDiarySchema.CREATE_DIARY)
        db.execSQL(JustDiarySchema.CREATE_EDIT_BLOCK)
        db.execSQL(JustDiarySchema.CREATE_DIARY_FLAG)
        JustDiarySchema.CREATE_INDICES.forEach(db::execSQL)
    }

    /**
     * v1 → v3 的位置元数据迁移：旧库的三级列叫 `province`/`city`/`district`，
     * 且可能缺 `loc_quality`。**必须原地重建表**（SQLite 早期版本不支持改列名），
     * 与 iOS `migrateLocationMeta` 的步骤一一对应。
     */
    private fun migrateLocationMeta(db: SQLiteDatabase) {
        val columns = tableColumns(db, JustDiarySchema.TABLE_EDIT_BLOCK)
        val needsRebuild = "region1" !in columns

        if (needsRebuild) {
            db.execSQL(JustDiarySchema.DROP_EDIT_BLOCK_NEW)
            db.execSQL(JustDiarySchema.CREATE_EDIT_BLOCK_NEW)
            db.execSQL(JustDiarySchema.copyEditBlockForLocationMetaMigration(columns))
            db.execSQL(JustDiarySchema.DROP_EDIT_BLOCK)
            db.execSQL(JustDiarySchema.RENAME_EDIT_BLOCK_NEW)
            JustDiarySchema.CREATE_INDICES.forEach(db::execSQL)
        } else if ("loc_quality" !in columns) {
            db.execSQL(JustDiarySchema.ADD_LOC_QUALITY)
        }

        db.execSQL(JustDiarySchema.BACKFILL_LOC_PRECISION)
        db.execSQL(JustDiarySchema.BACKFILL_LOC_QUALITY)
        db.execSQL(JustDiarySchema.FIX_REGION1)
    }

    private fun tableColumns(db: SQLiteDatabase, table: String): Set<String> {
        val columns = mutableSetOf<String>()
        db.rawQuery("PRAGMA table_info($table);", null).use { cursor ->
            val nameIndex = cursor.getColumnIndex("name")
            while (cursor.moveToNext()) {
                if (nameIndex >= 0) columns.add(cursor.getString(nameIndex))
            }
        }
        return columns
    }

    /**
     * 确保 FTS5 虚拟表存在。建表失败（该设备 SQLite 未编入 FTS5）时把 [ftsSupported]
     * 置 false，搜索退化为 LIKE —— 与 iOS `ensureFtsTable` 的容错完全一致。
     */
    private fun ensureFtsTable(db: SQLiteDatabase) {
        ftsSupported = try {
            db.execSQL(JustDiarySchema.CREATE_FTS)
            true
        } catch (error: Throwable) {
            Log.db.w("FTS5 不可用，搜索将退化为 LIKE：${error.message}")
            false
        }
    }

    /** FTS 虚拟表是否真的存在（用于判断是否需要重建索引）。 */
    fun isFtsTablePresent(db: SQLiteDatabase): Boolean =
        db.rawQuery(JustDiarySchema.FTS_TABLE_EXISTS, null).use { it.moveToFirst() }
}
