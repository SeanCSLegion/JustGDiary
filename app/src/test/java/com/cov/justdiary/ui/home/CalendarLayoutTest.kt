package com.cov.justdiary.ui.home

import com.cov.justdiary.model.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日历布局数学测试。
 *
 * 期望值来自 iOS 源码与 `docs/migration-analysis/03-home-calendar-and-motion.md` §2 的推演：
 * 竖屏 402×874 与横屏 750×330 两组数值就是这些断言。
 *
 * 注意其中一处**与分析报告的差异**：报告的 §10 把横屏 6 行的 `circleD` 写成 34.3，
 * 按源码公式（`CalendarGrids.swift:24-26, 76-84`）应为 **33.3**（`dayFont 15.3 + 4 = 19.3`，
 * 再加 14 得 33.3）。这里以**源码**为准；横屏 5 行的 36.36 与报告一致，可反证公式没错。
 */
class CalendarLayoutTest {

    private val portraitW = 402f
    private val portraitH = 874f
    private val portraitAreaH = CalendarLayout.portraitAreaHeight(portraitH)
    /** 横屏日历**列**宽 345（整屏可用宽 750 = 345 + 16 + 356.5 + 16 + …）。 */
    private val landscapeCalendarW = 345f
    private val landscapePaneH = 330f

    @Test
    fun `constants match the ios table`() {
        assertEquals(600, CalendarLayout.MORPH_DURATION_MS)
        assertEquals(3000, CalendarLayout.SLOW_MORPH_DURATION_MS)
        assertEquals(180, CalendarLayout.REDUCED_MORPH_DURATION_MS)
        assertEquals(30f, CalendarLayout.WEEKDAY_HEADER_H, 1e-6f)
        assertEquals(72f, CalendarLayout.BIG_TITLE_H, 1e-6f)
        assertEquals(34f, CalendarLayout.COMPACT_MONTH_TITLE_H, 1e-6f)
        assertEquals(26f, CalendarLayout.COMPACT_WEEKDAY_HEADER_H, 1e-6f)
        assertEquals(68f, CalendarLayout.WEEK_STRIP_H, 1e-6f)
        assertEquals(40f, CalendarLayout.DAY_TITLE_H, 1e-6f)
        assertEquals(62f, CalendarLayout.YEAR_TITLE_H, 1e-6f)
        assertEquals(16f, CalendarLayout.YEAR_PAD, 1e-6f)
        assertEquals(6f, CalendarLayout.YEAR_SPACING, 1e-6f)
        assertEquals(6f, CalendarLayout.MINI_PAD, 1e-6f)
        assertEquals(17f, CalendarLayout.MINI_TITLE_H, 1e-6f)
        assertEquals(44f, CalendarLayout.MINIMUM_ROW_HEIGHT, 1e-6f)
        assertEquals(0.35f, CalendarLayout.FUTURE_ALPHA, 1e-6f)
        assertEquals(listOf(0.2f, 0.75f, 0.3f, 1.0f), CalendarLayout.MORPH_EASING.toList())
    }

    @Test
    fun `portrait metrics reproduce the documented numbers`() {
        assertEquals(810f, portraitAreaH, 1e-3f)

        val cellW = CalendarLayout.monthCellW(portraitW)
        assertEquals(57.4286f, cellW, 1e-3f)
        assertEquals(14f, CalendarLayout.weekdayFontSize(cellW), 1e-3f)

        val cellH = CalendarLayout.monthCellH(portraitAreaH)
        assertEquals(118f, cellH, 1e-3f)

        val metrics = CalendarLayout.monthMetrics(portraitW, portraitAreaH, showLunar = true)
        assertEquals(57.4286f, metrics.cellW, 1e-3f)
        assertEquals(118f, metrics.cellH, 1e-3f)
        assertEquals(20f, metrics.dayFont, 1e-3f)
        assertEquals(11f, metrics.lunarFont, 1e-3f)
        assertEquals(16f, metrics.lunarLineH, 1e-3f)
        assertEquals(40f, metrics.contentH, 1e-3f)
        assertEquals(54f, metrics.circleDiameter, 1e-3f)
    }

    @Test
    fun `landscape metrics reproduce the documented numbers`() {
        val headerH = CalendarLayout.COMPACT_MONTH_TITLE_H + CalendarLayout.COMPACT_WEEKDAY_HEADER_H
        assertEquals(60f, headerH, 1e-6f)
        assertEquals(330f, CalendarLayout.landscapePaneHeight(402f, 8f, 64f), 1e-3f)

        val sixRows = CalendarLayout.monthMetrics(landscapeCalendarW, landscapePaneH, false, rows = 6, headerH = headerH)
        assertEquals(45f, sixRows.cellH, 1e-3f)
        assertEquals(15.3f, sixRows.dayFont, 1e-3f)
        assertEquals(19.3f, sixRows.contentH, 1e-3f)
        assertEquals(33.3f, sixRows.circleDiameter, 1e-3f)

        val fiveRows = CalendarLayout.monthMetrics(landscapeCalendarW, landscapePaneH, false, rows = 5, headerH = headerH)
        assertEquals(54f, fiveRows.cellH, 1e-3f)
        assertEquals(18.36f, fiveRows.dayFont, 1e-3f)
        assertEquals(36.36f, fiveRows.circleDiameter, 1e-3f)

        assertEquals(0f, sixRows.lunarAlpha, 1e-6f)
        assertEquals(49.2857f, sixRows.cellW, 1e-3f)
    }

    @Test
    fun `weekday font is clamped at both ends`() {
        assertEquals(11f, CalendarLayout.weekdayFontSize(20f), 1e-6f)
        assertEquals(14f, CalendarLayout.weekdayFontSize(300f), 1e-6f)
        assertEquals(13f, CalendarLayout.weekdayFontSize(50f), 1e-6f)
    }

    @Test
    fun `density resolution follows the two thresholds`() {
        assertEquals(CalendarLayout.DensityKind.Month, CalendarLayout.resolveDensity(360f, 6, true).kind)
        assertTrue(CalendarLayout.resolveDensity(360f, 6, true).showLunar)
        assertTrue(!CalendarLayout.resolveDensity(360f, 6, false).showLunar)

        val medium = CalendarLayout.resolveDensity(270f, 6, true)
        assertEquals(CalendarLayout.DensityKind.Month, medium.kind)
        assertTrue("中等密度必须隐藏农历", !medium.showLunar)

        assertEquals(CalendarLayout.DensityKind.WeekStrip, CalendarLayout.resolveDensity(200f, 6, true).kind)

        assertEquals(CalendarLayout.DensityKind.Month, CalendarLayout.resolveDensity(270f, 5, true).kind)
        assertEquals(CalendarLayout.DensityKind.Month, CalendarLayout.resolveDensity(270f, 6, true).kind)
        assertTrue("402pt 高的横屏不会触发周条", 330f - 60f >= 44f * 6)
    }

    @Test
    fun `year grid geometry matches the formulas`() {
        val (cardW, cardH) = CalendarLayout.yearCardSize(portraitW, portraitH)
        assertEquals(358f / 3f, cardW, 1e-3f)
        assertEquals(786f / 4f, cardH, 1e-3f)

        val january = CalendarLayout.yearCardRect(1, portraitW, portraitH)
        assertEquals(16f, january.x, 1e-3f)
        assertEquals(70f, january.y, 1e-3f)

        val december = CalendarLayout.yearCardRect(12, portraitW, portraitH)
        assertEquals(16f + 2 * (cardW + 6f), december.x, 1e-3f)
        assertEquals(70f + 3 * (cardH + 6f), december.y, 1e-3f)

        val title = CalendarLayout.miniTitleRect(january)
        val grid = CalendarLayout.miniGridRect(january)
        assertEquals(january.x + 6f, title.x, 1e-3f)
        assertEquals(january.y + 6f, title.y, 1e-3f)
        assertEquals(january.x + 6f, grid.x, 1e-3f)
        assertEquals(january.y + 23f, grid.y, 1e-3f)
        assertEquals(17f, title.h, 1e-3f)

        val mini = CalendarLayout.miniMetrics(grid)
        assertTrue("迷你月不显示农历", mini.lunarAlpha == 0f && mini.lunarFont == 6f)
        assertTrue("迷你月日号字号被夹在 9..14", mini.dayFont in 9f..14f)

        val full = CalendarLayout.fullMonthGridRect(portraitW, portraitH)
        assertEquals(0f, full.x, 1e-3f)
        assertEquals(102f, full.y, 1e-3f)
        assertEquals(402f, full.w, 1e-3f)
        assertEquals(772f, full.h, 1e-3f)
    }

    @Test
    fun `day drawing geometry matches the formulas`() {
        val metrics = CalendarLayout.monthMetrics(portraitW, portraitAreaH, showLunar = true)
        val rowY = 236f
        val top = CalendarLayout.contentTop(rowY, metrics)
        assertEquals(rowY + 39f, top, 1e-3f)
        assertEquals(top + 10f, CalendarLayout.dayNumberCenterY(top, metrics), 1e-3f)
        assertEquals(top + 32f, CalendarLayout.lunarCenterY(top, metrics), 1e-3f)
        assertEquals(28.7143f, CalendarLayout.cellCenterX(0, metrics.cellW), 1e-3f)

        val mark = CalendarLayout.diaryMark(top, metrics)
        assertEquals(20f, mark.width, 1e-3f)
        assertEquals(2f, mark.height, 1e-3f)
        assertEquals(1f, mark.cornerRadius, 1e-3f)
        // uy = numY + dayFont/2 + 2 = top + dayFont + 2
        assertEquals(top + 22f, mark.y, 1e-3f)

        assertEquals(1.5f, CalendarLayout.TODAY_RING_WIDTH, 1e-6f)
    }

    @Test
    fun `hit testing returns the day under the point and rejects out of range`() {
        val metrics = CalendarLayout.monthMetrics(portraitW, portraitAreaH, showLunar = true)
        val weeks = CalendarGrid.weeks(LocalDate.of(2026, 9, 1), DateUtil.WEEK_START_MONDAY)
        val anchor = LocalDate.of(2026, 9, 1)

        val date = CalendarLayout.dayAt(
            CalendarLayout.cellCenterX(0, metrics.cellW),
            metrics.cellH * 2 + 10f,
            metrics,
            weeks,
            anchor,
            showAdjacent = true,
        )
        assertNotNull(date)

        // 注意 (x/cellW).toInt() 向零截断：x = -1 会落回第 0 列（与 iOS `Int(x/cellW)` 同行为），
        // 因此越界样例要用一整格宽；真实点击也被视图边界夹住，不会出现 -1。
        assertNull(CalendarLayout.dayAt(-metrics.cellW, 0f, metrics, weeks, anchor, true))
        assertNull(CalendarLayout.dayAt(0f, metrics.cellH * 99f, metrics, weeks, anchor, true))
    }

    @Test
    fun `hit testing rejects adjacent month days when they are not shown`() {
        val metrics = CalendarLayout.monthMetrics(portraitW, portraitAreaH, showLunar = true)
        val weeks = CalendarGrid.weeks(LocalDate.of(2026, 9, 1), DateUtil.WEEK_START_MONDAY)
        val anchor = LocalDate.of(2026, 10, 1)

        val x = CalendarLayout.cellCenterX(0, metrics.cellW)
        val y = 5f

        assertNotNull(
            "showAdjacent = true 时应能点到相邻月",
            CalendarLayout.dayAt(x, y, metrics, weeks, anchor, showAdjacent = true),
        )
        assertNull(
            "showAdjacent = false 时不应点到相邻月",
            CalendarLayout.dayAt(x, y, metrics, weeks, anchor, showAdjacent = false),
        )
    }

    @Test
    fun `week row hit testing only needs the column`() {
        val metrics = CalendarLayout.weekMetrics(portraitW, showLunar = false)
        val week = CalendarGrid.weeks(LocalDate.of(2026, 9, 1), DateUtil.WEEK_START_MONDAY).first()
        assertEquals(week[3], CalendarLayout.weekDayAt(CalendarLayout.cellCenterX(3, metrics.cellW), metrics, week))
        assertNull(CalendarLayout.weekDayAt(metrics.cellW * 7f + 1f, metrics, week))
    }
}
