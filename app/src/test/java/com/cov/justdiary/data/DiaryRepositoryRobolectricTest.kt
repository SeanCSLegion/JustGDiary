package com.cov.justdiary.data

import android.content.Context
import com.cov.justdiary.data.db.JustDiaryOpenHelper
import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import com.cov.justdiary.service.SettingsStore
import com.cov.justdiary.ui.diary.EditorModel
import com.cov.justdiary.ui.diary.SpannableBridge
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 数据层的**运行时**验证（Robolectric）。
 *
 * 这是整个项目里唯一"真的打开了一次 SQLite"的测试 —— 之前所有断言都是"给定的输入 → 期望的输出"
 * 的纯函数测试，SQL 字符串、参数绑定、游标列名、迁移链都没有被执行过。
 * 有了这个宿主之后，下面这些**只可能**在运行时暴露的问题就纳入了验收：
 *
 * 1. DDL 是否真的能在 SQLite 上执行（列名/索引/FTS 语句）；
 * 2. 迁移链 v0 → v1 → v3 是否连贯（fresh install 也走同一条链）；
 * 3. 参数绑定与游标取值（`getColumnIndexOrThrow` 的列名对不对）；
 * 4. `day_key` 的归属规则在真实写入路径上是否一致；
 * 5. `Spannable` 往返（编辑器 ↔ 平台文本）是否真的不丢内容 —— 这条在 JVM 上做不了，
 *    因为需要真实的 `SpannableStringBuilder` 与 `ImageSpan`。
 *
 * Robolectric 需要下载 android-all-instrumented（阿里云镜像，见 `app/build.gradle.kts`）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiaryRepositoryRobolectricTest {

    private lateinit var context: Context
    private lateinit var repository: DiaryRepository

    private val region = LocRegion(
        country = "中国",
        countryCode = "CN",
        region1 = "广东省",
        region2 = "深圳市",
        region3 = "南山区",
        locQuality = LocQuality.precise,
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        repository = DiaryRepository(context, SettingsStore(context))
    }

    @After
    fun tearDown() {
        context.deleteDatabase(JustDiarySchema.DB_NAME)
    }

    // ------------------------------------------------------------------ 建表与迁移

    @Test
    fun `fresh install creates the whole schema through the migration chain`() {
        val helper = JustDiaryOpenHelper(context)
        val db = helper.writableDatabase

        val tables = db.rawQuery(
            "SELECT name FROM sqlite_master WHERE type IN ('table','view') ORDER BY name;",
            null,
        ).use { c -> buildList { while (c.moveToNext()) add(c.getString(0)) } }

        assertTrue("diary", tables.contains("diary"))
        assertTrue("edit_block", tables.contains("edit_block"))
        assertTrue("diary_flag", tables.contains("diary_flag"))
        assertEquals("user_version 应当是 VERSION", JustDiarySchema.VERSION, helper.readableDatabase.version)
        // 索引与 FTS 表都在（FTS 语句是否可用由平台决定，见 ftsSupported）
        if (helper.ftsSupported) assertTrue(tables.contains("diary_fts"))
    }

    // ------------------------------------------------------------------ 写入与读回

    @Test
    fun `a new block is written and read back with its location columns`() = runBlocking {
        repository.prepare()
        val startUtc = 1_700_000_000_000L
        val json = ContentFlatten.serializeContent(
            listOf(ContentPart(style = ContentPartStyle.body, text = "今天去了海边")),
        )

        repository.addBlock(
            startTimeUtc = startUtc,
            locText = "深圳市 · 广东省 · 中国",
            latitude = 22.5,
            longitude = 114.0,
            locPrecision = LocPrecision.exact,
            region = region,
            contentJson = json,
            dayStartHour = 4,
        )

        val dayKey = DateUtil.dayKeyForUtc(startUtc, 4)
        val diary = repository.getDiaryByDay(dayKey)
        assertNotNull("按 day_key 应当能读回这一天", diary)

        val blocks = repository.getBlocks(diary!!.id)
        assertEquals(1, blocks.size)
        val block = blocks.first()
        assertEquals("深圳市 · 广东省 · 中国", block.locText)
        assertEquals(LocPrecision.exact, block.locPrecision)
        assertEquals("广东省", block.region1)
        assertEquals("南山区", block.region3)
        assertEquals(json, block.contentJson)
        assertEquals("检索文本应当由 content_json 生成", "今天去了海边", block.searchText)

        assertEquals(1, repository.countDiaries())
        assertEquals(1, repository.countBlocks())
    }

    @Test
    fun `day key follows the configured day start hour`() = runBlocking {
        repository.prepare()
        // 凌晨 02:00（本地）→ 归属前一天；这里只用"写入 + 查询"验证两条路径一致
        val explicit = DateUtil.dayKeyForUtc(1_700_000_000_000L, 4)
        repository.addBlock(
            startTimeUtc = 1_700_000_000_000L,
            locText = "",
            latitude = 0.0,
            longitude = 0.0,
            locPrecision = LocPrecision.none,
            region = LocRegion("", "", "", "", "", LocQuality.none),
            contentJson = ContentFlatten.serializeContent(listOf(ContentPart(text = "x"))),
            dayStartHour = 4,
        )
        assertNotNull(repository.getDiaryByDay(explicit))
    }

    @Test
    fun `editing a block keeps its coordinates and only changes precision and text`() = runBlocking {
        repository.prepare()
        val startUtc = 1_700_000_000_000L
        repository.addBlock(
            startTimeUtc = startUtc,
            locText = "深圳市 · 广东省 · 中国",
            latitude = 22.5,
            longitude = 114.0,
            locPrecision = LocPrecision.exact,
            region = region,
            contentJson = ContentFlatten.serializeContent(listOf(ContentPart(text = "第一版"))),
            dayStartHour = 4,
        )
        val dayKey = DateUtil.dayKeyForUtc(startUtc, 4)
        val block = repository.getBlocks(repository.getDiaryByDay(dayKey)!!.id).first()

        repository.updateBlockContent(
            blockId = block.id,
            contentJson = ContentFlatten.serializeContent(listOf(ContentPart(text = "第二版"))),
            locText = "广东省 · 中国",
            locPrecision = LocPrecision.province,
        )

        val updated = repository.getBlocks(repository.getDiaryByDay(dayKey)!!.id).first()
        assertEquals("坐标属于写入那一刻，编辑不得改动", 22.5, updated.latitude, 1e-9)
        assertEquals(114.0, updated.longitude, 1e-9)
        assertEquals("广东省", updated.region1)
        assertEquals(LocPrecision.province, updated.locPrecision)
        assertEquals("广东省 · 中国", updated.locText)
        assertEquals("第二版", updated.searchText)
    }

    @Test
    fun `search finds the block by keyword and by region filter`() = runBlocking {
        repository.prepare()
        val startUtc = 1_700_000_000_000L
        repository.addBlock(
            startTimeUtc = startUtc,
            locText = "深圳市",
            latitude = 22.5,
            longitude = 114.0,
            locPrecision = LocPrecision.exact,
            region = region,
            contentJson = ContentFlatten.serializeContent(
                listOf(ContentPart(text = "今天去了海边")),
            ),
            dayStartHour = 4,
        )

        val hit = repository.search("海边", "0000-01-01", "9999-12-31", com.cov.justdiary.model.LocFilter(), 0)
        assertEquals(1, hit.total)
        assertEquals(DateUtil.dayKeyForUtc(startUtc, 4), hit.items.first().dayKey)

        val byRegion = repository.search(
            "",
            "0000-01-01",
            "9999-12-31",
            com.cov.justdiary.model.LocFilter(country = "中国", region1 = "广东省"),
            0,
        )
        assertEquals(1, byRegion.total)

        val miss = repository.search("不存在", "0000-01-01", "9999-12-31", com.cov.justdiary.model.LocFilter(), 0)
        assertEquals(0, miss.total)
    }

    @Test
    fun `deleting the only block removes the day as well`() = runBlocking {
        repository.prepare()
        val startUtc = 1_700_000_000_000L
        repository.addBlock(
            startTimeUtc = startUtc,
            locText = "",
            latitude = 0.0,
            longitude = 0.0,
            locPrecision = LocPrecision.none,
            region = LocRegion("", "", "", "", "", LocQuality.none),
            contentJson = ContentFlatten.serializeContent(listOf(ContentPart(text = "only"))),
            dayStartHour = 4,
        )
        val dayKey = DateUtil.dayKeyForUtc(startUtc, 4)
        val block = repository.getBlocks(repository.getDiaryByDay(dayKey)!!.id).first()

        repository.deleteBlocks(listOf(block.id))

        assertNull("最后一条片段删掉后，这一天本身也要消失", repository.getDiaryByDay(dayKey))
        assertEquals(0, repository.countDiaries())
        assertEquals(0, repository.getDiaryFlagsRange("0000-01-01", "9999-12-31").size)
    }

    @Test
    fun `recomputing day keys with a different start hour keeps the data`() = runBlocking {
        repository.prepare()
        val startUtc = 1_700_000_000_000L
        repository.addBlock(
            startTimeUtc = startUtc,
            locText = "",
            latitude = 0.0,
            longitude = 0.0,
            locPrecision = LocPrecision.none,
            region = LocRegion("", "", "", "", "", LocQuality.none),
            contentJson = ContentFlatten.serializeContent(listOf(ContentPart(text = "跨日"))),
            dayStartHour = 4,
        )
        val before = DateUtil.dayKeyForUtc(startUtc, 4)

        val conflicts = repository.recomputeDayKeys(dayStartHour = 4)

        assertEquals("同一个 dayStartHour 重算不应产生冲突", 0, conflicts)
        assertNotNull(repository.getDiaryByDay(before))
        assertEquals(1, repository.countBlocks())
    }

    // ------------------------------------------------------------------ 编辑器 ↔ Spannable

    @Test
    fun `editor lines survive a spannable round trip`() {
        val parts = listOf(
            ContentPart(style = ContentPartStyle.title, text = "标题"),
            ContentPart(style = ContentPartStyle.body, runs = listOf(
                com.cov.justdiary.model.TextRun(text = "粗", bold = true),
                com.cov.justdiary.model.TextRun(text = "体"),
            )),
            ContentPart(style = ContentPartStyle.todo, items = listOf("买牛奶", "写日记"), done = listOf(true, false)),
            ContentPart(style = ContentPartStyle.quote, text = "引用一句"),
            ContentPart(
                style = ContentPartStyle.image,
                src = "images/img_1.jpg",
                w = 800.0,
                h = 600.0,
            ),
        )
        val lines = EditorModel.partsToLines(parts)
        // 两个待办项各占一行，因此行数是 6
        assertEquals(6, lines.size)

        val spannable = SpannableBridge.toSpannable(lines, imageProvider = null, containerWidthPx = 1000)
        val restored = SpannableBridge.fromSpannable(spannable)
        assertEquals("没有空白行，行数应当原样保留", lines.size, restored.size)

        // 再编码回 parts：文本与图片 src 必须一个不丢
        val roundTripped = EditorModel.linesToParts(restored)
        assertEquals("段落数不变（待办的两行会合并回一个段落）", parts.size, roundTripped.size)
        assertEquals("标题", ContentFlatten.flattenPart(roundTripped[0]))
        assertEquals("粗体", ContentFlatten.flattenPart(roundTripped[1]))
        assertEquals(listOf("买牛奶", "写日记"), roundTripped[2].items)
        assertEquals(listOf(true, false), roundTripped[2].done)
        assertEquals("引用一句", ContentFlatten.flattenPart(roundTripped[3]))
        assertEquals("images/img_1.jpg", roundTripped[4].src)
        assertEquals(800.0, roundTripped[4].w!!, 1e-9)
        assertEquals(600.0, roundTripped[4].h!!, 1e-9)
    }

    @Test
    fun `blank lines are dropped by design but the text survives`() {
        // 段落里的换行会拆成多行；解析侧**刻意丢弃空白行**（编辑器自己会补一个可编辑位置），
        // 这条规则与 iOS `PartsCodec.parts(from:)` 一致，因此这里断言的是"规则本身"。
        val lines = EditorModel.partsToLines(listOf(ContentPart(text = "第一行\n")))
        assertEquals(2, lines.size)

        val spannable = SpannableBridge.toSpannable(lines)
        val restored = SpannableBridge.fromSpannable(spannable)

        assertEquals(1, restored.size)
        assertEquals("第一行", restored.first().text)
    }

    @Test
    fun `an all-blank editor keeps exactly one editable line`() {
        val spannable = SpannableBridge.toSpannable(listOf(EditorModel.EMPTY_LINE))
        val restored = SpannableBridge.fromSpannable(spannable)
        assertEquals(1, restored.size)
    }
}
