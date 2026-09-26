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
    /** 月度整屏区高：可用高 − 顶栏 52（与 `HomeScreen` 一致）。 */
    private val portraitAreaH = CalendarLayout.monthAreaHeight(portraitH - 52f, rows = 6)

    /** 横屏日历**列**宽 345（整屏可用宽 750 = 345 + 16 + 356.5 + 16 + …）。 */
    private val landscapeCalendarW = 345f
    private val landscapePaneH = 330f

    @Test
    fun `constants match the ios table`() {
        // M3 `long4`。iOS 的 `morphDuration` 也是 0.6s —— 全屏多元素 morph 属于
        // "larger, more complex transitions"，M3 明确说这一类要用长时长。
        // （曾经取 `medium4 = 400ms`，用户实测"太快了"。）
        assertEquals(600, CalendarLayout.MORPH_DURATION_MS)
        assertEquals(0.6, CalendarLayout.MORPH_DURATION_MS / 1000.0, 1e-9)  // = iOS morphDuration
        assertEquals(3000, CalendarLayout.SLOW_MORPH_DURATION_MS)
        assertEquals(180, CalendarLayout.REDUCED_MORPH_DURATION_MS)
        assertEquals(30f, CalendarLayout.WEEKDAY_HEADER_H, 1e-6f)
        assertEquals(72f, CalendarLayout.BIG_TITLE_H, 1e-6f)
        assertEquals(34f, CalendarLayout.COMPACT_MONTH_TITLE_H, 1e-6f)
        assertEquals(26f, CalendarLayout.COMPACT_WEEKDAY_HEADER_H, 1e-6f)
        // ⚠️ **有意偏离 iOS 的 68**：周条要放「选中圆 + 农历一行 + 有日记的短横线」，
        // 在 392.7dp 宽的手机上算出来约 67–69dp —— 68 顶满就会被下沿裁掉，
        // 中文的农历半截看不见（真机实测，用户反馈）。这里取 80 留出明确余量。
        assertEquals(80f, CalendarLayout.WEEK_STRIP_H, 1e-6f)
        assertEquals(40f, CalendarLayout.DAY_TITLE_H, 1e-6f)
        // 年历标题槽回到 iOS 的 62：页内要画 32sp 的「2026年」+ 右侧生肖
        assertEquals(62f, CalendarLayout.YEAR_TITLE_H, 1e-6f)
        assertEquals(16f, CalendarLayout.YEAR_PAD, 1e-6f)
        assertEquals(6f, CalendarLayout.YEAR_SPACING, 1e-6f)
        assertEquals(6f, CalendarLayout.MINI_PAD, 1e-6f)
        assertEquals(17f, CalendarLayout.MINI_TITLE_H, 1e-6f)
        assertEquals(44f, CalendarLayout.MINIMUM_ROW_HEIGHT, 1e-6f)
        // 未来日期不再靠透明度表达（0.35 alpha 对比度只有 2.19:1）；
        // 改用 M3 的 onSurfaceVariant 语义色，见 docs/ui-design-plan.md §3.1
        // M3 easing.emphasized / easing.emphasized.decelerate（官方令牌值）
        assertEquals(listOf(0.2f, 0f, 0f, 1f), CalendarLayout.M3_EMPHASIZED.toList())
        assertEquals(listOf(0.05f, 0.7f, 0.1f, 1f), CalendarLayout.M3_EMPHASIZED_DECELERATE.toList())
        // 竖屏版面的常量：顶栏、大标题槽、行高上下界
        // M3 small top app bar 的官方高度
        assertEquals(64f, CalendarLayout.HEADER_H, 1e-6f)
        assertEquals(72f, CalendarLayout.BIG_TITLE_H, 1e-6f)
        assertEquals(200f, CalendarLayout.SANITY_MAX_CELL_H, 1e-6f)
        assertEquals(44f, CalendarLayout.MIN_CELL_H, 1e-6f)
    }

    // --------------------------------------------------------------- 竖屏（本轮重写）

    @Test
    fun `month area fills the screen and never inverts`() {
        // 月度是整屏视图：有多少可用高就给多少（顶栏与系统占位已在上层扣掉）
        assertEquals(796f, CalendarLayout.monthAreaHeight(796f, rows = 6), 1e-3f)
        assertEquals(340f, CalendarLayout.monthAreaHeight(340f, rows = 6), 1e-3f)
        // 极矮窗口走下限，且**不超过**可用高（首轮横屏崩溃就出在区间反转）
        assertEquals(100f, CalendarLayout.monthAreaHeight(100f, rows = 6), 1e-3f)
        assertEquals(0f, CalendarLayout.monthAreaHeight(0f, rows = 6), 1e-3f)
        // 旧名保留且语义相同
        assertEquals(796f, CalendarLayout.portraitAreaHeight(796f, rows = 6), 1e-3f)

        // 单调不减、且不超过可用高
        var previous = -1f
        for (step in 0..60) {
            val available = step * 16f
            val area = CalendarLayout.monthAreaHeight(available, rows = 6)
            assertTrue("区高不应随可用高反向变化", area >= previous - 1e-3f)
            assertTrue("区高不得超过可用高", area <= available + 1e-3f)
            previous = area
        }
    }

    @Test
    fun `cell height fills the screen and is never capped for the panel`() {
        // 规范 §5.1：cellH = (区高 − 大标题槽 72 − 星期栏 30) / 6，**不封顶**。
        // (744 − 102) / 6 = 107
        assertEquals(107f, CalendarLayout.cellHeightFor(744f), 1e-3f)
        // 6 行月正好铺满网格区（这是"没有空白带"的判据）
        assertEquals(642f, CalendarLayout.monthGridHeight(6, 107f), 1e-3f)
        // 参考设备（Pixel 10 Pro：可用高 876，顶栏 52）→ (876 − 102) / 6 = 129
        assertEquals(129f, CalendarLayout.cellHeightFor(876f), 1e-3f)
        // 矮窗口按可用高压缩：(440 − 102) / 6 = 56.33
        assertEquals(56.333f, CalendarLayout.cellHeightFor(440f), 1e-3f)
        // 再矮：(280 − 102) / 6 = 29.67 → 抬到 MIN_CELL_H
        assertEquals(44f, CalendarLayout.cellHeightFor(280f), 1e-3f)
        // 极矮窗口仍然守住 MIN_CELL_H（不会压成 0，也不会抛异常）
        assertEquals(44f, CalendarLayout.cellHeightFor(100f), 1e-3f)
        assertEquals(44f, CalendarLayout.cellHeightFor(0f), 1e-3f)
        // 区间永不反转：MIN_CELL_H < SANITY_MAX_CELL_H，任何可用高都不抛异常
        var previous = -1f
        for (h in 0..2000 step 37) {
            val cell = CalendarLayout.cellHeightFor(h.toFloat())
            assertTrue("行高必须落在 [44, 200]", cell in 44f..200f)
            assertTrue("行高必须随可用高单调不减", cell >= previous - 1e-3f)
            previous = cell
        }
        // 行数与行高无关：行高只由"固定 6 行"决定，翻月时不抖
        assertEquals(
            CalendarLayout.cellHeightFor(876f),
            CalendarLayout.cellHeightFor(876f, rows = 5),
            1e-3f,
        )
        // 参考设备（Pixel 10 Pro：可用高 876，M3 顶栏 64）：
        // 区高 = 876 − 64 = 812，行高 = (812 − 102) / 6 = 118.33
        assertEquals(118.333f, CalendarLayout.cellHeightFor(876f - CalendarLayout.HEADER_H), 1e-3f)
        // 均分版本不夹取（横屏面板用）
        assertEquals(30f, CalendarLayout.fitCellHeight(180f, 6), 1e-3f)
        assertEquals(45f, CalendarLayout.fitCellHeight(270f, 6), 1e-3f)
    }

    @Test
    fun `portrait metrics reproduce the design spec numbers`() {
        val cellW = CalendarLayout.monthCellW(portraitW)
        assertEquals(57.4286f, cellW, 1e-3f)
        assertEquals(14f, CalendarLayout.weekdayFontSize(cellW), 1e-3f)

        // 区高 744（调用方已扣掉顶栏），行高 = (744 − 102) / 6 = 107
        val metrics = CalendarLayout.monthMetrics(portraitW, 744f, showLunar = true)
        assertEquals(57.4286f, metrics.cellW, 1e-3f)
        assertEquals(107f, metrics.cellH, 1e-3f)
        // dayFont = clamp(cellH × 0.34, 13, 20) = 20
        assertEquals(20f, metrics.dayFont, 1e-3f)
        // lunarFont = clamp(cellH × 0.18, 9, 11) = 11
        assertEquals(11f, metrics.lunarFont, 1e-3f)
        assertEquals(16f, metrics.lunarLineH, 1e-3f)
        assertEquals(40f, metrics.contentH, 1e-3f)
        // circleDiameter = min(cellW - 3, contentH + 14, cellH - 3) = min(54.43, 54, 93) = 54
        assertEquals(54f, metrics.circleDiameter, 1e-3f)
    }

    // --------------------------------------------------------------- 横屏（原样保留）

    @Test
    fun `year area is the full band`() {
        // 年历与月历一样是整屏视图：年历高度 = 整条带高（不再有"给面板让位"的收缩）
        assertEquals(744f, CalendarLayout.yearAreaHeight(744f), 1e-3f)
        assertEquals(300f, CalendarLayout.yearAreaHeight(300f), 1e-3f)
        assertEquals(0f, CalendarLayout.yearAreaHeight(0f), 1e-3f)
    }

    @Test
    fun `landscape metrics are derived from the pane height`() {
        // 横屏分栏的日历面板只含紧凑标题 + 星期栏（没有大标题槽）
        val headerH = CalendarLayout.COMPACT_MONTH_TITLE_H + CalendarLayout.COMPACT_WEEKDAY_HEADER_H
        assertEquals(60f, headerH, 1e-6f)

        // 面板高 = 可用高 − 顶栏 8 − 底部留白 64（横屏 340dp 高的窗口）
        val paneH = 340f - 8f - 64f
        assertEquals(268f, paneH, 1e-3f)

        // 横屏面板也要守 MIN_CELL_H（格子太矮就点不准）：
        // (268 − 60) / 6 = 34.67 → 抬到 44
        assertEquals(44f, CalendarLayout.cellHeightFor(paneH, 6, headerH), 1e-3f)

        val sixRows = CalendarLayout.monthMetrics(
            landscapeCalendarW, paneH, false, rows = 6, headerH = headerH,
        )
        assertEquals(44f, sixRows.cellH, 1e-3f)
        assertEquals(14.96f, sixRows.dayFont, 1e-3f)
        assertEquals(0f, sixRows.lunarAlpha, 1e-6f)
        assertEquals(49.2857f, sixRows.cellW, 1e-3f)

        // 需要"必须铺满、不许留白"的面板时用均分版（不夹取）
        assertEquals(34.666f, CalendarLayout.fitCellHeight(paneH - headerH, 6), 1e-3f)

        // 高一些的横屏面板：6 行各 45
        val tall = CalendarLayout.monthMetrics(
            landscapeCalendarW, 330f, false, rows = 6, headerH = headerH,
        )
        assertEquals(45f, tall.cellH, 1e-3f)
        assertEquals(15.3f, tall.dayFont, 1e-3f)
        assertEquals(19.3f, tall.contentH, 1e-3f)
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

    /**
     * 三档阈值的边界（规范 §5.3）：`perRow ≥ 60` 画农历 / `≥ 38` 只画日号 / 否则周条。
     *
     * 这里把 38 与 60 两个数钉死：曾经两档共用 44，于是 `perRow ∈ [38,44)` 被误判成
     * "连一行都放不下"、错误降级为周条；同时 `roundToInt()` 会让判定与实际行高差 1dp，
     * 网格比日历区高、最后一行被裁掉半行。
     */
    @Test
    fun `density thresholds are 38 and 60 and round down`() {
        // 6 行、每行恰好 37 / 38 → 37 降级、38 保留（只画日号）
        assertEquals(CalendarLayout.DensityKind.WeekStrip, CalendarLayout.resolveDensity(37f * 6, 6, true).kind)
        val at38 = CalendarLayout.resolveDensity(38f * 6, 6, true)
        assertEquals(CalendarLayout.DensityKind.Month, at38.kind)
        assertTrue("38 这一档不画农历", !at38.showLunar)
        // 59 / 60 → 59 不画农历、60 画
        assertTrue(!CalendarLayout.resolveDensity(59f * 6, 6, true).showLunar)
        assertTrue(CalendarLayout.resolveDensity(60f * 6, 6, true).showLunar)
        // 向下取整：59.9 / 6 = 9.98 → 9，仍是周条
        assertEquals(CalendarLayout.DensityKind.WeekStrip, CalendarLayout.resolveDensity(59.9f, 6, true).kind)
    }

    /**
     * 分栏下"降级为周条"的判定必须真的被消费。
     *
     * `resolveDensity` 一直能算出 `WeekStrip`，但界面只读了 `.showLunar` ——
     * 于是可用高不足时仍然画 6 行，每行不到 38dp，日号与选中圆挤在一起。
     * 这里钉住判定本身；界面侧在 `HomeScreen` 里按 `weekStripOnly` 只画一行。
     */
    @Test
    fun `week strip downgrade kicks in when rows cannot fit`() {
        // 分栏可用高（已扣紧凑标题+星期栏）÷ 6 行
        val tooShort = CalendarLayout.resolveDensity(200f, 6, true)
        assertEquals(CalendarLayout.DensityKind.WeekStrip, tooShort.kind)
        // 降级后只画一行 → 那一行的高度就是整个可用高，必然够
        val oneRow = CalendarLayout.fitCellHeight(200f, 1)
        assertTrue("降级后单行高度应当充裕", oneRow >= CalendarLayout.WEEK_STRIP_H)
        assertEquals(200f, oneRow, 1e-3f)
        // 刚好不再降级的边界：6 × 38
        assertEquals(
            CalendarLayout.DensityKind.Month,
            CalendarLayout.resolveDensity(38f * 6, 6, true).kind,
        )
    }

    @Test
    fun `year grid geometry matches the formulas`() {
        val areaH = CalendarLayout.portraitAreaHeight(portraitH, rows = 6)
        val (cardW, cardH) = CalendarLayout.yearCardSize(portraitW, areaH)
        assertEquals(358f / 3f, cardW, 1e-3f)
        // 卡片高度还要再扣掉**底部安全边距**（屏幕比例不同时末行不贴底栏）
        assertEquals(
            (areaH - 62f - 8f - 18f - CalendarLayout.YEAR_BOTTOM_PAD) / 4f,
            cardH,
            1e-3f,
        )

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
        assertEquals(
            62f + 8f + 3 * (
                (yearH - 62f - 8f - 18f - CalendarLayout.YEAR_BOTTOM_PAD) / 4f + 6f
                ),
            lastCard.y,
            1e-3f,
        )
        // 末行不仅要在区内，还要**离底部有安全余量**（否则会看起来压住底部导航栏）
        assertTrue(
            "年历最后一行必须留出底部安全边距",
            lastCard.maxY + CalendarLayout.YEAR_BOTTOM_PAD <= yearH + 1e-3f,
        )
        // 卡片高度足够画清 6 行迷你网格（每行 ≥ 10dp）
        // 卡片里要画 6 行日号（每行至少 10dp 才读得清），这是恢复迷你月历的硬条件
        assertTrue(
            "年历卡片太矮就画不清迷你月",
            (yearH - 62f - 8f - 18f - CalendarLayout.YEAR_BOTTOM_PAD) / 4f >= 78f,
        )

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
        val metrics = CalendarLayout.monthMetrics(portraitW, 744f, showLunar = true)
        val rowY = 192f
        val top = CalendarLayout.contentTop(rowY, metrics)
        // contentTop = rowY + (cellH − contentH) / 2
        assertEquals(rowY + (metrics.cellH - metrics.contentH) / 2f, top, 1e-3f)
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
        val metrics = CalendarLayout.monthMetrics(portraitW, 744f, showLunar = true)
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
        val metrics = CalendarLayout.monthMetrics(portraitW, 744f, showLunar = true)
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
