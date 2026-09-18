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
 * 期望值分两类来源：
 *  - **横屏那几条**仍来自 iOS 源码与 `docs/migration-analysis/03-home-calendar-and-motion.md`
 *    §2 的推演（横屏的 `cellH = (paneH - headerH) / rows` 是**有意保留**的：横屏高度稀缺，
 *    必须按可用高度均分）；
 *  - **竖屏那几条已按 `docs/design-spec.md` §5.1 重写**：竖屏行高固定
 *    [CalendarLayout.PORTRAIT_CELL_H]，日历区高度由「行数 × 行高」派生并封顶，
 *    不再照搬 iOS 的 `max(320, screenH - 64)`（在 Android 竖屏长宽比下会把月格拉散，
 *    这正是首轮上机暴露的头号缺陷）。
 *
 * 注意其中一处**与分析报告的差异**：报告的 §10 把横屏 6 行的 `circleD` 写成 34.3，
 * 按源码公式（`CalendarGrids.swift:24-26, 76-84`）应为 **33.3**（`dayFont 15.3 + 4 = 19.3`，
 * 再加 14 得 33.3）。这里以**源码**为准；横屏 5 行的 36.36 与报告一致，可反证公式没错。
 */
class CalendarLayoutTest {

    private val portraitW = 402f

    /** Pixel 10 Pro AVD 的首页内容区可用高度（952dp 屏 − 状态栏/导航栏/顶栏）。 */
    private val portraitH = 796f
    private val portraitAreaH = CalendarLayout.portraitAreaHeight(portraitH, rows = 6)

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
        // 年历标题槽从 iOS 的 62 调小到 44（标题已移到 M3 顶栏）
        assertEquals(44f, CalendarLayout.YEAR_TITLE_H, 1e-6f)
        assertEquals(16f, CalendarLayout.YEAR_PAD, 1e-6f)
        assertEquals(6f, CalendarLayout.YEAR_SPACING, 1e-6f)
        assertEquals(6f, CalendarLayout.MINI_PAD, 1e-6f)
        assertEquals(17f, CalendarLayout.MINI_TITLE_H, 1e-6f)
        assertEquals(44f, CalendarLayout.MINIMUM_ROW_HEIGHT, 1e-6f)
        assertEquals(0.35f, CalendarLayout.FUTURE_ALPHA, 1e-6f)
        assertEquals(listOf(0.2f, 0.75f, 0.3f, 1.0f), CalendarLayout.MORPH_EASING.toList())
        // 本轮新增的两个常量也必须被钉住：它们是竖屏版面的唯一真源
        assertEquals(56f, CalendarLayout.PORTRAIT_CELL_H, 1e-6f)
        assertEquals(0.62f, CalendarLayout.CALENDAR_MAX_HEIGHT_RATIO, 1e-6f)
        assertEquals(470f, CalendarLayout.YEAR_MIN_AREA_H, 1e-6f)
        assertEquals(200f, CalendarLayout.DAY_PANEL_MIN_H, 1e-6f)
    }

    // --------------------------------------------------------------- 竖屏（本轮重写）

    @Test
    fun `portrait area height is derived from content and capped`() {
        // 6 行：星期栏 30 + 6 × 56 = 366；上限 = 796 - 200（面板下限）= 596 → 取 366
        assertEquals(366f, CalendarLayout.portraitAreaHeight(796f, rows = 6), 1e-3f)
        // 5 行：30 + 5 × 56 = 310
        assertEquals(310f, CalendarLayout.portraitAreaHeight(796f, rows = 5), 1e-3f)

        // 矮窗口（横屏 ~350dp）：面板下限收缩到 140，日历拿 210（6 行 × 30dp）
        assertEquals(210f, CalendarLayout.portraitAreaHeight(350f, rows = 6), 1e-3f)
        // 再矮也不会让上下界反转（首轮这里会抛 IllegalArgumentException 崩屏）
        assertEquals(0f, CalendarLayout.portraitAreaHeight(0f, rows = 6), 1e-3f)
        // 极矮窗口（100dp，多窗口拖到最小）：面板下限被压到 40，日历拿 60
        assertEquals(60f, CalendarLayout.portraitAreaHeight(100f, rows = 6), 1e-3f)
        // 月份只有 5 行时，350dp 里日历仍保持同一上限
        assertEquals(210f, CalendarLayout.portraitAreaHeight(350f, rows = 5), 1e-3f)

        // 0 行不崩：按「至少 1 行」处理 = 30 + 56 = 86
        assertEquals(86f, CalendarLayout.portraitAreaHeight(796f, rows = 0), 1e-3f)

        // 单调性：可用高越大，日历区不会变小
        var previous = -1f
        for (step in 0..60) {
            val available = step * 16f
            val area = CalendarLayout.portraitAreaHeight(available, rows = 6)
            assertTrue("区高不应随可用高反向变化", area >= previous - 1e-3f)
            assertTrue("区高不得超过可用高", area <= available + 1e-3f)
            previous = area
        }
    }

    @Test
    fun `portrait cell height is fixed instead of stretched to the screen`() {
        // **这是首轮缺陷的回归断言**：行高不再随屏幕高度变化
        assertEquals(56f, CalendarLayout.monthCellH(CalendarLayout.portraitAreaHeight(796f, 6)), 1e-3f)
        assertEquals(56f, CalendarLayout.monthCellH(CalendarLayout.portraitAreaHeight(1200f, 6)), 1e-3f)
        // 旧实现在 952dp 屏上给出 126dp，断言它已经不是那个值
        assertTrue(
            "竖屏行高必须与屏幕高度解耦",
            CalendarLayout.monthCellH(CalendarLayout.portraitAreaHeight(872f, 6)) < 70f,
        )
    }

    @Test
    fun `portrait metrics reproduce the design spec numbers`() {
        val cellW = CalendarLayout.monthCellW(portraitW)
        assertEquals(57.4286f, cellW, 1e-3f)
        assertEquals(14f, CalendarLayout.weekdayFontSize(cellW), 1e-3f)

        val metrics = CalendarLayout.monthMetrics(portraitW, portraitAreaH, showLunar = true)
        assertEquals(57.4286f, metrics.cellW, 1e-3f)
        assertEquals(56f, metrics.cellH, 1e-3f)
        // dayFont = clamp(cellH × 0.34, 13, 20) = clamp(19.04) → 19.04
        assertEquals(19.04f, metrics.dayFont, 1e-3f)
        // lunarFont = clamp(cellH × 0.18, 9, 11) = clamp(10.08) → 10.08
        assertEquals(10.08f, metrics.lunarFont, 1e-3f)
        assertEquals(15.08f, metrics.lunarLineH, 1e-3f)
        assertEquals(38.12f, metrics.contentH, 1e-3f)
        // circleDiameter = min(cellW - 3, contentH + 14, cellH - 3) = min(54.43, 52.12, 53) = 52.12
        assertEquals(52.12f, metrics.circleDiameter, 1e-3f)
    }

    @Test
    fun `cell height degrades gracefully in very short windows`() {
        // 36dp 是压缩下限：可用高 200、6 行 → (200 - 30) / 6 = 28.3 → 夹到 36
        assertEquals(36f, CalendarLayout.cellHeightFor(200f, rows = 6), 1e-3f)
        // 正常高度不压缩
        assertEquals(56f, CalendarLayout.cellHeightFor(366f, rows = 6), 1e-3f)
        // 0 行不会崩
        assertEquals(56f, CalendarLayout.cellHeightFor(366f, rows = 0), 1e-3f)
    }

    @Test
    fun `year area is taller than the month area but leaves room for the panel`() {
        // 年历需要 470（12 张卡片各含 6 行迷你网格）
        assertEquals(470f, CalendarLayout.yearAreaHeight(796f), 1e-3f)
        // 矮窗口里让位给下方面板：availableH - 200
        assertEquals(300f, CalendarLayout.yearAreaHeight(500f), 1e-3f)
        assertEquals(0f, CalendarLayout.yearAreaHeight(150f), 1e-3f)
        // 年历区比同屏的月历区高（月历 6 行 = 366）
        assertTrue(
            "年历需要更多垂直空间",
            CalendarLayout.yearAreaHeight(796f) > CalendarLayout.portraitAreaHeight(796f, rows = 6),
        )
    }

    // --------------------------------------------------------------- 横屏（原样保留）

    @Test
    fun `landscape metrics reproduce the documented numbers`() {
        val headerH = CalendarLayout.COMPACT_MONTH_TITLE_H + CalendarLayout.COMPACT_WEEKDAY_HEADER_H
        assertEquals(60f, headerH, 1e-6f)
        assertEquals(330f, CalendarLayout.landscapePaneHeight(402f, 8f, 64f), 1e-3f)

        val sixRows = CalendarLayout.monthMetrics(
            landscapeCalendarW, landscapePaneH, false, rows = 6, headerH = headerH,
        )
        assertEquals(45f, sixRows.cellH, 1e-3f)
        assertEquals(15.3f, sixRows.dayFont, 1e-3f)
        assertEquals(19.3f, sixRows.contentH, 1e-3f)
        assertEquals(33.3f, sixRows.circleDiameter, 1e-3f)

        val fiveRows = CalendarLayout.monthMetrics(
            landscapeCalendarW, landscapePaneH, false, rows = 5, headerH = headerH,
        )
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
        val areaH = CalendarLayout.portraitAreaHeight(portraitH, rows = 6)
        val (cardW, cardH) = CalendarLayout.yearCardSize(portraitW, areaH)
        assertEquals(358f / 3f, cardW, 1e-3f)
        assertEquals((areaH - 44f - 8f - 18f) / 4f, cardH, 1e-3f)

        val january = CalendarLayout.yearCardRect(1, portraitW, areaH)
        val startY = CalendarLayout.YEAR_TITLE_H + CalendarLayout.YEAR_TOP_PAD
        assertEquals(16f, january.x, 1e-3f)
        assertEquals(startY, january.y, 1e-3f)

        val december = CalendarLayout.yearCardRect(12, portraitW, areaH)
        assertEquals(16f + 2 * (cardW + 6f), december.x, 1e-3f)
        assertEquals(startY + 3 * (cardH + 6f), december.y, 1e-3f)
        // 用**年历自己的**区高算一遍：第 4 行必须落在区内（否则会被下方当日面板压住）
        val yearH = CalendarLayout.yearAreaHeight(portraitH)
        val lastCard = CalendarLayout.yearCardRect(12, portraitW, yearH)
        assertEquals(44f + 8f + 3 * ((yearH - 44f - 8f - 18f) / 4f + 6f), lastCard.y, 1e-3f)
        assertTrue("年历最后一行必须留在区高之内", lastCard.maxY <= yearH)
        // 卡片高度足够画清 6 行迷你网格（每行 ≥ 10dp）
        assertTrue("年历卡片太矮就画不清迷你月", (yearH - 44f - 8f - 18f) / 4f >= 78f)

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

        // 带 headerH 的新签名：年历网格必须从与月历相同的内容区起点开始
        val full = CalendarLayout.fullMonthGridRect(portraitW, areaH, headerH = CalendarLayout.WEEKDAY_HEADER_H)
        assertEquals(0f, full.x, 1e-3f)
        assertEquals(30f, full.y, 1e-3f)
        assertEquals(402f, full.w, 1e-3f)
        assertEquals(areaH - 30f, full.h, 1e-3f)

        // 旧签名保留（大标题槽版本）
        val legacy = CalendarLayout.fullMonthGridRect(portraitW, areaH)
        assertEquals(102f, legacy.y, 1e-3f)
    }

    @Test
    fun `day drawing geometry matches the formulas`() {
        val metrics = CalendarLayout.monthMetrics(portraitW, portraitAreaH, showLunar = true)
        val rowY = 112f
        val top = CalendarLayout.contentTop(rowY, metrics)
        // contentTop = rowY + (cellH - contentH) / 2
        assertEquals(rowY + (56f - 38.12f) / 2f, top, 1e-3f)
        assertEquals(top + metrics.dayFont / 2f, CalendarLayout.dayNumberCenterY(top, metrics), 1e-3f)
        assertEquals(
            top + metrics.dayFont + 4f + metrics.lunarLineH / 2f,
            CalendarLayout.lunarCenterY(top, metrics),
            1e-3f,
        )
        assertEquals(28.7143f, CalendarLayout.cellCenterX(0, metrics.cellW), 1e-3f)

        val mark = CalendarLayout.diaryMark(top, metrics)
        assertEquals(20f, mark.width, 1e-3f)
        assertEquals(2f, mark.height, 1e-3f)
        assertEquals(1f, mark.cornerRadius, 1e-3f)
        // markY = numY + dayFont/2 + 2 = top + dayFont + 2
        assertEquals(top + metrics.dayFont + 2f, mark.y, 1e-3f)

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
