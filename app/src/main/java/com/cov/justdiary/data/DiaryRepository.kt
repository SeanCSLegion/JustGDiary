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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
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

    private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 8)

    /** 日记数据发生变化（对应 iOS 的 `.diaryVersionChanged` 通知）。 */
    val changes: SharedFlow<Unit> = _changes

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
        emitChange()
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
    ) = write { db ->
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

        cleanupImagesRemovedBetween(db, oldJson, newJson)
    }

    /**
     * 批量删除片段；某天删到没有片段时，连 `diary` / `diary_flag` / FTS 行一起删。
     * 对应 iOS `deleteBlocks`（图片清理改成"仍被引用则不删"，见类注释）。
     */
    suspend fun deleteBlocks(ids: List<Long>) {
        if (ids.isEmpty()) return
        write { db ->
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

            deleteImagesIfUnreferenced(db, removedJsons.flatMap(ImagePathUtil::collectImageSrcs))
        }
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
     * 改了"新一天的开始时间"之后重算全部 `day_key`，返回无法迁移的冲突天数。
     *
     * 用 `tmp#<id>` 两阶段写绕开 `day_key UNIQUE`；目标键被另一条**也在移动**的记录占用时
     * 计为冲突并跳过（与 iOS `recomputeDayKeys` 一致，返回值供设置页提示）。
     */
    suspend fun recomputeDayKeys(dayStartHour: Int): Int = write { db ->
        data class Row(val id: Long, val oldKey: String, val firstUtc: Long)

        val rows = db.rawQuery(JustDiarySchema.DIARY_DAY_KEYS_WITH_FIRST_BLOCK, null).use { c ->
            buildList {
                while (c.moveToNext()) {
                    add(Row(c.getLong(0), c.getString(1) ?: "", c.getLong(3)))
                }
            }
        }

        val newKeys = HashMap<Long, String>(rows.size)
        val holderOf = HashMap<String, Long>(rows.size)
        for (row in rows) {
            val key = if (row.firstUtc > 0) {
                DateUtil.dayKeyForUtc(row.firstUtc, dayStartHour)
            } else {
                row.oldKey
            }
            newKeys[row.id] = key
            holderOf[key] = row.id
        }

        var conflicts = 0
        val movers = mutableListOf<Long>()
        val finalKeys = HashMap<Long, String>()
        for (row in rows) {
            val newKey = newKeys[row.id] ?: row.oldKey
            if (newKey == row.oldKey) continue
            val currentHolder = holderOf[row.oldKey]
            if (currentHolder != null && currentHolder != row.id) {
                if (newKeys[currentHolder] != row.oldKey) {
                    conflicts += 1
                    continue
                }
            }
            movers.add(row.id)
            finalKeys[row.id] = newKey
        }

        for (id in movers) {
            db.execSQL(JustDiarySchema.UPDATE_DIARY_DAY_KEY, arrayOf<Any?>(JustDiarySchema.temporaryDayKey(id), id))
        }
        for ((id, key) in finalKeys) {
            db.execSQL(JustDiarySchema.UPDATE_DIARY_DAY_KEY, arrayOf<Any?>(key, id))
        }
        db.execSQL(JustDiarySchema.CLEAR_DIARY_FLAGS)
        db.execSQL(JustDiarySchema.REBUILD_DIARY_FLAGS)
        conflicts
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

    /** 删除 `images/` 下不再被任何片段引用的文件。对应 iOS `cleanupOrphanImages`。 */
    suspend fun cleanupOrphanImages() {
        withContext(singleThread) {
            val db = helper.writableDatabase
            val files = imagesDir.listFiles() ?: return@withContext
            val referenced = db.rawQuery(JustDiarySchema.SELECT_ALL_BLOCKS, null).use { c ->
                buildSet {
                    while (c.moveToNext()) {
                        val json = c.getString(c.getColumnIndexOrThrow("content_json")) ?: "[]"
                        addAll(ImagePathUtil.collectImageSrcs(json))
                    }
                }
            }
            for (file in files) {
                val src = ImagePathUtil.IMAGE_DIR_PREFIX + file.name
                if (src !in referenced) {
                    runCatching { file.delete() }
                        .onFailure { Log.db.w("删除孤立图片失败：${file.name}") }
                }
            }
        }
    }

    fun resolveImagePath(src: String): File =
        File(imagesDir, ImagePathUtil.normalizeSrcKey(src).removePrefix(ImagePathUtil.IMAGE_DIR_PREFIX))

    /** 只删"新旧内容差集里、且已不再被任何片段引用"的图片。 */
    private fun cleanupImagesRemovedBetween(db: SQLiteDatabase, oldJson: String, newJson: String) {
        val removed = ImagePathUtil.collectImageSrcs(oldJson).toSet() -
            ImagePathUtil.collectImageSrcs(newJson).toSet()
        deleteImagesIfUnreferenced(db, removed.toList())
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

    /** 串行化写路径 + 单个事务（`BEGIN IMMEDIATE`）。 */
    private suspend fun <T> write(block: (SQLiteDatabase) -> T): T {
        val result = writeMutex.withLock {
            withContext(singleThread) {
                val db = helper.writableDatabase
                db.beginTransactionNonExclusive()
                try {
                    val value = block(db)
                    db.setTransactionSuccessful()
                    value
                } finally {
                    db.endTransaction()
                }
            }
        }
        emitChange()
        return result
    }

    private fun emitChange() {
        _changes.tryEmit(Unit)
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
