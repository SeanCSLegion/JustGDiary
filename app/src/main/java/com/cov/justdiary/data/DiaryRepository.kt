package com.cov.justdiary.data

import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import com.cov.justdiary.data.db.JustDiaryOpenHelper
import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.ContentDocument
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.DiaryRecord
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.model.FootprintRow
import com.cov.justdiary.model.ImagePathUtil
import com.cov.justdiary.model.LocFilter
import com.cov.justdiary.model.LocOption
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.model.SearchPageResult
import com.cov.justdiary.model.SearchResultItem
import com.cov.justdiary.service.FtsSegment
import com.cov.justdiary.service.Log
import com.cov.justdiary.service.SearchUtil
import com.cov.justdiary.service.SettingsStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.CoroutineContext

/**
 * 日记数据访问层。对应 iOS `Data/DiaryRepository.swift`（1,039 行）。
 *
 * ## 并发模型
 *
 * iOS 用一条串行 `DispatchQueue` 承载**全部**数据库访问。这里的等价物是
 * [Dispatchers.IO] 上 `limitedParallelism(1)` 的单并发调度器 + [writeMutex]；
 * 事务用 `beginTransactionNonExclusive()`（= `BEGIN IMMEDIATE`，与 iOS 一致）。
 *
 * ## 与 iOS 的两处有意差异
 *
 * 1. **迁移失败会抛异常**（Q13 已确认：不保留 iOS 的 `do/catch` 静默降级）；
 * 2. **删除片段时按"是否仍被其他片段引用"决定是否删图片**。iOS 的
 *    `removeUnreferencedImages` 只按被删片段自己的 json 删文件，而备份导入会让
 *    两个片段共用同一张图（导入时按 src 复用映射）—— 那样删一个片段会删掉另一个
 *    片段仍在用的图。这里改成先查库再删，**库内容与 JSON 格式不受影响**。
 *
 * 备份/导入（zip）在下一里程碑接入，见 `docs/migration-progress.md`。
 */
class DiaryRepository(
    context: Context,
    private val settings: SettingsStore,
) {

    private val appContext: Context = context.applicationContext
    private val helper = JustDiaryOpenHelper(appContext)
    private val singleThread: CoroutineContext = Dispatchers.IO.limitedParallelism(1)
    private val writeMutex = Mutex()

    val imagesDir: File get() = File(appContext.filesDir, ImagePathUtil.IMAGE_DIR_NAME)

    /** FTS5 是否可用；不可用时搜索自动退化为 LIKE。 */
    val ftsSupported: Boolean get() = helper.ftsSupported

    // ------------------------------------------------------------------ 生命周期

    /** 打开库、跑一次性迁移、必要时重建索引。对应 iOS `prepare()`。 */
    suspend fun prepare() {
        writeMutex.withLock {
            withContext(singleThread) {
                val db = helper.writableDatabase
                imagesDir.mkdirs()
                migrateContentFormatIfNeeded(db)
                rebuildIndexIfNeeded(db)
            }
        }
    }

    /** 是否需要重建 FTS 索引（与 iOS `backfillIndexIfNeeded` 的判定一致）。 */
    fun searchIndexReady(): Boolean = !settings.load().needsSearchIndexRebuild

    private fun rebuildIndexIfNeeded(db: SQLiteDatabase) {
        if (searchIndexReady()) {
            if (ftsSupported && helper.isFtsTablePresent(db)) return
            if (!ftsSupported) return
        }
        rebuildSearchIndex(db)
    }

    suspend fun ensureSearchIndex() {
        writeMutex.withLock {
            withContext(singleThread) { rebuildIndexIfNeeded(helper.writableDatabase) }
        }
    }

    /**
     * 把库里残留的 v1 `content_json` 原地升级为 v2，并把图片路径归一。
     *
     * 只改 `content_json`；文本没变，因此 `search_text` 与 FTS 索引不受影响（与 iOS 注释一致）。
     * 由 `content_format_version` 保证只跑一次。
     */
    suspend fun migrateContentFormat() {
        writeMutex.withLock {
            withContext(singleThread) {
                val db = helper.writableDatabase
                migrateContentFormatIfNeeded(db)
            }
        }
    }

    private fun migrateContentFormatIfNeeded(db: SQLiteDatabase) {
        if (!settings.load().needsContentMigration) return
        Log.db.i("开始一次性内容格式迁移 → v${ContentDocument.CURRENT_VERSION}")

        db.beginTransactionNonExclusive()
        try {
            db.rawQuery(JustDiarySchema.SELECT_BLOCKS_FOR_INDEX, null).use { cursor ->
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(0)
                    val json = cursor.getString(1) ?: "[]"
                    val normalized = ImagePathUtil.normalizeContent(json)
                    if (normalized != json) {
                        db.execSQL(JustDiarySchema.UPDATE_BLOCK_CONTENT_JSON_ONLY, arrayOf<Any?>(normalized, id))
                    }
                }
            }
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        settings.contentFormatVersion = ContentDocument.CURRENT_VERSION
    }

    // ------------------------------------------------------------------ 读

    suspend fun getDiaryByDay(dayKey: String): DiaryRecord? = withContext(singleThread) {
        helper.writableDatabase.rawQuery(JustDiarySchema.SELECT_DIARY_BY_DAY, arrayOf(dayKey)).use { c ->
            if (c.moveToFirst()) diaryFrom(c) else null
        }
    }

    suspend fun getDiaryFlagsRange(fromKey: String, toKey: String): List<String> =
        withContext(singleThread) {
            helper.writableDatabase
                .rawQuery(JustDiarySchema.SELECT_DIARY_FLAGS_RANGE, arrayOf(fromKey, toKey)).use { c ->
                    buildList { while (c.moveToNext()) add(c.getString(0)) }
                }
        }

    suspend fun getBlocks(diaryId: Long): List<EditBlock> = withContext(singleThread) {
        helper.writableDatabase
            .rawQuery(JustDiarySchema.SELECT_BLOCKS_BY_DIARY, arrayOf(diaryId.toString())).use { c ->
                buildList { while (c.moveToNext()) add(blockFrom(c)) }
            }
    }

    suspend fun allFootprintRows(): List<FootprintRow> = withContext(singleThread) {
        helper.writableDatabase.rawQuery(JustDiarySchema.FOOTPRINT_ROWS, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(
                        FootprintRow(
                            id = c.getLong(c.getColumnIndexOrThrow("id")),
                            dayKey = c.getString(c.getColumnIndexOrThrow("day_key")) ?: "",
                            startTimeUtc = c.getLong(c.getColumnIndexOrThrow("start_time_utc")),
                            latitude = c.getDouble(c.getColumnIndexOrThrow("latitude")),
                            longitude = c.getDouble(c.getColumnIndexOrThrow("longitude")),
                            locText = c.getString(c.getColumnIndexOrThrow("loc_text")) ?: "",
                            locQuality = c.getString(c.getColumnIndexOrThrow("loc_quality")) ?: LocQuality.none,
                            locPrecision = c.getString(c.getColumnIndexOrThrow("loc_precision")) ?: "none",
                            country = c.getString(c.getColumnIndexOrThrow("country")) ?: "",
                            countryCode = c.getString(c.getColumnIndexOrThrow("country_code")) ?: "",
                            region1 = c.getString(c.getColumnIndexOrThrow("region1")) ?: "",
                            region2 = c.getString(c.getColumnIndexOrThrow("region2")) ?: "",
                            region3 = c.getString(c.getColumnIndexOrThrow("region3")) ?: "",
                            summary = c.getString(c.getColumnIndexOrThrow("summary")) ?: "",
                        ),
                    )
                }
            }
        }
    }

    /** 地点筛选面板的候选与"未记录地点"的天数。对应 iOS `getLocationOptions`。 */
    suspend fun getLocationOptions(
        keyword: String,
        fromKey: String,
        toKey: String,
    ): Pair<List<LocOption>, Int> = withContext(singleThread) {
        val db = helper.writableDatabase
        val kw = keyword.trim()
        val kwLike = "%$kw%"

        val merged = LinkedHashMap<String, LocOption>()
        fun merge(key: String, option: LocOption) {
            val existing = merged[key]
            merged[key] = if (existing == null) option else existing.copy(count = existing.count + option.count)
        }

        db.rawQuery(
            JustDiarySchema.LOCATION_OPTIONS_WITH_REGION,
            arrayOf(fromKey, toKey, kwLike, kwLike),
        ).use { c ->
            while (c.moveToNext()) {
                val country = c.getString(c.getColumnIndexOrThrow("country")) ?: ""
                val region1 = c.getString(c.getColumnIndexOrThrow("region1")) ?: ""
                val count = c.getLong(c.getColumnIndexOrThrow("cnt")).toInt()
                merge("$country\u0001$region1", LocOption(country, region1, count))
            }
        }

        db.rawQuery(
            JustDiarySchema.LOCATION_OPTIONS_COUNTRY_ONLY,
            arrayOf(fromKey, toKey, kwLike),
        ).use { c ->
            while (c.moveToNext()) {
                val country = c.getString(c.getColumnIndexOrThrow("country")) ?: ""
                val count = c.getLong(c.getColumnIndexOrThrow("cnt")).toInt()
                merge("$country\u0001", LocOption(country, "", count))
            }
        }

        val options = merged.values
            .sortedWith(
                compareByDescending<LocOption> { it.count }
                    .thenBy { it.country + it.region1 },
            )
            .take(LOCATION_OPTION_LIMIT)

        val noLoc = db.rawQuery(
            JustDiarySchema.COUNT_LOCATION_OPTIONS_NO_LOC,
            arrayOf(fromKey, toKey),
        ).use { c -> if (c.moveToFirst()) c.getLong(0).toInt() else 0 }

        options to noLoc
    }

    suspend fun countDiaries(): Int = withContext(singleThread) { count(JustDiarySchema.COUNT_DIARIES) }

    suspend fun countBlocks(): Int = withContext(singleThread) { count(JustDiarySchema.COUNT_BLOCKS) }

    private fun count(sql: String): Int =
        helper.writableDatabase.rawQuery(sql, null).use { c -> if (c.moveToFirst()) c.getLong(0).toInt() else 0 }

    // ------------------------------------------------------------------ 写

    /**
     * 新建片段：必要时建"这一天"，写位置列，重算当天的检索文本。
     * 对应 iOS `addBlock`。
     */
    suspend fun addBlock(
        startTimeUtc: Long,
        locText: String,
        latitude: Double,
        longitude: Double,
        locPrecision: String,
        region: LocRegion,
        contentJson: String,
        dayStartHour: Int,
    ): Long = write { db ->
        val dayKey = DateUtil.dayKeyForUtc(startTimeUtc, dayStartHour)
        db.execSQL(JustDiarySchema.INSERT_DIARY_IF_ABSENT, arrayOf<Any?>(dayKey, startTimeUtc, startTimeUtc))
        val diaryId = diaryIdOf(db, dayKey)
        db.execSQL(JustDiarySchema.INSERT_DIARY_FLAG_IF_ABSENT, arrayOf(dayKey))
        val blockId = insertBlock(db, diaryId, startTimeUtc, locText, latitude, longitude, locPrecision, region, contentJson)
        touchDiary(db, diaryId, startTimeUtc)
        blockId
    }

    /** 往已有日记追加片段（"继续写"）。对应 iOS `addBlockToDiary`。 */
    suspend fun addBlockToDiary(
        diaryId: Long,
        startTimeUtc: Long,
        locText: String,
        latitude: Double,
        longitude: Double,
        locPrecision: String,
        region: LocRegion,
        contentJson: String,
    ): Long = write { db ->
        val blockId = insertBlock(db, diaryId, startTimeUtc, locText, latitude, longitude, locPrecision, region, contentJson)
        touchDiary(db, diaryId, startTimeUtc)
        blockId
    }

    /**
     * 保存片段内容；位置列**只在两个参数都非空时**才写（坐标与地区列属于写入那一刻的事实）。
     * 对应 iOS `updateBlockContent`。
     */
    suspend fun updateBlockContent(
        blockId: Long,
        contentJson: String,
        locText: String? = null,
        locPrecision: String? = null,
    ) {
        write<List<String>>(
            block = { db ->
                val (oldJson, diaryId) = db.rawQuery(
                    JustDiarySchema.SELECT_BLOCK_JSON_AND_DIARY,
                    arrayOf(blockId.toString()),
                ).use { c ->
                    if (c.moveToFirst()) (c.getString(0) ?: "[]") to c.getLong(1) else "[]" to 0L
                }

                val now = System.currentTimeMillis()
                val newJson = ImagePathUtil.normalizeContent(contentJson)
                val text = ContentFlatten.flattenContent(newJson)

                if (locText != null && locPrecision != null) {
                    db.execSQL(
                        JustDiarySchema.UPDATE_BLOCK_CONTENT_WITH_LOCATION,
                        arrayOf<Any?>(newJson, text, now, locText, locPrecision, blockId),
                    )
                } else {
                    db.execSQL(JustDiarySchema.UPDATE_BLOCK_CONTENT_ONLY, arrayOf<Any?>(newJson, text, now, blockId))
                }
                if (diaryId > 0) touchDiary(db, diaryId, now)

                // 文件删除必须等事务提交后再做；否则事务回滚时 JSON 仍引用已经删掉的文件。
                removedImageSrcsBetween(oldJson, newJson)
            },
            afterCommit = { db, removed -> deleteImagesIfUnreferenced(db, removed) },
        )
    }

    /**
     * 批量删除片段；某天删到没有片段时，连 `diary` / `diary_flag` / FTS 行一起删。
     * 对应 iOS `deleteBlocks`（图片清理改成"仍被引用则不删"，见类注释）。
     */
    suspend fun deleteBlocks(ids: List<Long>) {
        if (ids.isEmpty()) return
        write<List<String>>(
            block = { db ->
                val placeholders = ids.joinToString(",") { "?" }
                val args = ids.map { it.toString() }.toTypedArray()

                val removedJsons = db.rawQuery(
                    JustDiarySchema.SELECT_BLOCK_JSON_BY_IDS_PREFIX + placeholders + ");",
                    args,
                ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0) ?: "[]") } }

                val diaryIds = db.rawQuery(
                    JustDiarySchema.SELECT_DIARY_IDS_BY_BLOCK_IDS_PREFIX + placeholders + ");",
                    args,
                ).use { c -> buildList { while (c.moveToNext()) add(c.getLong(0)) } }

                db.execSQL(JustDiarySchema.DELETE_BLOCKS_PREFIX + placeholders + ");", args)

                for (diaryId in diaryIds) {
                    val remaining = db.rawQuery(
                        JustDiarySchema.COUNT_BLOCKS_OF_DIARY,
                        arrayOf(diaryId.toString()),
                    ).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

                    if (remaining == 0L) {
                        val dayKey = db.rawQuery(JustDiarySchema.SELECT_DAY_KEY_OF_DIARY, arrayOf(diaryId.toString()))
                            .use { c -> if (c.moveToFirst()) c.getString(0) else null }
                        if (dayKey != null) {
                            db.execSQL(JustDiarySchema.DELETE_DIARY_BY_ID, arrayOf(diaryId))
                            db.execSQL(JustDiarySchema.DELETE_DIARY_FLAG, arrayOf(dayKey))
                            if (ftsSupported) db.execSQL(JustDiarySchema.FTS_DELETE_BY_DIARY, arrayOf(diaryId))
                        }
                    } else {
                        touchDiary(db, diaryId, System.currentTimeMillis())
                    }
                }

                // 图片也要等事务提交后再删（`afterCommit`）。
                removedJsons.flatMap(ImagePathUtil::collectImageSrcs)
            },
            afterCommit = { db, removed -> deleteImagesIfUnreferenced(db, removed) },
        )
    }

    /** 删除某一天的全部内容（编辑器往返测试的 `-ui-test-reset-data` 用到）。 */
    suspend fun deleteDiaryByDay(dayKey: String) {
        val diary = getDiaryByDay(dayKey) ?: return
        deleteBlocks(getBlocks(diary.id).map { it.id })
    }

    // ------------------------------------------------------------------ 索引重建

    /** 重建全部派生文本与 FTS 索引。对应 iOS `rebuildSearchIndex`。 */
    suspend fun rebuildSearchIndex() {
        writeMutex.withLock {
            withContext(singleThread) { rebuildSearchIndex(helper.writableDatabase) }
        }
    }

    private fun rebuildSearchIndex(db: SQLiteDatabase) {
        val blocks = db.rawQuery(JustDiarySchema.SELECT_BLOCKS_FOR_INDEX, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to (c.getString(1) ?: "[]")) }
        }

        blocks.chunked(JustDiarySchema.REBUILD_CHUNK).forEach { chunk ->
            db.beginTransactionNonExclusive()
            try {
                for ((id, json) in chunk) {
                    val normalized = ImagePathUtil.normalizeContent(json)
                    val text = ContentFlatten.flattenContent(normalized)
                    db.execSQL(JustDiarySchema.UPDATE_BLOCK_INDEX_TEXT, arrayOf<Any?>(normalized, text, id))
                }
                db.setTransactionSuccessful()
            } finally {
                db.endTransaction()
            }
        }

        db.execSQL(JustDiarySchema.RECOMPUTE_ALL_DIARY_TEXTS)

        if (ftsSupported) {
            db.execSQL(JustDiarySchema.FTS_DELETE_ALL)
            db.rawQuery(JustDiarySchema.FTS_SOURCE_ROWS, null).use { c ->
                while (c.moveToNext()) upsertFtsEntry(db, c.getLong(0), c.getString(1) ?: "")
            }
        }

        settings.searchIndexVersion = JustDiarySchema.SEARCH_INDEX_VERSION
        Log.db.i("搜索索引重建完成：${blocks.size} 个片段，fts=${ftsSupported}")
    }

    private fun upsertFtsEntry(db: SQLiteDatabase, diaryId: Long, searchText: String) {
        db.execSQL(JustDiarySchema.FTS_DELETE_BY_DIARY, arrayOf(diaryId))
        if (searchText.isNotEmpty()) {
            db.execSQL(JustDiarySchema.FTS_INSERT, arrayOf<Any?>(diaryId, FtsSegment.segment(searchText)))
        }
    }

    // ------------------------------------------------------------------ 搜索

    /** 全文搜索一页。对应 iOS `search`。 */
    suspend fun search(
        keyword: String,
        fromKey: String,
        toKey: String,
        filter: LocFilter,
        offset: Int,
    ): SearchPageResult = withContext(singleThread) {
        val db = helper.writableDatabase
        val ftsEnabled = ftsSupported
        val sql = SearchSql.build(keyword, fromKey, toKey, filter, offset, ftsEnabled)
        val terms = SearchUtil.terms(keyword)

        val total = db.rawQuery(SearchSql.countSql(sql.whereSql), sql.countArgs.toTypedArray())
            .use { c -> if (c.moveToFirst()) c.getLong(0).toInt() else 0 }

        var items = db.rawQuery(sql.listSql, sql.listArgs.toTypedArray()).use { c ->
            buildList {
                while (c.moveToNext()) {
                    val id = c.getLong(c.getColumnIndexOrThrow("id"))
                    val dayKey = c.getString(c.getColumnIndexOrThrow("day_key")) ?: ""
                    val summary = c.getString(c.getColumnIndexOrThrow("summary")) ?: ""
                    val updatedUtc = c.getLong(c.getColumnIndexOrThrow("updated_utc"))
                    var snippet = c.getString(c.getColumnIndexOrThrow("snip")) ?: ""

                    if (snippet.isNotEmpty()) snippet = FtsSegment.desegment(snippet)
                    if (snippet.isEmpty() && terms.isNotEmpty()) {
                        snippet = SearchUtil.buildFallbackSnippet(summary, keyword)
                    }
                    add(SearchResultItem(id, dayKey, summary, snippet, updatedUtc))
                }
            }
        }

        val hasMore = items.size > JustDiarySchema.SEARCH_LIMIT
        if (hasMore) items = items.take(JustDiarySchema.SEARCH_LIMIT)
        SearchPageResult(items, hasMore, total)
    }

    // ------------------------------------------------------------------ 日期重算

    /**
     * 改「新一天的开始时间」会改动归属日的日记篇数（**只算不写**）。
     *
     * 设计规范 `05-search-footprint-settings.md` §3.4：Android **先问再改** ——
     * 先算出会改变归属日的篇数，非零时先弹确认，用户点了"继续"才真正写库。
     * iOS 是先改后提示，用户没有反悔的机会。
     */
    internal suspend fun previewDayKeyChanges(dayStartHour: Int): DayKeyPlan = withContext(singleThread) {
        planDayKeys(readDetachedDayKeyRows(), dayStartHour)
    }

    /**
     * 改了"新一天的开始时间"之后重算全部 `day_key`，返回无法迁移的冲突天数。
     *
     * 用 `tmp#<id>` 两阶段写绕开 `day_key UNIQUE`；目标键被另一条**也在移动**的记录占用时
     * 计为冲突并跳过（与 iOS `recomputeDayKeys` 一致，返回值供设置页提示）。
     */
    suspend fun recomputeDayKeys(dayStartHour: Int): Int = write { db ->
        val plan = planDayKeys(readDayKeyRows(db), dayStartHour)
        for (id in plan.movers) {
            db.execSQL(JustDiarySchema.UPDATE_DIARY_DAY_KEY, arrayOf<Any?>(JustDiarySchema.temporaryDayKey(id), id))
        }
        for ((id, key) in plan.finalKeys) {
            db.execSQL(JustDiarySchema.UPDATE_DIARY_DAY_KEY, arrayOf<Any?>(key, id))
        }
        if (plan.movers.isNotEmpty()) {
            db.execSQL(JustDiarySchema.CLEAR_DIARY_FLAGS)
            db.execSQL(JustDiarySchema.REBUILD_DIARY_FLAGS)
        }
        plan.conflicts
    }

    private fun readDayKeyRows(db: android.database.sqlite.SQLiteDatabase): List<DayKeyRow> =
        db.rawQuery(JustDiarySchema.DIARY_DAY_KEYS_WITH_FIRST_BLOCK, null).use { it.toDayKeyRows() }

    /** 走只读连接读一次（预览用；与写事务里的那份是同一张表）。 */
    private suspend fun readDetachedDayKeyRows(): List<DayKeyRow> = withContext(singleThread) {
        helper.readableDatabase
            .rawQuery(JustDiarySchema.DIARY_DAY_KEYS_WITH_FIRST_BLOCK, null)
            .use { it.toDayKeyRows() }
    }

    private fun android.database.Cursor.toDayKeyRows(): List<DayKeyRow> = buildList {
        while (moveToNext()) {
            add(DayKeyRow(getLong(0), getString(1) ?: "", getLong(3)))
        }
    }

    // ------------------------------------------------------------------ 地区回填

    /** 需要补地区信息的历史片段（排除已放弃的 id）。对应 iOS `blocksNeedingRegionBackfill`。 */
    suspend fun blocksNeedingRegionBackfill(limit: Int): List<Triple<Long, Double, Double>> =
        withContext(singleThread) {
            val skip = settings.regionGeocodeSkip()
            helper.writableDatabase.rawQuery(
                JustDiarySchema.SELECT_BLOCKS_NEEDING_REGION.replace("?", maxOf(limit, 1).toString()),
                null,
            ).use { c ->
                buildList {
                    while (c.moveToNext()) {
                        val id = c.getLong(0)
                        if (id in skip) continue
                        add(Triple(id, c.getDouble(1), c.getDouble(2)))
                    }
                }
            }
        }

    /** 回填地区列；`WHERE region1 = ''` 保证幂等、不会覆盖已有值。 */
    suspend fun updateBlockRegions(
        id: Long,
        country: String,
        countryCode: String,
        region1: String,
        region2: String,
        region3: String,
        locQuality: String,
    ) = write { db ->
        db.execSQL(
            JustDiarySchema.UPDATE_BLOCK_REGIONS,
            arrayOf<Any?>(country, countryCode, region1, region2, region3, locQuality, id),
        )
    }

    /** 记录反地理编码失败的片段 id，避免反复重试。 */
    fun markRegionGeocodeSkipped(ids: List<Long>) = settings.markRegionGeocodeSkipped(ids)

    // ------------------------------------------------------------------ 图片文件

    /**
     * 删除 `images/` 下不再被任何片段（或受保护的草稿）引用的文件。
     * 对应 iOS `cleanupOrphanImages`；返回删掉的文件数（P2-5）。
     */
    suspend fun cleanupOrphanImages(protectedSrcs: Set<String> = emptySet()): Int =
        withContext(singleThread) {
            val db = helper.writableDatabase
            val files = imagesDir.listFiles() ?: return@withContext 0
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
            deleted
        }

    fun resolveImagePath(src: String): File =
        File(imagesDir, ImagePathUtil.normalizeSrcKey(src).removePrefix(ImagePathUtil.IMAGE_DIR_PREFIX))

    /** 新旧内容差集里的图片 src（删除动作在事务提交后做）。 */
    private fun removedImageSrcsBetween(oldJson: String, newJson: String): List<String> {
        val removed = ImagePathUtil.collectImageSrcs(oldJson).toSet() -
            ImagePathUtil.collectImageSrcs(newJson).toSet()
        return removed.toList()
    }

    private fun deleteImagesIfUnreferenced(db: SQLiteDatabase, srcs: List<String>) {
        if (srcs.isEmpty()) return
        for (src in srcs.distinct()) {
            // LIKE 的通配符要转义：图片名形如 img_<ms>.jpg，`_` 是 LIKE 的单字符通配符
            val stillReferenced = db.rawQuery(
                "SELECT 1 FROM edit_block WHERE content_json LIKE ? ESCAPE '\\' LIMIT 1;",
                arrayOf("%${SearchUtil.escapeLikeTerm(src)}%"),
            ).use { it.moveToFirst() }
            if (!stillReferenced) {
                runCatching { resolveImagePath(src).delete() }
                    .onFailure { Log.db.w("删除图片失败：$src") }
            }
        }
    }

    /** 写入 `content_json` 时统一走这里，保证图片路径与格式归一（与 iOS 的写出路径一致）。 */
    fun normalizeContent(raw: String): String = ImagePathUtil.normalizeContent(raw)

    // ------------------------------------------------------------------ 备份支持

    /**
     * 把主库快照到 [target]。
     *
     * iOS 用 SQLite Online Backup API（`sqlite3_backup`，自动包含 WAL 未 checkpoint 的数据）；
     * Android 没有公开等价物，改用 **`VACUUM INTO`**（SQLite 3.27+，minSdk 33 满足）。
     * 注意 `VACUUM INTO` 要求目标文件**不存在**，且不能在事务里执行。
     */
    suspend fun snapshotDatabaseTo(target: File) = withContext(singleThread) {
        target.parentFile?.mkdirs()
        if (target.exists() && !target.delete()) {
            throw BackupException.Database("无法删除旧的快照文件：${target.absolutePath}")
        }
        helper.writableDatabase.execSQL(JustDiarySchema.vacuumInto(target.absolutePath))
    }

    /** 导出备份用：全部片段的 `(id, content_json)`。 */
    suspend fun rawBlocksForBackup(): List<Pair<Long, String>> = withContext(singleThread) {
        helper.writableDatabase.rawQuery(JustDiarySchema.SELECT_BLOCKS_FOR_INDEX, null).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0) to (c.getString(1) ?: "[]")) }
        }
    }

    /**
     * 把备份库整天合并进主库。对应 iOS `mergeBackupDatabase`。
     *
     * 粒度是**整天**：已存在的日期要么整天跳过（[ImportMode.SKIP]）、要么整天替换
     * （[ImportMode.OVERWRITE]），**没有块级合并**。兼容老 schema：备份库缺
     * `region1` / `loc_quality` / `country_code` 时分别回落 `province/city/district`、
     * 由坐标是否全 0 推导质量、留空。
     *
     * 图片重命名与落盘由 [copyImage] 完成（它返回新的 `images/<name>` 键）。
     * Kotlin 无法像 iOS 那样用 `inout` 回填统计，因此**以返回值给出**（见方案 §4.7.4）。
     */
    suspend fun mergeBackupDatabase(
        backupDbPath: String,
        mode: ImportMode,
        copyImage: (String) -> String,
    ): BackupStats {
        val stats = writeMutex.withLock {
            withContext(singleThread) {
                val mainDb = helper.writableDatabase
                val backupDb = SQLiteDatabase.openDatabase(backupDbPath, null, SQLiteDatabase.OPEN_READONLY)
                try {
                    // P0-0：在写入任何一天之前校验备份库里的图片路径。
                    // 只校验 manifest 不够 —— 恶意 src 可能只藏在 content_json 里，
                    // 导入后会在编辑/删除时被 resolveImagePath(...).delete() 用到。
                    validateBackupImageSources(backupDb)
                    val columns = tableColumnsOf(backupDb, JustDiarySchema.TABLE_EDIT_BLOCK)
                    val hasRegion = "region1" in columns
                    val hasQuality = "loc_quality" in columns
                    val hasCountryCode = "country_code" in columns

                    var importedDays = 0
                    var skippedDays = 0
                    var overwrittenDays = 0
                    var importedBlocks = 0
                    // 被 OVERWRITE 替换掉的旧片段引用的图片：事务提交后统一清（见函数末尾）。
                    val removedImageSrcs = mutableListOf<String>()

                    // P1-15：整个库合并放进**一个事务**。此前是"逐天事务"，第 2 天失败时
                    // 第 1 天已经提交，用户看到"导入失败"但数据已经变了一半。
                    mainDb.beginTransactionNonExclusive()
                    try {
                    backupDb.rawQuery(JustDiarySchema.SELECT_BACKUP_DIARIES, null).use { diaryCursor ->
                        while (diaryCursor.moveToNext()) {
                            val dayKey = diaryCursor.stringOrNull("day_key") ?: ""
                            val existingId = mainDb.rawQuery(
                                JustDiarySchema.SELECT_DIARY_ID_BY_DAY_FOR_MERGE,
                                arrayOf(dayKey),
                            ).use { c -> if (c.moveToFirst()) c.getLong(0) else null }

                            if (mode == ImportMode.SKIP && existingId != null) {
                                skippedDays += 1
                                continue
                            }

                            val backupCreated = diaryCursor.longOrNull("created_utc")
                            val backupUpdated = diaryCursor.longOrNull("updated_utc")
                            val blocks = readBackupBlocks(
                                backupDb,
                                diaryCursor.longOrNull("id") ?: 0L,
                                hasRegion = hasRegion,
                                hasQuality = hasQuality,
                                hasCountryCode = hasCountryCode,
                            )

                                if (existingId != null) {
                                    removedImageSrcs += imageSrcsOfDiary(mainDb, existingId)
                                    mainDb.execSQL(JustDiarySchema.DELETE_BLOCKS_OF_DIARY, arrayOf<Any?>(existingId))
                                    mainDb.execSQL(JustDiarySchema.DELETE_DIARY_BY_ID, arrayOf<Any?>(existingId))
                                    mainDb.execSQL(JustDiarySchema.DELETE_DIARY_FLAG, arrayOf<Any?>(dayKey))
                                    if (ftsSupported) {
                                        mainDb.execSQL(JustDiarySchema.FTS_DELETE_BY_DIARY, arrayOf<Any?>(existingId))
                                    }
                                    overwrittenDays += 1
                                } else {
                                    importedDays += 1
                                }

                                val now = System.currentTimeMillis()
                                mainDb.execSQL(
                                    JustDiarySchema.INSERT_DIARY,
                                    arrayOf<Any?>(dayKey, backupCreated ?: now, backupUpdated ?: now),
                                )
                                val newDiaryId = lastInsertRowId(mainDb)
                                mainDb.execSQL(JustDiarySchema.INSERT_DIARY_FLAG_IF_ABSENT, arrayOf<Any?>(dayKey))

                                val dayText = StringBuilder()
                                for (block in blocks) {
                                    val normalized = ImagePathUtil.normalizeContent(block.contentJson)
                                    val rewritten = ContentFlatten.parseContent(normalized).map { part ->
                                        part.src?.let { part.copy(src = copyImage(it)) } ?: part
                                    }
                                    val newJson = ContentFlatten.serializeContent(rewritten)
                                    val text = ContentFlatten.flattenContent(newJson)
                                    val locQuality = if (hasQuality) {
                                        block.locQuality
                                    } else if (block.latitude != 0.0 || block.longitude != 0.0) {
                                        LocQuality.precise
                                    } else {
                                        LocQuality.coarse
                                    }

                                    mainDb.execSQL(
                                        JustDiarySchema.INSERT_BLOCK,
                                        arrayOf<Any?>(
                                            newDiaryId, block.startTimeUtc, block.locText,
                                            block.latitude, block.longitude, newJson, text,
                                            block.locPrecision, locQuality, block.country,
                                            block.countryCode, block.region1, block.region2, block.region3,
                                            block.createdUtc, block.updatedUtc,
                                        ),
                                    )
                                    importedBlocks += 1
                                    dayText.append(text).append(' ')
                                }

                                val trimmed = dayText.toString().trim()
                                if (trimmed.isNotEmpty()) {
                                    mainDb.execSQL(
                                        JustDiarySchema.UPDATE_DIARY_TEXTS,
                                        arrayOf<Any?>(trimmed.take(40), trimmed, newDiaryId),
                                    )
                                }
                                if (ftsSupported) {
                                    mainDb.rawQuery(
                                        JustDiarySchema.SELECT_DIARY_SUMMARY_FOR_FTS,
                                        arrayOf(newDiaryId.toString()),
                                    ).use { c ->
                                        if (c.moveToFirst()) upsertFtsEntry(mainDb, c.getLong(0), c.getString(1) ?: "")
                                    }
                                }
                        }
                    }

                    mainDb.execSQL(JustDiarySchema.CLEAR_DIARY_FLAGS)
                    mainDb.execSQL(JustDiarySchema.REBUILD_DIARY_FLAGS)
                    rebuildSearchIndex(mainDb)
                    mainDb.setTransactionSuccessful()
                    } finally {
                        mainDb.endTransaction()
                    }

                    // 事务已提交：被覆盖片段留下的旧图终于可以删了。删除放在事务**之后**，
                    // 避免"事务回滚了、文件却已经被删"（P2-6b 的同类问题）。
                    deleteImagesIfUnreferenced(mainDb, removedImageSrcs)

                    BackupStats(
                        importedDays = importedDays,
                        skippedDays = skippedDays,
                        overwrittenDays = overwrittenDays,
                        importedBlocks = importedBlocks,
                    )
                } finally {
                    backupDb.close()
                }
            }
        }
        return stats
    }

    /** 某一天全部片段的 `content_json` 里引用到的图片键（OVERWRITE 前用来清理旧图）。 */
    private fun imageSrcsOfDiary(db: SQLiteDatabase, diaryId: Long): List<String> =
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
    private fun validateBackupImageSources(backupDb: SQLiteDatabase) {
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

    private fun readBackupBlocks(
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

    private fun tableColumnsOf(db: SQLiteDatabase, table: String): Set<String> =
        db.rawQuery("PRAGMA table_info($table);", null).use { c ->
            val index = c.getColumnIndex("name")
            buildSet { while (c.moveToNext()) if (index >= 0) add(c.getString(index)) }
        }

    private fun lastInsertRowId(db: SQLiteDatabase): Long =
        db.rawQuery("SELECT last_insert_rowid();", null).use { c -> if (c.moveToFirst()) c.getLong(0) else 0L }

    // ------------------------------------------------------------------ 内部工具

    private fun diaryIdOf(db: SQLiteDatabase, dayKey: String): Long =
        db.rawQuery(JustDiarySchema.SELECT_DIARY_ID_BY_DAY, arrayOf(dayKey)).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }

    private fun insertBlock(
        db: SQLiteDatabase,
        diaryId: Long,
        startTimeUtc: Long,
        locText: String,
        latitude: Double,
        longitude: Double,
        locPrecision: String,
        region: LocRegion,
        contentJson: String,
    ): Long {
        db.execSQL(
            JustDiarySchema.INSERT_BLOCK,
            arrayOf<Any?>(
                diaryId, startTimeUtc, locText, latitude, longitude, contentJson,
                ContentFlatten.flattenContent(contentJson), locPrecision, region.locQuality,
                region.country, region.countryCode, region.region1, region.region2, region.region3,
                startTimeUtc, startTimeUtc,
            ),
        )
        return db.rawQuery("SELECT last_insert_rowid();", null).use { c ->
            if (c.moveToFirst()) c.getLong(0) else 0L
        }
    }

    /** 重算某一天的 `summary` / `search_text` 并刷新 FTS 行。对应 iOS `touchDiaryTx`。 */
    private fun touchDiary(db: SQLiteDatabase, diaryId: Long, updatedUtc: Long) {
        db.execSQL(JustDiarySchema.TOUCH_DIARY, arrayOf(diaryId, diaryId, updatedUtc, diaryId))
        if (ftsSupported) {
            db.rawQuery(JustDiarySchema.SELECT_DIARY_SUMMARY_FOR_FTS, arrayOf(diaryId.toString())).use { c ->
                if (c.moveToFirst()) upsertFtsEntry(db, c.getLong(0), c.getString(1) ?: "")
            }
        }
    }

    /**
     * 串行化写路径 + 单个事务（`BEGIN IMMEDIATE`）。
     *
     * [afterCommit] 在事务**提交后**、仍占用写锁时执行，用来做"不能回滚的副作用"
     * （例如删除图片文件）。P2-6b：文件删除不能放在事务里，否则事务回滚后
     * `content_json` 仍引用已经删掉的文件。
     */
    private suspend fun <T> write(
        afterCommit: (SQLiteDatabase, T) -> Unit = { _, _ -> },
        block: (SQLiteDatabase) -> T,
    ): T {
        val result = writeMutex.withLock {
            withContext(singleThread) {
                val db = helper.writableDatabase
                db.beginTransactionNonExclusive()
                val value = try {
                    block(db).also { db.setTransactionSuccessful() }
                } finally {
                    db.endTransaction()
                }
                afterCommit(db, value)
                value
            }
        }
        return result
    }

    private fun diaryFrom(c: Cursor): DiaryRecord = DiaryRecord(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        dayKey = c.getString(c.getColumnIndexOrThrow("day_key")) ?: "",
        summary = c.getString(c.getColumnIndexOrThrow("summary")) ?: "",
        searchText = c.getString(c.getColumnIndexOrThrow("search_text")) ?: "",
        createdUtc = c.getLong(c.getColumnIndexOrThrow("created_utc")),
        updatedUtc = c.getLong(c.getColumnIndexOrThrow("updated_utc")),
    )

    private fun blockFrom(c: Cursor): EditBlock = EditBlock(
        id = c.getLong(c.getColumnIndexOrThrow("id")),
        diaryId = c.getLong(c.getColumnIndexOrThrow("diary_id")),
        startTimeUtc = c.getLong(c.getColumnIndexOrThrow("start_time_utc")),
        locText = c.getString(c.getColumnIndexOrThrow("loc_text")) ?: "",
        latitude = c.getDouble(c.getColumnIndexOrThrow("latitude")),
        longitude = c.getDouble(c.getColumnIndexOrThrow("longitude")),
        contentJson = c.getString(c.getColumnIndexOrThrow("content_json")) ?: "[]",
        searchText = c.getString(c.getColumnIndexOrThrow("search_text")) ?: "",
        locPrecision = c.getString(c.getColumnIndexOrThrow("loc_precision")) ?: "none",
        locQuality = c.getString(c.getColumnIndexOrThrow("loc_quality")) ?: LocQuality.none,
        country = c.getString(c.getColumnIndexOrThrow("country")) ?: "",
        countryCode = c.getString(c.getColumnIndexOrThrow("country_code")) ?: "",
        region1 = c.getString(c.getColumnIndexOrThrow("region1")) ?: "",
        region2 = c.getString(c.getColumnIndexOrThrow("region2")) ?: "",
        region3 = c.getString(c.getColumnIndexOrThrow("region3")) ?: "",
        createdUtc = c.getLong(c.getColumnIndexOrThrow("created_utc")),
        updatedUtc = c.getLong(c.getColumnIndexOrThrow("updated_utc")),
    )

    companion object {
        /** 地点候选上限（iOS 取前 20）。 */
        const val LOCATION_OPTION_LIMIT: Int = 20
    }
}

/**
 * 备份库里一条片段的中间表示。
 *
 * 单列出来是因为备份库可能是**老 schema**：缺 `region1/2/3` 时要回落读
 * `province/city/district`，缺 `loc_quality` 时要由坐标推导 —— 这些回落必须在
 * 读取阶段就做完，后面的写入路径才能只面对一种形状。
 */
private data class BackupBlockRow(
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
private fun Cursor.stringOrNull(name: String): String? {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) null else getString(index)
}

private fun Cursor.longOrNull(name: String): Long? {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) null else getLong(index)
}

private fun Cursor.doubleOrNull(name: String): Double? {
    val index = getColumnIndex(name)
    return if (index < 0 || isNull(index)) null else getDouble(index)
}
