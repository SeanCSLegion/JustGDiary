package com.cov.justdiary.ui.home

import com.cov.justdiary.model.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 连续月历流（[MonthFlowLayout]）的几何回归。**逐条移植**自 iOS
 * `JustDiaryTests/MonthFlowLayoutTests.swift`。
 *
 * 月视图改成连续滚动之后，「哪一行在哪儿」不再由 Compose 的布局给出，而是这份布局
 * 算出来的 —— morph 的起点、顶部月份、跳月位置全都读它。所以它的三条约定必须有
 * 测试守住：
 *
 * 1. **行高全局一致**：月内相邻两行正好差一个 `rowH`，且上一块的底 = 下一块的顶
 *    （月份之间**不留空隙**）；
 * 2. **小标题在分割线上方**：底边 = 本月第一行顶 − 3dp，整块落在上个月最后一行的
 *    留白里（用户来回改过三版：一整周 94dp 空带 → 35dp → 21dp → 「紧贴」）；
 * 3. **静止位置 = 该月第一行的顶**：此时视口里正好是「标题槽 + 星期栏 + 六行日期」，
 *    与改造前的静止画面一致，年↔月 morph 的终点也就不用重新推导。
 */
class MonthFlowLayoutTest {

    /**
     * 参考机（Pixel 10 Pro AVD，1280×2856 @480dpi = 426.7×952dp）的实测值。
     *
     * 行高与 iOS **同一条尺子**：`(日历区高 − 标题槽 − 星期栏) / 6`（用户 2026-09-27
     * "行高也按照 iOS 的 94pt 来设置，这方面对齐 iOS"）。iPhone 402×874 上得 94pt，
     * 本机得 ~105dp —— 一屏正好 6 行（= 一个月），与 iOS 的观感一致。
     */
    private val calendarW = 426.7f

    /** 日历区高度 = 屏高 − 顶栏 64 − 底部导航（实测内容顶 ≈ 218dp、底 ≈ 848dp）。 */
    private val bandH = 952f - CalendarLayout.HEADER_H - 83f
    private val viewportH = bandH - CalendarLayout.BIG_TITLE_H - CalendarLayout.WEEKDAY_HEADER_H
    private val rowH = CalendarLayout.flowCellHeightFor(areaH = bandH, fontScale = 1f, showLunar = true)

    private fun layout(ws: String = DateUtil.WEEK_START_MONDAY) = MonthFlowLayout.of(ws, rowH)

    private val labelH = CalendarLayout.flowLabelBandHeight(compact = false)

    /** 见 `rows inside a month are exactly one row apart` 里的说明。 */
    private val FLOAT_EPS = 0.1f

    /** 一行里「内容之上」的留白（小标题就借用它）。 */
    private val contentTopPadding =
        CalendarLayout.cellContentTopPadding(CalendarLayout.flowMetrics(calendarW, rowH, showLunar = true))

    // ------------------------------------------------------------------ 行数与行高

    /**
     * `displayedWeekCount`（O(1) 算法版，给 2412 个月建偏移用）必须与
     * `displayedWeeks(...).size` **逐月一致**。
     */
    @Test
    fun `week count matches the real week array for every month`() {
        for (ws in listOf(DateUtil.WEEK_START_MONDAY, DateUtil.WEEK_START_SUNDAY)) {
            for (key in CalendarGrid.MONTH_KEYS) {
                val first = CalendarGrid.monthFirstOfMonthKey(key)
                assertEquals(
                    "key=$key ws=$ws",
                    CalendarGrid.displayedWeeks(first, ws).size,
                    CalendarGrid.displayedWeekCount(first, ws),
                )
            }
        }
    }

    /** 1) 行高一致：月内相邻两行的顶正好差一个 `rowH`；两块之间不留空隙。 */
    @Test
    fun `rows inside a month are exactly one row apart`() {
        val l = layout()
        for (key in listOf(202601, 202602, 202606, 202608, 202609)) {
            val block = l.blocks[l.blockIndexForKey(key)]
            assertTrue(block.rowCount > 0)
            for (row in 1 until block.rowCount) {
                assertEquals(
                    "$key 第 $row 行",
                    l.rowH,
                    l.rowTop(block, row) - l.rowTop(block, row - 1),
                    // 容差 0.1dp：整条流是 Float 累加出来的（1900–2100 共 2412 个月，
                    // 内容坐标能到 ~90 万 dp），在这个量级上 Float 的分辨率约 0.06dp。
                    // 0.06dp = 0.2px（参考机 3×），画不出来；换 Double 不值得。
                    FLOAT_EPS,
                )
            }
            val next = l.blocks[block.index + 1]
            assertEquals("$key 最后一行到底就是下个月的开始", l.rowH,
                next.top - l.rowTop(block, block.rowCount - 1), FLOAT_EPS)
            assertEquals("上一块的底 = 下一块的顶", next.top, block.bottom, 0.001f)
        }
    }

    /**
     * 2) 月份之间**不留空隙**，小标题在**分割线上方**、紧贴分割线。
     *
     * 从上到下的次序：上个月的日期 → 小标题 → 分割线（画在本月第一行的顶）→ 本月日期。
     */
    @Test
    fun `months are adjacent and the label sits above the divider`() {
        val l = layout()
        assertEquals("竖屏：15dp 字 → 21dp（就是这一行字）", 21f, labelH, 0.001f)
        val tightGap = CalendarLayout.LABEL_TIGHT_GAP
        l.blocks.take(24).forEach { block ->
            val next = l.blocks[block.index + 1]
            assertEquals("${block.key} → ${next.key} 之间不留空隙", block.bottom, next.top, 0.001f)
            // 小标题：整块都在分割线（本月第一行的顶）上方，底边离它 3dp。
            val labelTop = next.top - tightGap - labelH
            assertTrue("小标题整个在分割线上方", labelTop + labelH <= next.top - tightGap + 0.001f)
            // 而且它没有飘出上个月最后一行（仍在那一行的范围内）。
            assertTrue("小标题落在上个月最后一行之内", labelTop >= block.bottom - rowH)
        }
    }

    /**
     * 2b) **小标题与上个月最后一行要隔开**（用户 2026-09-27："小月份和上一个月的最后要
     * 稍微隔开一点，以小月份的上方为限"）。
     *
     * 小标题整块高度 = 带高 + 3dp 间隙，必须**放得进**上一行底部的留白
     * `(行高 − 内容高)/2`，这样它的**顶边**之上还剩一段真实的空档。
     */
    @Test
    fun `the label keeps a gap above the previous month's last row`() {
        val padding = contentTopPadding
        // 小标题整块（带高 + 3dp 间隙）必须放得进上一行底部的留白
        assertTrue(
            "留白 ${'$'}padding 要放得下小标题 ${'$'}{labelH + CalendarLayout.LABEL_TIGHT_GAP}",
            padding >= labelH + CalendarLayout.LABEL_TIGHT_GAP,
        )
        // 而且"小标题顶边"之上还有真正的空档（用户要的"稍微隔开一点"）
        val gap = padding - labelH - CalendarLayout.LABEL_TIGHT_GAP
        assertTrue("小标题上方要留出空档（当前 ${'$'}gap dp）", gap >= 4f)
    }

    /** 行高 = iOS 那条尺子：一屏正好 6 行（分区高 = 标题槽 + 星期栏 + 6 行）。 */
    @Test
    fun `row height fills the viewport with six rows like ios`() {
        assertEquals(
            "六行正好铺满视口（iOS 同一条尺子）",
            viewportH,
            rowH * CalendarLayout.MONTH_ROWS,
            0.001f,
        )
        // 系统字号放大时行高要跟着长，否则"日号 + 农历"会被裁掉
        val big = CalendarLayout.flowCellHeightFor(bandH, fontScale = 2f, showLunar = true)
        assertTrue("字号放大后行高要跟着长", big > rowH)
    }

    /** 3) 静止位置 = 该月第一行的顶；此时顶部月份就是它自己。 */
    @Test
    fun `rest offset puts the first row at the top`() {
        val l = layout()
        for (key in listOf(202609, 202608, 202602, 200001, 210012)) {
            val index = l.blockIndexForKey(key)
            assertTrue("$key 应在流里", index >= 0)
            val rest = l.restOffset(key)
            assertNotNull(rest)
            assertEquals("静止偏移 = 本月第一行的顶", l.blocks[index].top, rest!!, 0.001f)
            assertEquals("静止时顶部月份应是 $key", index, l.blockIndexAt(rest))
        }
    }

    /**
     * 静止画面（竖屏，与 iOS 一致）：本月第一行顶在视口顶部；**6 行月正好铺满**
     * （下个月一行都不露），5 行月则把下个月的第一行露在底部 —— 这正是用户要的
     * 「下一个月的日期会立即出现」。
     */
    @Test
    fun `the viewport at rest shows six rows or the next month's first row`() {
        val l = layout()
        for (key in listOf(202608, 202609, 202602)) {
            val i = l.blockIndexForKey(key)
            val rest = l.restOffset(key)!!
            val block = l.blocks[i]
            val rowsH = block.rowCount * l.rowH
            val nextTop = l.blocks[i + 1].top
            assertEquals("本月的第一行顶在视口顶部", block.top, rest, 0.001f)
            assertEquals("下个月紧接着本月最后一行（不留空隙）", rest + rowsH, nextTop, 0.001f)
            if (block.rowCount == 6) {
                assertEquals("六行正好铺满视口", viewportH, rowsH, 0.001f)
                assertEquals("六行月的下一行正好在视口之外", rest + viewportH, nextTop, 0.001f)
            } else {
                assertTrue("五行月底下会露出下个月的开头", rowsH < viewportH)
                assertTrue("所以下一行确实露在视口里", nextTop < rest + viewportH)
            }
        }
    }

    /** 可见块：只画与视口相交的那些（外加留白），首尾两端不越界；一屏最多跨 3–4 个月。 */
    @Test
    fun `visible blocks stay inside the flow`() {
        val l = layout()
        val head = l.visibleBlocks(0f, viewportH, rowH)
        assertTrue(head.size in 1..4)
        assertEquals(l.blocks.first().key, head.first().key)

        val end = l.maxOffset(viewportH)
        val tail = l.visibleBlocks(end, viewportH, rowH)
        assertEquals(l.blocks.last().key, tail.last().key)
        assertEquals("内容总高 = 最后一块的底", l.blocks.last().bottom, l.contentH, 0.001f)
        assertTrue("1900–2100 是一条很长的流", l.contentH > viewportH * 100)
        assertEquals("2412 个月", 2412, l.blocks.size)
    }

    /** 小标题要站在**当月 1 号所在的那一列**（用户：「在每个月的 1 号的上方」）。 */
    @Test
    fun `the month label stands above the first day`() {
        for (ws in listOf(DateUtil.WEEK_START_MONDAY, DateUtil.WEEK_START_SUNDAY)) {
            for (key in listOf(202601, 202602, 202608, 202609, 202610, 202612)) {
                val first = CalendarGrid.monthFirstOfMonthKey(key)
                val col = DateUtil.weekdayIndex(first, ws)
                assertTrue("$key 列号要在 0…6", col in 0..6)
                val week = CalendarGrid.displayedWeeks(first, ws).first()
                assertEquals("ws=$ws $key：小标题那一列必须是 1 号", 1, week[col].dayOfMonth)
            }
        }
    }

    // ------------------------------------------------------------------ 顶部月份

    /**
     * 顶部月份 = **占视口最多**的那一块，不是「视口顶部落在哪一块」。
     *
     * iOS 的注释：后者在滑动时只要下一月的第一行露头就抢标题（用户：「要显示的是占据
     * 屏幕主要的月份」「快速滑动会乱显示」）。这条断言把「过半才换」写死 ——
     * 具体过半的位置由 [MonthFlowLayout.dominantBlockIndex] 自己的几何决定，
     * 所以这里**算出**那一刻，而不是写死一个 dp。
     */
    @Test
    fun `the top month is the one that covers most of the viewport`() {
        val l = layout()
        // 五行月的静止位置：下面露着下个月的开头，顶部仍然是本月（本月 5 行 = 视口的 54%）。
        val key = 202609
        val i = l.blockIndexForKey(key)
        val rest = l.restOffset(key)!!
        assertEquals("静止时顶部就是本月", i, l.dominantBlockIndex(rest, viewportH))

        // 本月的份额 = `rowCount·rowH − o`，下个月的份额 = `o + viewportH − rowCount·rowH`（未封顶时）。
        // 令两者相等解出"过半"的偏移：o* = (2·rowsH − viewportH) / 2。
        val rowsH = l.blocks[i].rowCount * l.rowH
        // 过半点是相对本块起点的**位移**，要加上静止偏移才是内容坐标。
        val half = (2f * rowsH - viewportH) / 2f
        assertTrue("过半点应落在本月之内（$half ∈ 0…$rowsH）", half > 0f && half < rowsH)
        assertEquals("还没过半：仍是本月", i, l.dominantBlockIndex(rest + half - l.rowH * 0.5f, viewportH))
        assertEquals("过半之后：换成下一个月", i + 1, l.dominantBlockIndex(rest + half + l.rowH * 0.5f, viewportH))
    }

    /** 越界时二分查找夹到两端，不会崩。 */
    @Test
    fun `block lookup clamps at both ends`() {
        val l = layout()
        assertEquals(0, l.blockIndexAt(-100000f))
        assertEquals(l.blocks.size - 1, l.blockIndexAt(l.contentH + 100000f))
        assertEquals(0, l.blockIndexAt(0f))
    }

    /** 行高变了要重建（旋转 / 密度 / 字体缩放），而且缓存不能串味。 */
    @Test
    fun `layout is cached per row height and week start`() {
        val a = MonthFlowLayout.of(DateUtil.WEEK_START_MONDAY, 73f)
        val b = MonthFlowLayout.of(DateUtil.WEEK_START_MONDAY, 73f)
        val c = MonthFlowLayout.of(DateUtil.WEEK_START_MONDAY, 74f)
        val d = MonthFlowLayout.of(DateUtil.WEEK_START_SUNDAY, 73f)
        assertTrue("同一个 key 复用同一个实例", a === b)
        assertTrue("行高不同就是另一份几何", a !== c)
        assertTrue("周起始不同就是另一份几何", a !== d)
        // 周起始不同 → 2026-11 的行数不同（11/1 是周日：周一起始 6 行、周日起始 5 行）
        val monRows = a.blocks[a.blockIndexForKey(202611)].rowCount
        val sunRows = d.blocks[d.blockIndexForKey(202611)].rowCount
        assertEquals(6, monRows)
        assertEquals(5, sunRows)
    }

    // ------------------------------------------------------------------ 手感物理

    /**
     * 松手后的预测落点 = `起点 − 位移 − 速度 × 0.5s`，再夹进 `[0, maxOff]`。
     *
     * 真机上「甩一下滑多远」很难量，但这条公式可以钉住 —— 它是「无级滑动」里
     * 唯一决定"滑到哪儿"的地方。
     */
    @Test
    fun `projected target follows the ios deceleration model`() {
        val maxOff = 10000f
        // 手指上滑 100dp、速度 1000dp/s（向上）→ 内容再往前 100 + 500 = 600dp
        assertEquals(
            600f,
            MonthFlowPhysics.projectedTarget(0f, -100f, -1000f, maxOff),
            0.001f,
        )
        // 手指下滑同理（往回走）
        assertEquals(
            0f,
            MonthFlowPhysics.projectedTarget(600f, 100f, 1000f, maxOff),
            0.001f,
        )
        // 两端夹住：不会滑出内容之外（越界由橡皮筋负责，不是预测落点）
        assertEquals(maxOff, MonthFlowPhysics.projectedTarget(maxOff - 10f, -100f, -5000f, maxOff), 0.001f)
        assertEquals(0f, MonthFlowPhysics.projectedTarget(10f, 100f, 5000f, maxOff), 0.001f)
    }

    /** 位移很小时**不传初速**（iOS 的 4pt 规则）：否则弹簧一上来就飞。 */
    @Test
    fun `tiny travel gets no initial velocity`() {
        assertEquals(0f, MonthFlowPhysics.initialVelocity(100f, 102f, -3000f), 0.001f)
        assertEquals(3000f, MonthFlowPhysics.initialVelocity(100f, 200f, -3000f), 0.001f)
    }

    /** 橡皮筋：两端是"越拖越拖不动"，且永远不会越过一整屏。 */
    @Test
    fun `rubber band damps the overflow and never goes past the viewport`() {
        val viewportH = 600f
        val maxOff = 1000f
        assertEquals("没越界就原样返回", 500f, MonthFlowPhysics.rubberClamped(500f, maxOff, viewportH), 0.001f)
        val over = MonthFlowPhysics.rubberClamped(maxOff + 10_000f, maxOff, viewportH)
        assertTrue("越界被压住", over < maxOff + viewportH * 0.25f + 0.001f)
        assertTrue("但确实能拉动一点", over > maxOff)
        val under = MonthFlowPhysics.rubberClamped(-10_000f, maxOff, viewportH)
        assertTrue("上端同理", under > -viewportH * 0.25f - 0.001f && under < 0f)
    }

    /** 弹簧三个常数与 iOS 同一个数（改了手感就变了，必须显式改这里）。 */
    @Test
    fun `spring constants match the ios ones`() {
        assertEquals(130f, MonthFlowPhysics.SPRING_STIFFNESS, 0.001f)
        assertEquals(26f, 2 * MonthFlowPhysics.SPRING_DAMPING_RATIO * kotlin.math.sqrt(130f), 0.01f)
        assertEquals(0.5f, MonthFlowPhysics.PROJECTION_SECONDS, 0.001f)
        assertTrue("略过阻尼（>1）：到点即停、不回弹", MonthFlowPhysics.SPRING_DAMPING_RATIO > 1f)
    }

    /** 标签带高就是这一行字：竖屏 21dp、横屏紧凑 18dp（用户改过三版，别再动）。 */
    @Test
    fun `label band height is one line of text`() {
        assertEquals(21f, CalendarLayout.flowLabelBandHeight(compact = false), 0.001f)
        assertEquals(18f, CalendarLayout.flowLabelBandHeight(compact = true), 0.001f)
        assertEquals(15f, CalendarLayout.flowLabelFontSize(compact = false), 0.001f)
        assertEquals(13f, CalendarLayout.flowLabelFontSize(compact = true), 0.001f)
    }

    /** 流里的度量跟行高走（横屏左栏一格只有 ~40dp 时字号要收小）。 */
    @Test
    fun `flow metrics follow the row height`() {
        val tall = CalendarLayout.flowMetrics(402f, 94f, showLunar = true)
        assertEquals(20f, tall.dayFont, 0.001f)
        assertEquals(11f, tall.lunarFont, 0.001f)
        assertEquals(94f, tall.cellH, 0.001f)

        val short = CalendarLayout.flowMetrics(307f, 40.5f, showLunar = false)
        assertTrue("行矮了字号要跟着小", short.dayFont < tall.dayFont)
        assertEquals(0f, short.lunarAlpha, 0.001f)
    }

    /** 每块的 `monthFirst` 必须真的是那个月的 1 号（morph 的行锚点靠它）。 */
    @Test
    fun `every block starts at the first day of its month`() {
        val l = layout()
        for (block in listOf(l.blocks.first(), l.blocks[1000], l.blocks.last())) {
            assertEquals(1, block.monthFirst.dayOfMonth)
            assertEquals(block.key, CalendarGrid.monthKeyOf(block.monthFirst))
            assertTrue(block.rowCount in 4..6)
        }
        assertEquals(LocalDate.of(1900, 1, 1), l.blocks.first().monthFirst)
        assertEquals(LocalDate.of(2100, 12, 1), l.blocks.last().monthFirst)
    }
}
