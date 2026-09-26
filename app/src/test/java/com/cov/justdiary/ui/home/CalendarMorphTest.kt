package com.cov.justdiary.ui.home

import com.cov.justdiary.model.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * morph 插值与翻页数学的测试。
 *
 * 期望值来自 `MorphViews.swift` 与 `DragPagePager.swift` 的公式
 * （见 `docs/migration-analysis/03-home-calendar-and-motion.md` §1.6、§3.2）。
 *
 * 这一层的价值在于：morph 的"收尾不跳变"依赖**端点值精确等于真实图层**，
 * 而端点值只能靠这种断言守住 —— 本机没有设备可以肉眼看动画。
 */
class CalendarMorphTest {

    private val portraitW = 402f

    /** Pixel 10 Pro AVD 的首页内容区可用高度（见 `CalendarLayoutTest`）。 */
    private val portraitH = 796f
    /** 月度整屏区高：可用高 − 顶栏 52（与 `HomeScreen` 一致）。 */
    private val monthAreaH = CalendarLayout.monthAreaHeight(portraitH - 52f, rows = 6)
    /** 月层**实际**网格高 = 行数 × 行高（行高 = (区高 − 大标题槽 − 星期栏) / 6）。 */
    private val cellH = CalendarLayout.cellHeightFor(monthAreaH)
    private val gridH = CalendarLayout.monthGridHeight(6, cellH)
    /** 年历层高度 = 顶栏之外的全部可用高（月 / 年都是整屏视图）。 */
    private val yearHeight = portraitH - 52f

    // 关键：`fullMonthGridRect` 接收的是**日历区**尺寸，header = 大标题槽 + 星期栏
    // （规范 §5.1：竖屏月态有 72dp 的 32sp 大标题）。
    // 只有和真实图层用同一份「区高 + header」，morph 收尾才不会跳变。
    private val headerH = CalendarLayout.BIG_TITLE_H + CalendarLayout.WEEKDAY_HEADER_H
    private val fullGrid = CalendarLayout.fullMonthGridRect(
        portraitW,
        headerH + gridH,
        headerH = headerH,
    )
    private val miniGrid = CalendarLayout.miniGridRect(
        CalendarLayout.yearCardRect(1, portraitW, yearHeight),
    )

    // ------------------------------------------------------------------ 年 ↔ 月

    @Test
    fun `year month morph starts exactly at the month grid`() {
        val state = CalendarLayout.yearMonthMorph(0f, miniGrid, fullGrid, showLunar = true)

        assertEquals(1f, state.t, 1e-6f)
        assertEquals(fullGrid.x, state.grid.x, 1e-3f)
        assertEquals(fullGrid.y, state.grid.y, 1e-3f)
        assertEquals(fullGrid.w, state.grid.w, 1e-3f)
        assertEquals(fullGrid.h, state.grid.h, 1e-3f)
        // 落点必须与真实图层同源：cellH / dayFont 与 monthMetrics 完全一致
        val real = CalendarLayout.monthMetrics(portraitW, monthAreaH, showLunar = true)
        assertEquals(real.cellH, state.metrics.cellH, 1e-3f)
        assertEquals(real.dayFont, state.metrics.dayFont, 1e-3f)
        assertEquals(1f, state.lunarAlpha, 1e-6f)
        assertEquals(1f, state.dividerAlpha, 1e-6f)
        // 年历层完全隐藏
        assertEquals(0f, state.yearOpacity, 1e-6f)
        assertEquals(0f, state.sourceMiniTitleOpacity, 1e-6f)
    }

    @Test
    fun `year month morph ends exactly at the mini month grid`() {
        val state = CalendarLayout.yearMonthMorph(1f, miniGrid, fullGrid, showLunar = true)

        assertEquals(0f, state.t, 1e-6f)
        assertEquals(miniGrid.x, state.grid.x, 1e-3f)
        assertEquals(miniGrid.y, state.grid.y, 1e-3f)
        assertEquals(miniGrid.w, state.grid.w, 1e-3f)
        assertEquals(miniGrid.h, state.grid.h, 1e-3f)
        assertEquals(0f, state.lunarAlpha, 1e-6f)
        assertEquals(0f, state.dividerAlpha, 1e-6f)
        assertEquals(1f, state.yearOpacity, 1e-6f)
        assertEquals(1f, state.yearScale, 1e-6f)
        assertEquals(1f, state.sourceMiniTitleOpacity, 1e-6f)
    }

    /**
     * 翻页的收尾时长：提交比放弃略慢（回弹要"轻"），两者都在 `medium` 量级。
     * 这两个值决定"松手后补完动画"的观感，被改小会让翻页显得很硬。
     */
    @Test
    fun `page settle timings stay in the medium range`() {
        assertTrue(CalendarLayout.DragPagerMath.PAGE_SETTLE_MS in 150..300)
        assertTrue(CalendarLayout.DragPagerMath.PAGE_CANCEL_MS in 150..300)
        assertTrue(
            "放弃要比提交快，回弹才显得轻",
            CalendarLayout.DragPagerMath.PAGE_CANCEL_MS <= CalendarLayout.DragPagerMath.PAGE_SETTLE_MS,
        )
    }

    @Test
    fun `year layer opacity and scale ramp up with progress`() {
        // 两层交叉淡入淡出的前提：任一帧都至少有一层可见
        for (step in 0..20) {
            val p = step / 20f
            val state = CalendarLayout.yearMonthMorph(p, miniGrid, fullGrid, showLunar = true)
            val monthAlpha = 1f - p
            assertTrue(
                "p=$p 时两层都不可见（月 $monthAlpha / 年 ${state.yearOpacity}）",
                monthAlpha > 0f || state.yearOpacity > 0f,
            )
        }
        val middle = CalendarLayout.yearMonthMorph(0.5f, miniGrid, fullGrid, showLunar = true)
        assertEquals(0.5f, middle.t, 1e-6f)
        assertEquals(1f + 0.5f * 0.6f, middle.yearScale, 1e-6f)
        // 年层从 p=0.25 就淡入（不是 0.45）：月层的淡出是 1-p，
        // 两条曲线必须在整段区间里至少有一层可见（否则 morph 前段整屏空白）
        assertEquals((0.5f - 0.25f) / 0.75f, middle.yearOpacity, 1e-4f)
        // 农历与分隔线的淡入都晚于中点
        assertEquals(0f, middle.lunarAlpha, 1e-6f)
        assertEquals(0f, middle.dividerAlpha, 1e-6f)

        var previousOpacity = -1f
        var previousScale = Float.MAX_VALUE
        var previousWidth = Float.MAX_VALUE
        for (step in 0..20) {
            val p = step / 20f
            val state = CalendarLayout.yearMonthMorph(p, miniGrid, fullGrid, showLunar = true)
            assertTrue("年历层透明度应单调不减", state.yearOpacity >= previousOpacity - 1e-6f)
            assertTrue("年历缩放应单调不增", state.yearScale <= previousScale + 1e-6f)
            assertTrue("网格宽度应单调不增（月格 402 → 迷你月宽约 107）", state.grid.w <= previousWidth + 1e-3f)
            previousOpacity = state.yearOpacity
            previousScale = state.yearScale
            previousWidth = state.grid.w
        }
    }

    @Test
    fun `year month morph forces seven by six cells at every progress`() {
        for (step in 0..10) {
            val state = CalendarLayout.yearMonthMorph(step / 10f, miniGrid, fullGrid, showLunar = true)
            assertEquals("列宽必须等于网格宽 / 7", state.grid.w / 7f, state.metrics.cellW, 1e-3f)
            assertEquals("行高必须等于网格高 / 6", state.grid.h / 6f, state.metrics.cellH, 1e-3f)
        }
    }

    @Test
    fun `lunar alpha is disabled when the language hides it`() {
        val state = CalendarLayout.yearMonthMorph(0f, miniGrid, fullGrid, showLunar = false)
        assertEquals(0f, state.lunarAlpha, 1e-6f)
        assertEquals(0f, state.metrics.lunarLineH, 1e-6f)
    }

    // ------------------------------------------------------------------ 月 ↔ 周

    @Test
    fun `month week morph starts at the month layout`() {
        val metrics = CalendarLayout.monthMetrics(
            portraitW, monthAreaH, showLunar = true, headerH = CalendarLayout.WEEKDAY_HEADER_H,
        )
        val week = CalendarLayout.weekMetrics(portraitW, showLunar = false)
        val baseRowY = metrics.cellH * 2
        val monthHeight = metrics.cellH * CalendarLayout.MONTH_ROWS

        val state = CalendarLayout.monthWeekMorph(0f, baseRowY, monthHeight, metrics, week)

        assertEquals(baseRowY, state.selectedRowY, 1e-3f)
        assertEquals(metrics.cellH, state.metrics.cellH, 1e-3f)
        assertEquals(metrics.dayFont, state.metrics.dayFont, 1e-3f)
        assertEquals(1f, state.rowAboveAlpha, 1e-6f)
        assertEquals(baseRowY, state.rowAboveY, 1e-3f)
        assertEquals(0f, state.titleOffsetY, 1e-6f)
        assertEquals(1f, state.titleOpacity, 1e-6f)
        assertEquals(0f, state.contentT, 1e-6f)
        assertEquals(0f, state.contentAlpha, 1e-6f)
        // 内容区起点 = stripBottom 98 + (40 + 20)
        assertEquals(158f, state.contentOffsetY, 1e-3f)
    }

    @Test
    fun `month week morph ends at the week strip layout`() {
        val metrics = CalendarLayout.monthMetrics(
            portraitW, monthAreaH, showLunar = true, headerH = CalendarLayout.WEEKDAY_HEADER_H,
        )
        val week = CalendarLayout.weekMetrics(portraitW, showLunar = false)
        val baseRowY = metrics.cellH * 2
        val monthHeight = metrics.cellH * CalendarLayout.MONTH_ROWS

        val state = CalendarLayout.monthWeekMorph(1f, baseRowY, monthHeight, metrics, week)

        // 选中行上移到星期栏高度处
        assertEquals(CalendarLayout.WEEKDAY_HEADER_H, state.selectedRowY, 1e-3f)
        assertEquals(week.cellH, state.metrics.cellH, 1e-3f)
        assertEquals(week.dayFont, state.metrics.dayFont, 1e-3f)
        assertEquals(0f, state.rowAboveAlpha, 1e-6f)
        assertEquals(-metrics.cellH, state.rowAboveY, 1e-3f)
        assertEquals(monthHeight, state.rowBelowY, 1e-3f)
        assertEquals(-CalendarLayout.BIG_TITLE_H, state.titleOffsetY, 1e-3f)
        assertEquals(0f, state.titleOpacity, 1e-6f)
        assertEquals(1f, state.contentT, 1e-6f)
        assertEquals(98f, state.contentOffsetY, 1e-3f)
        assertEquals(1f, state.contentAlpha, 1e-6f)
    }

    @Test
    fun `month week morph interpolates monotonically`() {
        val metrics = CalendarLayout.monthMetrics(
            portraitW, monthAreaH, showLunar = true, headerH = CalendarLayout.WEEKDAY_HEADER_H,
        )
        val week = CalendarLayout.weekMetrics(portraitW, showLunar = false)
        val baseRowY = metrics.cellH * 2
        val monthHeight = metrics.cellH * CalendarLayout.MONTH_ROWS

        var previousRowY = Float.MAX_VALUE
        var previousRowAboveAlpha = Float.MAX_VALUE
        var previousContentAlpha = -1f
        for (step in 0..20) {
            val state = CalendarLayout.monthWeekMorph(step / 20f, baseRowY, monthHeight, metrics, week)
            assertTrue("选中行应持续上移", state.selectedRowY <= previousRowY + 1e-3f)
            assertTrue("上方行应持续淡出", state.rowAboveAlpha <= previousRowAboveAlpha + 1e-6f)
            assertTrue("内容区应持续淡入", state.contentAlpha >= previousContentAlpha - 1e-6f)
            previousRowY = state.selectedRowY
            previousRowAboveAlpha = state.rowAboveAlpha
            previousContentAlpha = state.contentAlpha
        }
    }

    // ------------------------------------------------------------------ 翻页

    @Test
    fun `drag offset is clamped to one page`() {
        assertEquals(300f, CalendarLayout.DragPagerMath.clampOffset(500f, 300f), 1e-6f)
        assertEquals(-300f, CalendarLayout.DragPagerMath.clampOffset(-900f, 300f), 1e-6f)
        assertEquals(120f, CalendarLayout.DragPagerMath.clampOffset(120f, 300f), 1e-6f)
    }

    @Test
    fun `rubber band damps overflow`() {
        val pageSize = 402f
        val c = pageSize * CalendarLayout.DragPagerMath.RUBBER_BAND_RATIO
        val overflow = 74f
        assertEquals(c * overflow / (overflow + c), CalendarLayout.DragPagerMath.rubberBand(overflow, pageSize), 1e-3f)
        assertTrue("阻尼后必须小于原始越界量", CalendarLayout.DragPagerMath.rubberBand(overflow, pageSize) < overflow)
        assertTrue(CalendarLayout.DragPagerMath.rubberBand(overflow, pageSize) > 0f)
    }

    @Test
    fun `page decision uses the ninety and sixty point thresholds`() {
        val decide = CalendarLayout.DragPagerMath::decide

        // 预测终点超过 90 → 翻页
        assertEquals(CalendarLayout.PageTarget.Next, decide(-20f, -100f, true, true))
        assertEquals(CalendarLayout.PageTarget.Previous, decide(20f, 100f, true, true))

        // 预测终点同向但不足 90，而当前位移超过 60 → 仍然翻页
        assertEquals(CalendarLayout.PageTarget.Next, decide(-70f, -50f, true, true))
        assertEquals(CalendarLayout.PageTarget.Previous, decide(70f, 50f, true, true))

        // 两个阈值都不满足 → 回弹
        assertEquals(CalendarLayout.PageTarget.Stay, decide(-50f, -50f, true, true))
        assertEquals(CalendarLayout.PageTarget.Stay, decide(-59f, 0f, true, true))

        // 没有邻页时不翻
        assertEquals(CalendarLayout.PageTarget.Stay, decide(-200f, -300f, false, true))
        assertEquals(CalendarLayout.PageTarget.Stay, decide(200f, 300f, true, false))
    }

    @Test
    fun `drag thresholds match the ios constants`() {
        assertEquals(8f, CalendarLayout.DragPagerMath.MINIMUM_DRAG_DISTANCE, 1e-6f)
        assertEquals(0.25f, CalendarLayout.DragPagerMath.RUBBER_BAND_RATIO, 1e-6f)
        assertEquals(90f, CalendarLayout.DragPagerMath.PREDICTED_THRESHOLD, 1e-6f)
        assertEquals(60f, CalendarLayout.DragPagerMath.TRANSLATION_THRESHOLD, 1e-6f)
        assertEquals(300, CalendarLayout.PAGE_SETTLE_DURATION_MS)
    }

    // ------------------------------------------------------------------ 行号

    @Test
    fun `week row index matches the documented formula`() {
        val monthFirst = LocalDate.of(2026, 9, 1)
        // 周一起始：9/1 是周二，lead = 1 → 行 0
        assertEquals(0, CalendarLayout.weekRowIndex(LocalDate.of(2026, 9, 1), monthFirst, DateUtil.WEEK_START_MONDAY))
        assertEquals(0, CalendarLayout.weekRowIndex(LocalDate.of(2026, 9, 6), monthFirst, DateUtil.WEEK_START_MONDAY))
        assertEquals(1, CalendarLayout.weekRowIndex(LocalDate.of(2026, 9, 7), monthFirst, DateUtil.WEEK_START_MONDAY))
        assertEquals(4, CalendarLayout.weekRowIndex(LocalDate.of(2026, 9, 30), monthFirst, DateUtil.WEEK_START_MONDAY))
    }
}
