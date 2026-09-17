package com.cov.justdiary.data

import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享长图版式测试。
 *
 * 常量逐项对齐 iOS `ImageShareService.swift:10-17`（方案 §8.3 要求"常量必须逐项对齐，
 * 否则导出样式漂移"）。量宽用 [ShareLayout.InkRatioWidthProvider]，
 * 因此换行结果是确定的，可以精确断言。
 */
class ShareLayoutTest {

    private val ink = ShareLayout.InkRatioWidthProvider

    @Test
    fun `canvas constants match the ios renderer`() {
        assertEquals(720.0, ShareLayout.WIDTH, 1e-6)
        assertEquals(108.0, ShareLayout.BLOCK_LEFT, 1e-6)
        assertEquals(48.0, ShareLayout.SIDE, 1e-6)
        assertEquals(61.0, ShareLayout.TIMELINE_LINE_X, 1e-6)
        assertEquals(3.0, ShareLayout.TIMELINE_LINE_W, 1e-6)
        assertEquals(64.0, ShareLayout.DATE_Y, 1e-6)
        assertEquals(34.0, ShareLayout.FOOTER_H, 1e-6)
        // contentWidth = 720 - 108 - 48
        assertEquals(564.0, ShareLayout.CONTENT_WIDTH, 1e-6)
    }

    @Test
    fun `style metrics match the ios fonts and line heights`() {
        assertEquals(36.0, ShareLayout.metricsFor(ContentPartStyle.title).fontSize, 1e-6)
        assertEquals(52.0, ShareLayout.metricsFor(ContentPartStyle.title).lineHeight, 1e-6)
        assertEquals(32.0, ShareLayout.metricsFor(ContentPartStyle.heading).fontSize, 1e-6)
        assertEquals(46.0, ShareLayout.metricsFor(ContentPartStyle.heading).lineHeight, 1e-6)
        assertEquals(30.0, ShareLayout.metricsFor(ContentPartStyle.quote).fontSize, 1e-6)
        assertEquals(40.0, ShareLayout.metricsFor(ContentPartStyle.quote).lineHeight, 1e-6)
        assertEquals(30.0, ShareLayout.metricsFor(ContentPartStyle.list).fontSize, 1e-6)
        assertEquals(55.0, ShareLayout.metricsFor(ContentPartStyle.todo).lineHeight, 1e-6)
        assertEquals(31.0, ShareLayout.metricsFor(ContentPartStyle.body).fontSize, 1e-6)
        assertEquals(50.0, ShareLayout.metricsFor(ContentPartStyle.body).lineHeight, 1e-6)
    }

    @Test
    fun `ink ratios match the ios values`() {
        assertEquals(0.87, ShareLayout.inkRatio('深'), 1e-6)
        assertEquals(0.87, ShareLayout.inkRatio('。'), 1e-6)
        assertEquals(0.75, ShareLayout.inkRatio('a'), 1e-6)
        assertEquals(0.75, ShareLayout.inkRatio('7'), 1e-6)
        assertEquals(0.98, ShareLayout.inkRatio('—'), 1e-6)
    }

    @Test
    fun `single line part heights match the ios rects`() {
        val title = ContentPart(style = ContentPartStyle.title, text = "短标题")
        assertEquals(52.0 + 12.0, ShareLayout.partRect(title, 0.0).height, 1e-6)

        val heading = ContentPart(style = ContentPartStyle.heading, text = "小标题")
        assertEquals(46.0 + 10.0, ShareLayout.partRect(heading, 0.0).height, 1e-6)

        val body = ContentPart(style = ContentPartStyle.body, text = "一行正文")
        assertEquals(50.0 + 10.0, ShareLayout.partRect(body, 0.0).height, 1e-6)

        val list = ContentPart(style = ContentPartStyle.list, items = listOf("甲", "乙"))
        assertEquals(2 * 55.0, ShareLayout.partRect(list, 0.0).height, 1e-6)

        val todo = ContentPart(style = ContentPartStyle.todo, items = listOf("做", "没做", "再来"))
        assertEquals(3 * 55.0, ShareLayout.partRect(todo, 0.0).height, 1e-6)
    }

    /** D6：长标题按实际行数增长，不再固定 64 而溢出。 */
    @Test
    fun `long titles grow with the number of wrapped lines`() {
        val longTitle = ContentPart(style = ContentPartStyle.title, text = "深".repeat(40))
        val height = ShareLayout.partRect(longTitle, 0.0).height
        // 40 个 CJK 字 × (0.87 × 36 = 31.32) = 1252.8 → 564 宽下 3 行
        assertTrue("长标题应换行而不是溢出：$height", height > 52.0 * 2)
        assertEquals(3 * 52.0 + 12.0, height, 1e-6)
    }

    @Test
    fun `image height uses the stored ratio with a floor`() {
        val wide = ContentPart(style = ContentPartStyle.image, src = "images/a.jpg", w = 1000.0, h = 500.0)
        assertEquals(564.0 * 0.5, ShareLayout.imageHeight(wide), 1e-6)

        // 缺 w/h → 0.75 兜底，且不小于 120
        val unknown = ContentPart(style = ContentPartStyle.image, src = "images/a.jpg")
        assertEquals(0.75 * 564.0, ShareLayout.imageHeight(unknown), 1e-6)

        // 极扁的图被 120 托住
        val flat = ContentPart(style = ContentPartStyle.image, src = "images/a.jpg", w = 1000.0, h = 50.0)
        assertEquals(ShareLayout.IMAGE_MIN_HEIGHT, ShareLayout.imageHeight(flat), 1e-6)
    }

    @Test
    fun `quote height includes the ink of the tallest line plus padding`() {
        val quote = ContentPart(style = ContentPartStyle.quote, text = "水光潋滟晴方好")
        val height = ShareLayout.partRect(quote, 0.0).height
        // 单行：max(1, 1-1) = 1 → 1×40 + 0.87×30 + 40
        // 注意 `max(1, …)` 让**单行引用也多留一行高** —— 这是 iOS 的原样行为，不是笔误。
        assertEquals(40.0 + 0.87 * 30.0 + 40.0, height, 1e-6)
    }

    @Test
    fun `greedy wrapping breaks only when the next character would overflow`() {
        // 字号 30、CJK 墨迹 0.87 → 每字 26.1；30 字 = 783 → 564 宽下 2 行（21 + 9）
        val text = "字".repeat(30)
        val lines = ShareLayout.wrappedLines(text, 30.0, 564.0, ink)
        assertEquals(2, lines.size)
        assertEquals(21, lines[0].length)
        assertEquals(9, lines[1].length)

        // 恰好放得下时不换行
        assertEquals(1, ShareLayout.wrappedLines("字".repeat(21), 30.0, 564.0, ink).size)
    }

    @Test
    fun `wrapping never produces an empty first line`() {
        // 单字比整行还宽时，也要把它放进第一行而不是留下空行
        val lines = ShareLayout.wrappedLines("字".repeat(3), 30.0, 10.0, ink)
        assertEquals(3, lines.size)
        assertTrue(lines.all { it.isNotEmpty() })
    }

    /** D5：地点行按实际换行行数前进，避免与下一块重叠。 */
    @Test
    fun `multi line location advances by its real line count`() {
        val block = ShareLayout.ShareBlock(
            time = 1_760_000_000_000,
            location = "南山区 · 深圳市 · 广东省 · 中国 · ".repeat(3),
            parts = listOf(ContentPart(style = ContentPartStyle.body, text = "正文")),
        )
        val layout = ShareLayout.layout("2026年9月15日", "21:00 开始写", listOf(block), ink)

        val locationLines = ShareLayout.wrappedLines(
            block.location,
            ShareLayout.LOCATION_FONT,
            ShareLayout.CONTENT_WIDTH,
            ink,
        )
        assertTrue("这个地点串必须换行才说明测试有效", locationLines.size > 1)

        val expectedBlockHeight = ShareLayout.TIME_ADVANCE + ShareLayout.TIME_GAP +
            locationLines.size * ShareLayout.LOCATION_LINE_HEIGHT +
            ShareLayout.partRect(block.parts[0], 0.0).height +
            ShareLayout.BLOCK_SPACING
        assertEquals(expectedBlockHeight, layout.blockRects[0].height, 1e-6)
        assertTrue(
            "固定按 34 前进会低估块高",
            layout.blockRects[0].height > ShareLayout.TIME_ADVANCE + ShareLayout.TIME_GAP +
                ShareLayout.LOCATION_LINE_HEIGHT,
        )
    }

    @Test
    fun `total height follows the ios cursor arithmetic`() {
        val parts = listOf(
            ContentPart(style = ContentPartStyle.title, text = "标题"),
            ContentPart(style = ContentPartStyle.body, text = "正文"),
        )
        val block = ShareLayout.ShareBlock(time = 1L, location = "深圳市", parts = parts)
        val startLine = "21:00 开始写"
        val layout = ShareLayout.layout("2026年9月15日", startLine, listOf(block), ink)

        val startLineLines = ShareLayout.wrappedLines(startLine, ShareLayout.START_LINE_FONT, ShareLayout.CONTENT_WIDTH, ink)
        assertEquals(1, startLineLines.size)

        val expected = ShareLayout.DATE_Y +
            ShareLayout.DATE_ADVANCE +
            ShareLayout.START_LINE_HEIGHT +
            ShareLayout.HAIRLINE_ADVANCE +
            (ShareLayout.TIME_ADVANCE + ShareLayout.TIME_GAP) +
            ShareLayout.LOCATION_LINE_HEIGHT +
            ShareLayout.partRect(parts[0], 0.0).height +
            ShareLayout.partRect(parts[1], 0.0).height +
            ShareLayout.BLOCK_SPACING +
            ShareLayout.FOOTER_H
        assertEquals(expected, layout.height, 1e-6)
    }

    @Test
    fun `empty day still lays out the header and footer`() {
        val layout = ShareLayout.layout("2026年9月15日", "", emptyList(), ink)
        assertEquals(
            ShareLayout.DATE_Y + ShareLayout.DATE_ADVANCE + ShareLayout.START_LINE_HEIGHT +
                ShareLayout.HAIRLINE_ADVANCE + ShareLayout.FOOTER_H,
            layout.height,
            1e-6,
        )
        assertTrue(layout.blockRects.isEmpty())
        assertTrue(layout.timelineBottom >= layout.timelineTop)
    }

    @Test
    fun `part rects are positioned by the running cursor`() {
        val parts = listOf(
            ContentPart(style = ContentPartStyle.title, text = "标题"),
            ContentPart(style = ContentPartStyle.body, text = "正文"),
        )
        val block = ShareLayout.ShareBlock(time = 1L, location = "", parts = parts)
        val layout = ShareLayout.layout("d", "", listOf(block), ink)

        val first = layout.partRects[0][0]
        val second = layout.partRects[0][1]
        assertEquals(first.y + first.height, second.y, 1e-6)
        assertTrue(second.y > first.y)
    }
}
