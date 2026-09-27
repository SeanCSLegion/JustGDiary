package com.cov.justdiary.ui.diary

import android.content.Context
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.data.DiaryRepository
import com.cov.justdiary.data.DiaryTopBarState
import com.cov.justdiary.data.db.JustDiarySchema
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.model.LocQuality
import com.cov.justdiary.model.LocRegion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 日记页**状态迁移**的运行时验证（Robolectric + 真 SQLite）。
 *
 * 这些用例是补上"三条断链"的根因：阅读页 → 编辑 / 多选 / 删除这条链此前
 * 六个回调一个都没接线（界面点了没反应），而**没有任何测试覆盖过这些迁移**
 * （见 `docs/android-design/08-diary-implementation-plan.md` §5 的最后一行的建议）。
 *
 * 覆盖的不变量（同文档 §4）：
 * - 点片段 → 进编辑，且载入的是**该片段**的内容；
 * - 长按 → 多选并选中该条；删除 → 回阅读态且选中清空；
 * - **编辑态收到 `refresh()` 不改变 `isRead`**（iOS 的坑：写库通知会把编辑踢掉）；
 * - 搜索命中数与阅读页高亮来自**同一个函数**（规范 §2.7 的"一套算法"）；
 * - 非当天不显示写入入口（`canEditToday` → 顶栏三态）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DiaryPageStateTest {

    private lateinit var context: Context
    private lateinit var container: AppContainer
    private lateinit var viewModel: DiaryPageViewModel
    private val scheduler = TestCoroutineScheduler()

    private val region = LocRegion(
        country = "中国",
        countryCode = "CN",
        region1 = "上海市",
        region2 = "上海市",
        region3 = "浦东新区",
        locQuality = LocQuality.precise,
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
        container = AppContainer(context)
        viewModel = DiaryPageViewModel(container)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        context.deleteDatabase(JustDiarySchema.DB_NAME)
    }

    // ------------------------------------------------------------------ 夹具

    private fun todayKey(): String {
        val settings = container.settings.load()
        return DateUtil.dayKeyForUtc(System.currentTimeMillis(), settings.dayStartHour)
    }

    /** 今天写两条片段，返回它们的 id。 */
    private fun seedToday(): List<Long> = runBlocking {
        val now = System.currentTimeMillis()
        listOf(
            container.repository.addBlock(
                startTimeUtc = now - 3_600_000,
                locText = "浦东新区 · 上海市 · 中国",
                latitude = 31.2,
                longitude = 121.5,
                locPrecision = LocPrecision.district,
                region = region,
                contentJson = ContentFlatten.serializeContent(
                    listOf(ContentPart(style = ContentPartStyle.body, text = "第一条：深圳的天气")),
                ),
                dayStartHour = 4,
            ),
            container.repository.addBlockToDiary(
                diaryId = container.repository.getDiaryByDay(todayKey())!!.id,
                startTimeUtc = now - 60_000,
                locText = "",
                latitude = 0.0,
                longitude = 0.0,
                locPrecision = LocPrecision.none,
                region = LocRegion("", "", "", "", "", LocQuality.none),
                contentJson = ContentFlatten.serializeContent(
                    listOf(
                        ContentPart(style = ContentPartStyle.heading, text = "小标题"),
                        ContentPart(style = ContentPartStyle.todo, items = listOf("去深圳", "在家"), done = listOf(true, false)),
                    ),
                ),
            ),
        )
    }

    private fun awaitState(
        predicate: (DiaryPageViewModel.State) -> Boolean,
    ): DiaryPageViewModel.State = runBlocking {
        withTimeout(10_000) { viewModel.state.first(predicate) }
    }

    private fun openToday() {
        viewModel.open(todayKey())
        awaitState { it.blocks.size == 2 }
    }

    // ------------------------------------------------------------------ 三条断链

    @Test
    fun `tapping a block enters editing with that block's content`() {
        seedToday()
        openToday()

        viewModel.enterEditBlock(1)
        val state = viewModel.state.value

        assertFalse("点片段必须离开阅读态", state.isRead)
        assertEquals(1, state.editingIndex)
        assertTrue("载入的必须是第 2 条的内容", state.loadParts.any { it.style == ContentPartStyle.heading })
        assertEquals("新片段才有「重新获取位置」，已有片段没有", false, state.canRelocate)

        // 编辑态顶栏：插图 / 放弃 / 保存（这里只断言形态，动作由 EditorScreen 承载）
        val bar = DiaryTopBarState.of(
            isRead = state.isRead,
            editingIndex = state.editingIndex,
            selectMode = state.selectMode,
            selectedIds = state.selectedIds,
            canEditToday = state.canEditToday,
            showSearch = state.showSearch,
        )
        assertEquals(DiaryTopBarState.Mode.Edit, bar.mode)
        assertTrue(bar.canSave)
    }

    @Test
    fun `long press enters select mode and selects that block`() {
        seedToday()
        openToday()
        val ids = viewModel.state.value.blocks.map { it.id }

        viewModel.enterSelect(ids[1])

        val state = viewModel.state.value
        assertTrue("多选是阅读态里的第三个维度，isRead 仍为真", state.isRead)
        assertTrue(state.selectMode)
        assertEquals(setOf(ids[1]), state.selectedIds)

        val bar = DiaryTopBarState.of(
            isRead = state.isRead,
            editingIndex = state.editingIndex,
            selectMode = state.selectMode,
            selectedIds = state.selectedIds,
            canEditToday = state.canEditToday,
            showSearch = state.showSearch,
        )
        assertEquals(DiaryTopBarState.Mode.Select, bar.mode)
        assertEquals(1, bar.selectTitleCount)
        assertTrue(bar.canDeleteSelected)
    }

    @Test
    fun `deleting the selected blocks returns to reading with an empty selection`() {
        seedToday()
        openToday()
        val ids = viewModel.state.value.blocks.map { it.id }

        viewModel.enterSelect(ids[0])
        viewModel.confirmDeleteSelected()
        assertEquals(1, (viewModel.state.value.alert as DiaryPageViewModel.DiaryAlert.DeleteConfirm).count)
        viewModel.performDeleteSelected()

        val state = awaitState { it.blocks.size == 1 }
        assertTrue("删完停在阅读态（不自动关闭路由）", state.isRead)
        assertFalse(state.selectMode)
        assertTrue(state.selectedIds.isEmpty())
        assertEquals(listOf(ids[1]), state.blocks.map { it.id })
    }

    @Test
    fun `deleting every block keeps the page open on the empty state`() {
        seedToday()
        openToday()
        val ids = viewModel.state.value.blocks.map { it.id }

        ids.forEach { viewModel.enterSelect(it) }
        viewModel.performDeleteSelected()

        val state = awaitState { it.blocks.isEmpty() }
        assertTrue(state.isRead)
        assertFalse("删空当天不等于关闭路由", state.requestDismiss)
        assertEquals(0, state.selectedIds.size)
    }

    @Test
    fun `back from an empty editor returns to reading instead of closing the page`() {
        seedToday()
        openToday()

        viewModel.enterEditBlock(0)
        viewModel.handleBack(editorHasContent = false)

        val state = viewModel.state.value
        assertTrue("回到本页阅读态", state.isRead)
        assertNull(state.editingIndex)
        assertFalse("**不关页**：这一天还在，用户能看到刚读的内容", state.requestDismiss)
    }

    @Test
    fun `back from a dirty editor asks first and then returns to reading`() {
        seedToday()
        openToday()

        viewModel.enterEditBlock(0)
        viewModel.handleBack(editorHasContent = true)
        assertEquals(DiaryPageViewModel.DiaryAlert.ExitConfirm, viewModel.state.value.alert)
        assertFalse(viewModel.state.value.requestDismiss)

        viewModel.showRead()
        assertTrue(viewModel.state.value.isRead)
        assertNull(viewModel.state.value.alert)
        assertFalse(viewModel.state.value.requestDismiss)
    }

    @Test
    fun `refresh while editing must not drop the editor`() {
        seedToday()
        openToday()

        viewModel.enterEditBlock(0)
        // 模拟"别处写了库"：刷新必须把新数据读进来（块数 2 → 3），但不能动编辑态。
        // 用块数当同步点，避免"刷新还没落地就断言"的竞态。
        runBlocking {
            container.repository.addBlockToDiary(
                diaryId = container.repository.getDiaryByDay(todayKey())!!.id,
                startTimeUtc = System.currentTimeMillis(),
                locText = "",
                latitude = 0.0,
                longitude = 0.0,
                locPrecision = LocPrecision.none,
                region = LocRegion("", "", "", "", "", LocQuality.none),
                contentJson = ContentFlatten.serializeContent(
                    listOf(ContentPart(style = ContentPartStyle.body, text = "别处写的第三条")),
                ),
            )
        }
        viewModel.refresh()

        val state = awaitState { it.blocks.size == 3 }
        assertFalse("写库通知不得把编辑态复位成阅读态（iOS 的坑）", state.isRead)
        assertEquals(0, state.editingIndex)
        assertTrue("编辑态载入的内容不能被重载冲掉", state.loadParts.any { it.text == "第一条：深圳的天气" })
    }

    @Test
    fun `back exits select mode first and only then closes the page`() {
        seedToday()
        openToday()
        val ids = viewModel.state.value.blocks.map { it.id }

        viewModel.enterSelect(ids[0])
        viewModel.handleBack(editorHasContent = false)
        assertFalse(viewModel.state.value.selectMode)
        assertFalse("第一次返回只退多选", viewModel.state.value.requestDismiss)

        viewModel.handleBack(editorHasContent = false)
        assertTrue("再按一次才关页", viewModel.state.value.requestDismiss)
    }

    // ------------------------------------------------------------------ 搜索一套算法

    @Test
    fun `search hits come from the same function that draws the highlights`() {
        seedToday()
        openToday()

        viewModel.setSearchVisible(true)
        viewModel.onSearchTextChange("深圳")
        // 150ms 防抖：推进虚拟时间让命中算出来
        scheduler.advanceTimeBy(DiaryPageViewModel.SEARCH_DEBOUNCE_MS + 50)
        scheduler.runCurrent()

        val state = viewModel.state.value
        // 夹具里 "深圳" 出现两次：正文一次、待办项一次
        assertEquals(2, state.hits.size)

        // 逐条与 DiarySearch 的结果对照（同一个函数 → 同样的 partIndex / itemIndex / 区间）
        val expected = state.blocks.flatMap { block ->
            val parts = ContentFlatten.parseContent(block.contentJson)
            DiarySearch.findMatches(parts, "深圳").map { block.id to it }
        }
        assertEquals(expected.size, state.hits.size)
        expected.forEachIndexed { index, (blockId, match) ->
            val hit = state.hits[index]
            assertEquals(blockId, hit.blockId)
            assertEquals(match.partIndex, hit.partIndex)
            assertEquals(match.itemIndex, hit.itemIndex)
            assertEquals(match.start, hit.start)
            assertEquals(match.end, hit.end)
        }
    }

    @Test
    fun `stepping through hits targets the block that owns them`() {
        seedToday()
        openToday()
        viewModel.setSearchVisible(true)
        viewModel.onSearchTextChange("深圳")
        scheduler.advanceTimeBy(DiaryPageViewModel.SEARCH_DEBOUNCE_MS + 50)
        scheduler.runCurrent()

        viewModel.stepHit(1)
        val target = viewModel.state.value.scrollTargetBlockId
        assertEquals(viewModel.state.value.hits[viewModel.state.value.hitIndex].blockId, target)
        viewModel.consumeScrollTarget()
        assertEquals(null, viewModel.state.value.scrollTargetBlockId)
    }

    // ------------------------------------------------------------------ 顶栏三态

    @Test
    fun `a past day offers no write entry and refuses new blocks`() {
        viewModel.open("2020-01-01")
        awaitState { it.actualDayKey == "2020-01-01" }

        val state = viewModel.state.value
        assertFalse(state.canEditToday)
        val bar = DiaryTopBarState.of(
            isRead = state.isRead,
            editingIndex = state.editingIndex,
            selectMode = state.selectMode,
            selectedIds = state.selectedIds,
            canEditToday = state.canEditToday,
            showSearch = state.showSearch,
        )
        assertFalse("历史日期顶栏没有「写日记」", bar.showWrite)
        assertTrue(bar.showSearch && bar.showShare)

        // 即便被调用（例如深链），也只给一条提示、**不进编辑态**
        viewModel.enterWrite()
        assertTrue("历史日期的新增入口即便被调用也不该进编辑态", viewModel.state.value.isRead)
        assertEquals(DiaryPageViewModel.DiaryAlert.HistoryNoAdd, viewModel.state.value.alert)
    }

    @Test
    fun `an empty day keeps the hero card and the write entry`() {
        viewModel.open(todayKey())
        awaitState { it.actualDayKey == todayKey() }
        val state = viewModel.state.value

        assertTrue(state.blocks.isEmpty())
        assertTrue(state.isRead)
        assertTrue("空的一天也要能写", state.canEditToday)
        assertEquals(null, state.alert)
    }

    // ------------------------------------------------------------------ 待办

    @Test
    fun `toggling a todo writes through and keeps the page in reading mode`() {
        seedToday()
        openToday()
        val block = viewModel.state.value.blocks[1]

        viewModel.toggleTodo(block.id, partIndex = 1, itemIndex = 1)

        val state = awaitState { current ->
            val parts = ContentFlatten.parseContent(current.blocks[1].contentJson)
            parts.getOrNull(1)?.done == listOf(true, true)
        }
        assertTrue(state.isRead)
        assertNull("勾待办不进入编辑态", state.editingIndex)
    }
}
