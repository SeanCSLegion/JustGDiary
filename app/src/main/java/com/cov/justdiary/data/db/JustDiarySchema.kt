package com.cov.justdiary.data.db

/**
 * 数据库 schema 与全部 SQL 语句，**逐条对齐** iOS `Data/DiaryRepository.swift`。
 *
 * 表名、列名、字符串取值、索引名、`user_version` 迁移链都保持不变 —— 这是跨端备份
 * 可以双向互导的前提（见 `docs/android-migration-plan.md` §4.7.2）。
 *
 * 连接参数也与 iOS 一致：WAL、`busy_timeout=3000`、`foreign_keys=OFF`、
 * 事务用 `BEGIN IMMEDIATE`（Android 侧对应 `beginTransactionNonExclusive()`）。
 */
object JustDiarySchema {

    const val DB_NAME: String = "just_diary.db"

    /** 当前 schema 版本（`PRAGMA user_version`）。iOS 侧同为 3。 */
    const val VERSION: Int = 3

    const val TABLE_DIARY: String = "diary"
    const val TABLE_EDIT_BLOCK: String = "edit_block"
    const val TABLE_DIARY_FLAG: String = "diary_flag"
    const val TABLE_DIARY_FTS: String = "diary_fts"

    // ------------------------------------------------------------------ v1 建表

    const val CREATE_DIARY: String = """
        CREATE TABLE IF NOT EXISTS diary (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          day_key TEXT NOT NULL UNIQUE,
          summary TEXT NOT NULL DEFAULT '',
          search_text TEXT NOT NULL DEFAULT '',
          created_utc INTEGER NOT NULL,
          updated_utc INTEGER NOT NULL
        );
    """

    const val CREATE_EDIT_BLOCK: String = """
        CREATE TABLE IF NOT EXISTS edit_block (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          diary_id INTEGER NOT NULL,
          start_time_utc INTEGER NOT NULL,
          loc_text TEXT NOT NULL DEFAULT '',
          latitude REAL NOT NULL DEFAULT 0,
          longitude REAL NOT NULL DEFAULT 0,
          content_json TEXT NOT NULL DEFAULT '[]',
          search_text TEXT NOT NULL DEFAULT '',
          loc_precision TEXT NOT NULL DEFAULT 'none',
          loc_quality TEXT NOT NULL DEFAULT 'none',
          country TEXT NOT NULL DEFAULT '',
          country_code TEXT NOT NULL DEFAULT '',
          region1 TEXT NOT NULL DEFAULT '',
          region2 TEXT NOT NULL DEFAULT '',
          region3 TEXT NOT NULL DEFAULT '',
          created_utc INTEGER NOT NULL,
          updated_utc INTEGER NOT NULL
        );
    """

    const val CREATE_DIARY_FLAG: String =
        "CREATE TABLE IF NOT EXISTS diary_flag (day_key TEXT PRIMARY KEY);"

    val CREATE_INDICES: List<String> = listOf(
        "CREATE INDEX IF NOT EXISTS idx_block_diary ON edit_block(diary_id);",
        "CREATE INDEX IF NOT EXISTS idx_block_start ON edit_block(start_time_utc);",
        "CREATE INDEX IF NOT EXISTS idx_block_country_region ON edit_block(country, region1, diary_id);",
        "CREATE INDEX IF NOT EXISTS idx_block_diary_quality ON edit_block(diary_id, loc_quality);",
    )

    // ------------------------------------------------------------------ FTS5

    /**
     * FTS5 虚拟表。**注意**：Room 的 `@Fts4/@Fts5` 注解不适合这里（相关子查询 `MATCH`、
     * `snippet()`、`UNINDEXED` 列都要手写 SQL），所以用裸 SQL 建表。
     *
     * `diary_id` 是 `UNINDEXED`（可存可查、不进索引）；`text` 是唯一被索引的列，
     * 写入前经过 `FtsSegment.segment`（相邻 CJK 之间插空格）。
     */
    const val CREATE_FTS: String =
        "CREATE VIRTUAL TABLE IF NOT EXISTS diary_fts USING fts5(diary_id UNINDEXED, text, tokenize='unicode61');"

    const val FTS_TABLE_EXISTS: String =
        "SELECT name FROM sqlite_master WHERE type='table' AND name='diary_fts';"

    const val FTS_DELETE_ALL: String = "DELETE FROM diary_fts;"
    const val FTS_DELETE_BY_DIARY: String = "DELETE FROM diary_fts WHERE diary_id = ?;"
    const val FTS_INSERT: String = "INSERT INTO diary_fts(diary_id, text) VALUES (?, ?);"
    const val FTS_SOURCE_ROWS: String = "SELECT id, search_text FROM diary WHERE search_text <> '';"

    // ------------------------------------------------------------------ 迁移：位置元数据（v1 → v3）

    const val PRAGMA_TABLE_INFO_EDIT_BLOCK: String = "PRAGMA table_info(edit_block);"

    const val CREATE_EDIT_BLOCK_NEW: String = """
        CREATE TABLE edit_block_new (
          id INTEGER PRIMARY KEY AUTOINCREMENT,
          diary_id INTEGER NOT NULL,
          start_time_utc INTEGER NOT NULL,
          loc_text TEXT NOT NULL DEFAULT '',
          latitude REAL NOT NULL DEFAULT 0,
          longitude REAL NOT NULL DEFAULT 0,
          content_json TEXT NOT NULL DEFAULT '[]',
          search_text TEXT NOT NULL DEFAULT '',
          loc_precision TEXT NOT NULL DEFAULT 'none',
          loc_quality TEXT NOT NULL DEFAULT 'none',
          country TEXT NOT NULL DEFAULT '',
          country_code TEXT NOT NULL DEFAULT '',
          region1 TEXT NOT NULL DEFAULT '',
          region2 TEXT NOT NULL DEFAULT '',
          region3 TEXT NOT NULL DEFAULT '',
          created_utc INTEGER NOT NULL,
          updated_utc INTEGER NOT NULL
        );
    """

    const val DROP_EDIT_BLOCK_NEW: String = "DROP TABLE IF EXISTS edit_block_new;"
    const val DROP_EDIT_BLOCK: String = "DROP TABLE edit_block;"
    const val RENAME_EDIT_BLOCK_NEW: String = "ALTER TABLE edit_block_new RENAME TO edit_block;"

    const val ADD_LOC_QUALITY: String =
        "ALTER TABLE edit_block ADD COLUMN loc_quality TEXT NOT NULL DEFAULT 'none';"

    /**
     * 老库（没有 `region1` 列）重建 `edit_block` 时的数据搬运。
     *
     * 与 iOS 一致：新列取同名列，**缺失的列取 `''`**；三级的旧列名是
     * `province` / `city` / `district`。
     */
    fun copyEditBlockForLocationMetaMigration(columns: Set<String>): String {
        fun col(name: String): String = if (name in columns) name else "''"
        return """
            INSERT INTO edit_block_new (id, diary_id, start_time_utc, loc_text, latitude, longitude,
              content_json, search_text, loc_precision, loc_quality, country, country_code,
              region1, region2, region3, created_utc, updated_utc)
            SELECT id, diary_id, start_time_utc, loc_text, latitude, longitude,
              ${col("content_json")}, ${col("search_text")}, ${col("loc_precision")}, ${col("loc_quality")},
              ${col("country")}, ${col("country_code")},
              ${col("province")}, ${col("city")}, ${col("district")}, created_utc, updated_utc
            FROM edit_block;
        """
    }

    /** 回填 `loc_precision`：由可用的地区列反推最低可表达的档位。 */
    const val BACKFILL_LOC_PRECISION: String = """
        UPDATE edit_block SET
          loc_precision = CASE
            WHEN region3 <> '' THEN 'district'
            WHEN region2 <> '' THEN 'city'
            WHEN region1 <> '' THEN 'province'
            WHEN loc_text <> '' THEN 'province'
            ELSE 'none' END
          WHERE loc_precision = 'none' OR loc_precision = '';
    """

    /** 回填 `loc_quality`：有坐标即 precise，否则 coarse。 */
    const val BACKFILL_LOC_QUALITY: String = """
        UPDATE edit_block SET
          loc_quality = CASE WHEN latitude <> 0 OR longitude <> 0 THEN 'precise' ELSE 'coarse' END
          WHERE loc_quality = 'none' OR loc_quality = '';
    """

    /** 修 `region1` 为空但 `region2` 有值的旧数据。 */
    const val FIX_REGION1: String =
        "UPDATE edit_block SET region1 = region2 WHERE region1 = '' AND region2 <> '';"

    // ------------------------------------------------------------------ 读

    const val SELECT_DIARY_BY_DAY: String = "SELECT * FROM diary WHERE day_key = ?;"

    const val SELECT_DIARY_FLAGS_RANGE: String =
        "SELECT day_key FROM diary_flag WHERE day_key >= ? AND day_key <= ?;"

    const val SELECT_BLOCKS_BY_DIARY: String =
        "SELECT * FROM edit_block WHERE diary_id = ? ORDER BY start_time_utc ASC;"

    const val SELECT_ALL_BLOCKS: String = "SELECT * FROM edit_block;"

    const val SELECT_BLOCKS_FOR_INDEX: String = "SELECT id, content_json FROM edit_block ORDER BY id;"

    const val FOOTPRINT_ROWS: String = """
        SELECT b.id AS id, d.day_key AS day_key, b.start_time_utc AS start_time_utc,
               b.latitude AS latitude, b.longitude AS longitude, b.loc_text AS loc_text,
               b.loc_quality AS loc_quality, b.loc_precision AS loc_precision,
               b.country AS country, b.country_code AS country_code,
               b.region1 AS region1, b.region2 AS region2, b.region3 AS region3,
               d.summary AS summary
        FROM edit_block b JOIN diary d ON d.id = b.diary_id
        ORDER BY b.start_time_utc ASC;
    """

    const val LOCATION_OPTIONS_WITH_REGION: String = """
        SELECT b.country AS country, b.region1 AS region1, COUNT(DISTINCT d.id) AS cnt
        FROM edit_block b JOIN diary d ON d.id = b.diary_id
        WHERE d.day_key >= ? AND d.day_key <= ? AND b.region1 <> '' AND b.country <> ''
        AND (b.country LIKE ? OR b.region1 LIKE ?)
        GROUP BY b.country, b.region1;
    """

    const val LOCATION_OPTIONS_COUNTRY_ONLY: String = """
        SELECT b.country AS country, COUNT(DISTINCT d.id) AS cnt
        FROM edit_block b JOIN diary d ON d.id = b.diary_id
        WHERE d.day_key >= ? AND d.day_key <= ? AND b.region1 = '' AND b.country <> ''
        AND b.country LIKE ?
        GROUP BY b.country;
    """

    /**
     * 「这一天完全没有地点」的判定。
     *
     * 只要当天**没有任何**片段满足"质量非 none / 地点文本非空 / 任一地区列非空 / 坐标非 0"，
     * 这一天就算未记录地点。与 iOS `noLocSql` 逐字一致。
     */
    const val NO_LOC_SQL: String = """
        NOT EXISTS (SELECT 1 FROM edit_block b WHERE b.diary_id = d.id
          AND (b.loc_quality <> 'none' OR b.loc_text <> '' OR b.country <> '' OR b.region1 <> ''
               OR b.region2 <> '' OR b.region3 <> '' OR b.latitude != 0 OR b.longitude != 0))
    """

    const val COUNT_LOCATION_OPTIONS_NO_LOC: String = "SELECT COUNT(*) AS cnt FROM diary d WHERE d.day_key >= ? AND d.day_key <= ? AND $NO_LOC_SQL;"

    const val COUNT_DIARIES: String = "SELECT COUNT(*) AS cnt FROM diary;"
    const val COUNT_BLOCKS: String = "SELECT COUNT(*) AS cnt FROM edit_block;"

    // ------------------------------------------------------------------ 写

    const val INSERT_DIARY_IF_ABSENT: String =
        "INSERT OR IGNORE INTO diary(day_key, created_utc, updated_utc) VALUES (?, ?, ?);"

    const val SELECT_DIARY_ID_BY_DAY: String = "SELECT id FROM diary WHERE day_key = ?;"

    const val INSERT_DIARY_FLAG_IF_ABSENT: String =
        "INSERT OR IGNORE INTO diary_flag(day_key) VALUES (?);"

    const val INSERT_BLOCK: String = """
        INSERT INTO edit_block(diary_id, start_time_utc, loc_text, latitude, longitude,
          content_json, search_text, loc_precision, loc_quality, country, country_code,
          region1, region2, region3, created_utc, updated_utc)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?);
    """

    const val SELECT_BLOCK_JSON_AND_DIARY: String =
        "SELECT content_json, diary_id FROM edit_block WHERE id = ?;"

    const val UPDATE_BLOCK_CONTENT_WITH_LOCATION: String = """
        UPDATE edit_block SET content_json = ?, search_text = ?, updated_utc = ?,
          loc_text = ?, loc_precision = ? WHERE id = ?;
    """

    const val UPDATE_BLOCK_CONTENT_ONLY: String =
        "UPDATE edit_block SET content_json = ?, search_text = ?, updated_utc = ? WHERE id = ?;"

    const val SELECT_BLOCK_JSON_BY_IDS_PREFIX: String = "SELECT content_json FROM edit_block WHERE id IN ("
    const val SELECT_DIARY_IDS_BY_BLOCK_IDS_PREFIX: String =
        "SELECT DISTINCT diary_id FROM edit_block WHERE id IN ("
    const val DELETE_BLOCKS_PREFIX: String = "DELETE FROM edit_block WHERE id IN ("

    const val COUNT_BLOCKS_OF_DIARY: String =
        "SELECT COUNT(*) AS cnt FROM edit_block WHERE diary_id = ?;"

    const val SELECT_DAY_KEY_OF_DIARY: String = "SELECT day_key FROM diary WHERE id = ?;"
    const val DELETE_DIARY_BY_ID: String = "DELETE FROM diary WHERE id = ?;"
    const val DELETE_DIARY_FLAG: String = "DELETE FROM diary_flag WHERE day_key = ?;"

    /** 由片段重算某一天的 `summary` / `search_text`（全库最热的语句）。 */
    const val TOUCH_DIARY: String = """
        UPDATE diary SET
          summary = substr(IFNULL((SELECT group_concat(search_text, ' ') FROM edit_block WHERE diary_id = ?), ''), 1, 40),
          search_text = IFNULL((SELECT group_concat(search_text, ' ') FROM edit_block WHERE diary_id = ?), ''),
          updated_utc = ?
        WHERE id = ?;
    """

    const val SELECT_DIARY_SUMMARY_FOR_FTS: String =
        "SELECT id, search_text FROM diary WHERE id = ? AND search_text <> '';"

    // ------------------------------------------------------------------ 索引重建

    const val UPDATE_BLOCK_INDEX_TEXT: String =
        "UPDATE edit_block SET content_json = ?, search_text = ? WHERE id = ?;"

    /** 一次性内容格式迁移只改 `content_json`（文本没变，因此不碰 `search_text` 与 FTS）。 */
    const val UPDATE_BLOCK_CONTENT_JSON_ONLY: String =
        "UPDATE edit_block SET content_json = ? WHERE id = ?;"

    const val RECOMPUTE_ALL_DIARY_TEXTS: String = """
        UPDATE diary SET
          summary = substr(IFNULL((SELECT group_concat(search_text, ' ') FROM edit_block WHERE diary_id = diary.id), ''), 1, 40),
          search_text = IFNULL((SELECT group_concat(search_text, ' ') FROM edit_block WHERE diary_id = diary.id), '');
    """

    // ------------------------------------------------------------------ 日期重算

    const val DIARY_DAY_KEYS_WITH_FIRST_BLOCK: String = """
        SELECT d.id AS id, d.day_key AS day_key, d.created_utc AS created_utc,
               COALESCE(MIN(b.start_time_utc), 0) AS first_utc
        FROM diary d LEFT JOIN edit_block b ON b.diary_id = d.id
        GROUP BY d.id;
    """

    const val UPDATE_DIARY_DAY_KEY: String = "UPDATE diary SET day_key = ? WHERE id = ?;"
    const val CLEAR_DIARY_FLAGS: String = "DELETE FROM diary_flag;"
    const val REBUILD_DIARY_FLAGS: String =
        "INSERT OR IGNORE INTO diary_flag(day_key) SELECT day_key FROM diary;"

    /** 重算期间的临时键前缀（绕开 `day_key UNIQUE` 冲突）。 */
    fun temporaryDayKey(diaryId: Long): String = "tmp#$diaryId"

    // ------------------------------------------------------------------ 地区回填

    const val SELECT_BLOCKS_NEEDING_REGION: String = """
        SELECT id, latitude, longitude FROM edit_block
        WHERE (latitude <> 0 OR longitude <> 0) AND region1 = ''
        ORDER BY id ASC LIMIT ?;
    """

    const val UPDATE_BLOCK_REGIONS: String = """
        UPDATE edit_block SET country = ?, country_code = ?, region1 = ?, region2 = ?, region3 = ?, loc_quality = ?
        WHERE id = ? AND region1 = '';
    """

    // ------------------------------------------------------------------ 备份合并

    const val SELECT_BACKUP_DIARIES: String = "SELECT * FROM diary ORDER BY day_key ASC;"

    const val SELECT_BACKUP_BLOCKS_OF_DIARY: String =
        "SELECT * FROM edit_block WHERE diary_id = ? ORDER BY start_time_utc ASC;"

    const val DELETE_BLOCKS_OF_DIARY: String = "DELETE FROM edit_block WHERE diary_id = ?;"

    const val INSERT_DIARY: String =
        "INSERT INTO diary(day_key, created_utc, updated_utc) VALUES (?, ?, ?);"

    const val UPDATE_DIARY_TEXTS: String =
        "UPDATE diary SET summary = ?, search_text = ? WHERE id = ?;"

    const val SELECT_DIARY_ID_BY_DAY_FOR_MERGE: String = "SELECT id FROM diary WHERE day_key = ?;"

    /** 把主库快照成备份包里的 `db/just_diary.db`；`VACUUM INTO` 等价于 iOS 的 `sqlite3_backup`。 */
    fun vacuumInto(path: String): String = "VACUUM INTO '${path.replace("'", "''")}';"

    // ------------------------------------------------------------------ 设置项

    /** 与 iOS `DiaryRepository.searchIndexVersion` 一致。 */
    const val SEARCH_INDEX_VERSION: Int = 3

    /** 单页搜索上限（多取 1 条判断 `hasMore`）。 */
    const val SEARCH_LIMIT: Int = 200

    /** 索引重建时每批处理的行数。 */
    const val REBUILD_CHUNK: Int = 500
}
