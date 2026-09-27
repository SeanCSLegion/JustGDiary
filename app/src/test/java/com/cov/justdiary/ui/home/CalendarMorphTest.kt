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
    private val monthAreaH = CalendarLayout.monthAreaHeight(portraitH - 52f)
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

    /**
     * 翻页方向：**向下拖回上一个月、向上拖到下一个月**。
     *
     * 这里钉的是一类真实发生过的回归 —— 收尾动画那边一度只用 `settled: Boolean`，
     * 把 `Next / Previous` 收敛成布尔值，结果**两个方向都往前翻**
     * （实测：从一月往下拖得到二月，往上拖得到三月）。
     * 方向必须一路带到 `pageMonth` / `jumpYear`，所以断言"决定"与"步长"两步都带符号。
     */
    @Test
    fun `swiping down goes to the previous page and up goes to the next`() {
        val down = CalendarLayout.DragPagerMath.decide(120f, 200f, hasNext = true, hasPrev = true)
        assertEquals(CalendarLayout.PageTarget.Previous, down)
        assertEquals(-1, CalendarLayout.stepOf(down))

        val up = CalendarLayout.DragPagerMath.decide(-120f, -200f, hasNext = true, hasPrev = true)
        assertEquals(CalendarLayout.PageTarget.Next, up)
        assertEquals(1, CalendarLayout.stepOf(up))

        assertEquals(
            "没到阈值就不翻",
            0,
            CalendarLayout.stepOf(
                CalendarLayout.DragPagerMath.decide(5f, 5f, hasNext = true, hasPrev = true),
            ),
        )
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

    /**
     * 月层与年层的透明度必须**互补**：任一帧至少有一层可见，否则 morph 前段会整屏空白。
     *
     * 实际曲线就是 `CalendarLayout.yearLayerOpacity`（月层用 `1 - 它`），这条测试把它钉住。
     */
    @Test
    fun `year layer opacity is complementary to the month layer`() {
        for (step in 0..20) {
            val p = step / 20f
            val monthAlpha = 1f - p
            val yearAlpha = CalendarLayout.yearLayerOpacity(p)
            assertTrue("p=$p 时两层都不可见（月 $monthAlpha / 年 $yearAlpha）", monthAlpha > 0f || yearAlpha > 0f)
        }
        // 端点：月态 p=0 年层不可见；年态 p=1 完全可见
        assertEquals(0f, CalendarLayout.yearLayerOpacity(0f), 1e-6f)
        assertEquals(1f, CalendarLayout.yearLayerOpacity(1f), 1e-6f)
        // 从 p=0.25 开始淡入（不是 0.45），否则前段会有一段"两层都很淡"
        assertEquals(0f, CalendarLayout.yearLayerOpacity(0.25f), 1e-6f)
        assertTrue("0.5 处年层要过半可见", CalendarLayout.yearLayerOpacity(0.5f) > 0.3f)
        // 单调不减
        var prev = -1f
        for (step in 0..20) {
            val v = CalendarLayout.yearLayerOpacity(step / 20f)
            assertTrue("年层透明度应单调不减", v >= prev - 1e-6f)
            prev = v
        }
    }

    /**
     * 年↔月 morph 的**网格重排**（`CalendarLayout.yearMonthGridMorph`）。
     *
     * 这一条是"月份缩进卡片时日期始终清晰"的守门人：格子必须按 `rowCount` 行 × 7 列
     * 落在插值出来的矩形里，字号在 miniDayFont 与月格字号之间插值 ——
     * 而不是把整块做仿射变换（那样文字会被压扁）。
     */
    @Test
    fun `year month grid morph relayouts the cells instead of squashing them`() {
        val flowMetrics = CalendarLayout.flowMetrics(portraitW, 94f, showLunar = true)
        val source = CalendarLayout.Rect(0f, 0f, portraitW, 5 * 94f)   // 五行月，静止位置
        val target = CalendarLayout.Rect(120f, 300f, 107f, 40f)        // 年历里那张迷你月的网格
        val rows = 5

        // p = 0（月侧）：源矩形原样、字号就是月格字号
        val atMonth = CalendarLayout.yearMonthGridMorph(0f, source, target, flowMetrics, true)
        assertEquals(1f, atMonth.t, 1e-6f)
        assertEquals(source.x, atMonth.rect.x, 1e-3f)
        assertEquals(source.y, atMonth.rect.y, 1e-3f)
        assertEquals(source.w, atMonth.rect.w, 1e-3f)
        assertEquals(source.h, atMonth.rect.h, 1e-3f)
        assertEquals(flowMetrics.dayFont, atMonth.metrics.dayFont, 1e-3f)
        assertEquals(flowMetrics.lunarFont, atMonth.metrics.lunarFont, 1e-3f)
        assertEquals(1f, atMonth.lunarAlpha, 1e-3f)
        assertEquals(1f, atMonth.dividerAlpha, 1e-3f)

        // p = 1（年侧）：正好落在迷你月网格上，字号收到 miniDayFont
        val atYear = CalendarLayout.yearMonthGridMorph(1f, source, target, flowMetrics, true)
        assertEquals(0f, atYear.t, 1e-6f)
        assertEquals(target.x, atYear.rect.x, 1e-3f)
        assertEquals(target.y, atYear.rect.y, 1e-3f)
        assertEquals(target.w, atYear.rect.w, 1e-3f)
        assertEquals(target.h, atYear.rect.h, 1e-3f)
        assertEquals(CalendarLayout.miniDayFont(target.w / 7f, target.h / rows), atYear.metrics.dayFont, 0.6f)
        assertEquals(0f, atYear.lunarAlpha, 1e-3f)
        assertEquals(0f, atYear.dividerAlpha, 1e-3f)

        // 端点：年侧的行高必须与年历迷你月一致（**固定 6 行**的尺子，不是 /rowCount）
        assertEquals(target.h / 6f, atYear.metrics.cellH, 1e-3f)
        assertEquals(94f, atMonth.metrics.cellH, 1e-3f)

        // 全程：格子始终 7 列（**重排**，不是压扁），宽 / 行高 / 字号三条都单调
        var previousW = Float.MAX_VALUE
        var previousH = Float.MAX_VALUE
        var previousFont = Float.MAX_VALUE
        for (step in 0..20) {
            val p = step / 20f
            val m = CalendarLayout.yearMonthGridMorph(p, source, target, flowMetrics, true)
            assertEquals("列宽 = 网格宽 / 7", m.rect.w / 7f, m.metrics.cellW, 1e-3f)
            assertTrue("行高单调不增", m.metrics.cellH <= previousH + 1e-3f)
            assertTrue("宽度单调不增", m.rect.w <= previousW + 1e-3f)
            assertTrue("字号单调不增", m.metrics.dayFont <= previousFont + 1e-3f)
            previousW = m.rect.w
            previousH = m.metrics.cellH
            previousFont = m.metrics.dayFont
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
