package com.cov.justdiary.data

import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分享长图版式测试（对齐 iOS `Services/ImageShareService.swift` 的**重做版**）。
 *
 * 常量逐项对齐 `ImageShareService.swift:28-77`（方案 §8.3 要求"常量必须逐项对齐，
 * 否则导出样式漂移"）。量宽用 [ShareLayout.InkRatioFontMetrics]，
 * 因此换行与高度是确定的，可以精确断言。
 *
 * 覆盖 iOS `ShareRendererTests` 守的四件事（不比对像素）：
 * 各种块样式都画得出来、尺寸跟着内容走、`auto_time` 关闭时图会变矮、
 * 空白的一天不会画出空卡片。
 */
class ShareLayoutTest {

    private val metrics = ShareLayout.InkRatioFontMetrics
    private val palette = ShareLayout.Palette.light()

    private fun texts(startLine: String? = "8:40 开始写") = ShareLayout.ShareTexts(
        brand = "一页时光",
        year = "2026年",
        date = "9月8日",
        weekday = "周二",
        startLine = startLine,
        footerBrand = "一页时光 · JustADiary",
        dayKey = "2026-09-08",
    )

    private fun page(
        blocks: List<ShareLayout.ShareBlock>,
        showTime: Boolean = true,
    ) = ShareLayout.makePage(
        texts = texts(startLine = if (showTime) "8:40 开始写" else null),
        blocks = blocks,
        showTime = showTime,
        palette = palette,
        metrics = metrics,
        timeLabel = { "8:40" },
    )

    // ------------------------------------------------------------------ 常量

    @Test
    fun `canvas constants match the ios renderer`() {
        assertEquals(720.0, ShareLayout.WIDTH, 1e-6)
        assertEquals(56.0, ShareLayout.MARGIN, 1e-6)
        assertEquals(608.0, ShareLayout.CONTENT, 1e-6)
        assertEquals(56.0, ShareLayout.TOP, 1e-6)
        assertEquals(64.0, ShareLayout.RULE_WIDTH, 1e-6)
        assertEquals(5.0, ShareLayout.RULE_HEIGHT, 1e-6)
        assertEquals(26.0, ShareLayout.CARD_RADIUS, 1e-6)
        assertEquals(30.0, ShareLayout.CARD_PAD_H, 1e-6)
        assertEquals(28.0, ShareLayout.CARD_PAD_V, 1e-6)
        assertEquals(30.0, ShareLayout.BETWEEN_ENTRIES, 1e-6)
        assertEquals(20.0, ShareLayout.PART_GAP, 1e-6)
        assertEquals(26.0, ShareLayout.LOOSE_GAP, 1e-6)
        assertEquals(46.0, ShareLayout.BEFORE_FOOTER, 1e-6)
        assertEquals(30.0, ShareLayout.FOOTER_H, 1e-6)
        assertEquals(48.0, ShareLayout.BOTTOM, 1e-6)
    }

    @Test
    fun `fonts and line heights match the ios renderer`() {
        assertEquals(26.0, ShareLayout.BRAND_FONT, 1e-6)
        assertEquals(22.0, ShareLayout.YEAR_FONT, 1e-6)
        assertEquals(58.0, ShareLayout.DATE_FONT, 1e-6)
        assertEquals(28.0, ShareLayout.WEEKDAY_FONT, 1e-6)
        assertEquals(25.0, ShareLayout.META_FONT, 1e-6)
        assertEquals(27.0, ShareLayout.TIME_FONT, 1e-6)
        assertEquals(23.0, ShareLayout.LOC_FONT, 1e-6)
        assertEquals(38.0, ShareLayout.TITLE_FONT, 1e-6)
        assertEquals(54.0, ShareLayout.TITLE_LH, 1e-6)
        assertEquals(32.0, ShareLayout.HEADING_FONT, 1e-6)
        assertEquals(46.0, ShareLayout.HEADING_LH, 1e-6)
        assertEquals(29.0, ShareLayout.BODY_FONT, 1e-6)
        assertEquals(48.0, ShareLayout.BODY_LH, 1e-6)
        assertEquals(27.0, ShareLayout.QUOTE_FONT, 1e-6)
        assertEquals(44.0, ShareLayout.QUOTE_LH, 1e-6)
        assertEquals(29.0, ShareLayout.ITEM_FONT, 1e-6)
        assertEquals(46.0, ShareLayout.ITEM_LH, 1e-6)
        assertEquals(22.0, ShareLayout.FOOTER_FONT, 1e-6)
        assertEquals(30.0, ShareLayout.FOOTER_LH, 1e-6)
    }

    @Test
    fun `palette matches the ios colors`() {
        val light = ShareLayout.Palette.light()
        assertEquals(0xFF15181DL, light.text)
        assertEquals(0xFF5B6472L, light.sub)
        assertEquals(0xFF98A1B0L, light.faint)
        assertEquals(0xFF2563EBL, light.accent)
        assertEquals(0xFFF8F9FCL, light.bgTop)
        assertEquals(0xFFEDF0F7L, light.bgBottom)
        assertEquals(0xFFFFFFFFL, light.card)
        assertEquals(0.06, light.cardBorderAlpha, 1e-6)
        assertEquals(0.07, light.quoteBgAlpha, 1e-6)

        val dark = ShareLayout.Palette.dark()
        assertEquals(0xFFECEDF1L, dark.text)
        assertEquals(0xFFA7AEBBL, dark.sub)
        assertEquals(0xFF7C97FFL, dark.accent)
        assertEquals(0xFF0C0F14L, dark.bgTop)
        assertEquals(0xFF131722L, dark.bgBottom)
        assertEquals(0xFF1A1F29L, dark.card)
        assertEquals(0.14, dark.quoteBgAlpha, 1e-6)
    }

    // ------------------------------------------------------------------ 纵向结构

    @Test
    fun `an empty day is header plus footer only`() {
        val page = page(blocks = emptyList())
        // 只有页眉 + 页脚：高度落在 iOS 测试守的 200~420 区间
        assertTrue("高 ${page.height} 应该 > 200", page.height > 200)
        assertTrue("高 ${page.height} 应该 < 420", page.height < 420)
        // 不能画出任何卡片
        assertTrue(page.ops.none { it is ShareLayout.Op.Card })
    }

    @Test
    fun `each block draws a card behind its content`() {
        val page = page(
            blocks = listOf(
                ShareLayout.ShareBlock(
                    time = 1_788_700_000_000,
                    location = "南山区 · 深圳市 · 广东省 · 中国",
                    parts = listOf(ContentPart(style = ContentPartStyle.body, text = "正文一段")),
                ),
            ),
        )
        val cards = page.ops.filterIsInstance<ShareLayout.Op.Card>()
        assertEquals(1, cards.size)
        val card = cards.single()
        assertEquals(ShareLayout.MARGIN, card.rect.x, 1e-6)
        assertEquals(ShareLayout.CONTENT, card.rect.width, 1e-6)
        assertEquals(26.0, card.radius, 1e-6)
        // 卡片必须**排在**它的内容之前（绘制顺序 = 先卡后面）：iOS 是把卡片指令 insert 到内容之前
        val cardIndex = page.ops.indexOf(card)
        val bodyIndex = page.ops.indexOfFirst {
            it is ShareLayout.Op.Text && it.text == "正文一段"
        }
        assertTrue("卡片要画在内容之下（先前绘制）", cardIndex < bodyIndex)
    }

    @Test
    fun `the header carries brand, date, weekday, meta and the accent rule`() {
        val page = page(
            blocks = listOf(
                ShareLayout.ShareBlock(time = 1_788_700_000_000, location = "", parts = emptyList()),
            ),
        )
        val texts = page.ops.filterIsInstance<ShareLayout.Op.Text>().map { it.text }
        assertTrue(texts.contains("一页时光"))
        assertTrue(texts.contains("2026年"))
        assertTrue(texts.contains("9月8日"))
        assertTrue(texts.contains("周二"))
        assertTrue("头部第二行只有时间", texts.contains("8:40 开始写"))
        // 品牌短线：64 × 5 的品牌色圆角条
        val rule = page.ops.filterIsInstance<ShareLayout.Op.FillRounded>()
            .first { it.rect.height == ShareLayout.RULE_HEIGHT }
        assertEquals(64.0, rule.rect.width, 1e-6)
        assertEquals(2.5, rule.radius, 1e-6)
        assertEquals(palette.accent, rule.color)
        // 页脚：品牌 + 当天 dayKey
        assertTrue(texts.contains("一页时光 · JustADiary"))
        assertTrue(texts.contains("2026-09-08"))
    }

    @Test
    fun `hiding time removes the meta line and entry times`() {
        val block = ShareLayout.ShareBlock(
            time = 1_788_700_000_000,
            location = "南山区 · 深圳市",
            parts = listOf(ContentPart(style = ContentPartStyle.body, text = "正文")),
        )
        val shown = page(blocks = listOf(block), showTime = true)
        val hidden = page(blocks = listOf(block), showTime = false)

        assertTrue("关掉时间后图要变矮", hidden.height < shown.height)
        val hiddenTexts = hidden.ops.filterIsInstance<ShareLayout.Op.Text>().map { it.text }
        assertTrue("头部起始行不画", hiddenTexts.none { it.contains("开始写") })
        assertTrue("每条记录的时间行不画", hiddenTexts.none { it == "8:40" })
        assertTrue("地点还在", hiddenTexts.any { it == "南山区 · 深圳市" })
    }

    @Test
    fun `a block with neither content nor time nor location is skipped entirely`() {
        val empty = ShareLayout.ShareBlock(time = 0, location = "  ", parts = emptyList())
        val real = ShareLayout.ShareBlock(
            time = 1_788_700_000_000,
            location = "",
            parts = listOf(ContentPart(style = ContentPartStyle.body, text = "有内容")),
        )
        val onlyReal = page(blocks = listOf(real))
        val withEmpty = page(blocks = listOf(empty, real))
        assertEquals("空条目不该改变高度", onlyReal.height, withEmpty.height, 1e-6)
    }

    // ------------------------------------------------------------------ 每种段落的绘制

    @Test
    fun `every block style produces ops`() {
        val parts = listOf(
            ContentPart(style = ContentPartStyle.title, text = "大标题"),
            ContentPart(style = ContentPartStyle.heading, text = "小标题"),
            ContentPart(style = ContentPartStyle.body, text = "正文"),
            ContentPart(style = ContentPartStyle.quote, text = "引用"),
            ContentPart(style = ContentPartStyle.list, items = listOf("一", "二")),
            ContentPart(style = ContentPartStyle.todo, items = listOf("甲", "乙"), done = listOf(true, false)),
            ContentPart(style = ContentPartStyle.image, src = "images/a.png", w = 1200.0, h = 800.0),
        )
        val ops = mutableListOf<ShareLayout.Op>()
        var y = 0.0
        val width = ShareLayout.CONTENT - ShareLayout.CARD_PAD_H * 2
        parts.forEachIndexed { index, part ->
            if (index > 0) y += ShareLayout.gapBefore(part)
            y += ShareLayout.appendPart(
                part, ShareLayout.MARGIN + ShareLayout.CARD_PAD_H, y, width, ops, palette, metrics,
            )
        }
        val kinds = ops.map { it::class.simpleName }.toSet()
        assertTrue("要有文字指令", kinds.contains("Text"))
        assertTrue("引用要有底色块", kinds.contains("FillRounded"))
        assertTrue("引用要有左侧色条", kinds.contains("Bar"))
        assertTrue("列表要有圆点", kinds.contains("Dot"))
        assertTrue("待办要有复选框", kinds.contains("Checkbox"))
        assertTrue("图片要有图片指令", kinds.contains("Image"))
        assertTrue("整段内容应该撑出高度", y > 300)
    }

    @Test
    fun `quote draws a background box and a left bar`() {
        val ops = mutableListOf<ShareLayout.Op>()
        val h = ShareLayout.appendPart(
            part = ContentPart(style = ContentPartStyle.quote, text = "引用一段"),
            x = 86.0, y = 0.0, width = 548.0,
            ops = ops, palette = palette, metrics = metrics,
        )
        val box = ops.filterIsInstance<ShareLayout.Op.FillRounded>().single()
        assertEquals(ShareLayout.QUOTE_RADIUS, box.radius, 1e-6)
        assertEquals(palette.quoteBg, box.color)
        assertEquals(palette.quoteBgAlpha, box.alpha, 1e-6)
        val bar = ops.filterIsInstance<ShareLayout.Op.Bar>().single()
        assertEquals(5.0, bar.rect.width, 1e-6)
        assertEquals(palette.accent, bar.color)
        // 色条上下各内缩 10
        assertEquals(10.0, bar.rect.y - box.rect.y, 1e-6)
        assertEquals(10.0, box.rect.bottom - bar.rect.bottom, 1e-6)
        assertEquals(h, box.rect.height, 1e-6)
    }

    @Test
    fun `todo items use a checkbox and dim the done ones`() {
        val ops = mutableListOf<ShareLayout.Op>()
        ShareLayout.appendPart(
            part = ContentPart(
                style = ContentPartStyle.todo,
                items = listOf("已完成", "没做"),
                done = listOf(true, false),
            ),
            x = 86.0, y = 0.0, width = 548.0,
            ops = ops, palette = palette, metrics = metrics,
        )
        val boxes = ops.filterIsInstance<ShareLayout.Op.Checkbox>()
        assertEquals(2, boxes.size)
        assertTrue(boxes[0].checked)
        assertTrue(!boxes[1].checked)
        assertEquals(ShareLayout.CHECKBOX_SIZE, boxes[0].rect.width, 1e-6)
        val texts = ops.filterIsInstance<ShareLayout.Op.Text>()
        assertEquals("已完成项用 sub 色", palette.sub, texts[0].color)
        assertEquals("未完成项用正文色", palette.text, texts[1].color)
        // 待办的标记列宽是 48（复选框 30 + 间隙）
        assertEquals(86.0 + ShareLayout.TODO_MARKER_W, texts[0].rect.x, 1e-6)
    }

    @Test
    fun `list items use an accent dot and a narrower marker column`() {
        val ops = mutableListOf<ShareLayout.Op>()
        ShareLayout.appendPart(
            part = ContentPart(style = ContentPartStyle.list, items = listOf("一项")),
            x = 86.0, y = 0.0, width = 548.0,
            ops = ops, palette = palette, metrics = metrics,
        )
        val dot = ops.filterIsInstance<ShareLayout.Op.Dot>().single()
        assertEquals(palette.accent, dot.color)
        assertEquals(5.5, dot.radius, 1e-6)
        val text = ops.filterIsInstance<ShareLayout.Op.Text>().single()
        assertEquals(86.0 + ShareLayout.LIST_MARKER_W, text.rect.x, 1e-6)
    }

    @Test
    fun `image height follows the stored ratio and never goes below the minimum`() {
        val wide = ContentPart(style = ContentPartStyle.image, src = "a", w = 1000.0, h = 250.0)
        assertEquals(548.0 * 0.25, ShareLayout.imageHeight(wide, 548.0), 1e-6)
        val tall = ContentPart(style = ContentPartStyle.image, src = "a", w = 1000.0, h = 3000.0)
        assertEquals(548.0 * 3.0, ShareLayout.imageHeight(tall, 548.0), 1e-6)
        val unknown = ContentPart(style = ContentPartStyle.image, src = "a")
        // 缺省比例 0.75 → 411，高于最小高 120
        assertEquals(548.0 * 0.75, ShareLayout.imageHeight(unknown, 548.0), 1e-6)
        val tiny = ContentPart(style = ContentPartStyle.image, src = "a", w = 1000.0, h = 10.0)
        assertEquals(120.0, ShareLayout.imageHeight(tiny, 548.0), 1e-6)
        // 库里没有比例时问真实文件尺寸
        assertEquals(
            548.0 * 0.5,
            ShareLayout.imageHeight(unknown, 548.0, imageSize = { 800 to 400 }),
            1e-6,
        )
    }

    @Test
    fun `titles use the loose gap above them`() {
        assertEquals(
            ShareLayout.LOOSE_GAP,
            ShareLayout.gapBefore(ContentPart(style = ContentPartStyle.title, text = "x")),
            1e-6,
        )
        assertEquals(
            ShareLayout.LOOSE_GAP,
            ShareLayout.gapBefore(ContentPart(style = ContentPartStyle.heading, text = "x")),
            1e-6,
        )
        assertEquals(
            ShareLayout.PART_GAP,
            ShareLayout.gapBefore(ContentPart(style = ContentPartStyle.body, text = "x")),
            1e-6,
        )
    }

    // ------------------------------------------------------------------ 量算

    @Test
    fun `ink ratios match the ios values`() {
        assertEquals(0.87, ShareLayout.inkRatio('深'), 1e-6)
        assertEquals(0.87, ShareLayout.inkRatio('。'), 1e-6)
        assertEquals(0.75, ShareLayout.inkRatio('a'), 1e-6)
        assertEquals(0.75, ShareLayout.inkRatio('7'), 1e-6)
        assertEquals(0.98, ShareLayout.inkRatio('—'), 1e-6)
    }

    @Test
    fun `text height is at least one line`() {
        val oneLine = ShareLayout.textHeight("短", ShareLayout.BODY_FONT, 548.0, ShareLayout.BODY_LH, metrics)
        assertEquals(ShareLayout.BODY_LH, oneLine, 1e-6)
        // 空文本也占一行（iOS 的 textHeight 在空文本时早退返回 lineHeight）
        val empty = ShareLayout.textHeight("", ShareLayout.BODY_FONT, 548.0, ShareLayout.BODY_LH, metrics)
        assertEquals(ShareLayout.BODY_LH, empty, 1e-6)
    }

    @Test
    fun `wrapping is greedy and keeps every character`() {
        val text = "这一段是正文，用来验证换行位置。"
        val lines = ShareLayout.wrappedLines(text, ShareLayout.BODY_FONT, 200.0, metrics)
        assertTrue("应该折成多行", lines.size > 1)
        assertEquals("不能丢字", text, lines.joinToString(""))
    }

    @Test
    fun `a second entry adds head, card padding and one body line`() {
        fun block(minute: Int) = ShareLayout.ShareBlock(
            time = 1_788_700_000_000 + minute * 60_000L,
            location = "",
            parts = listOf(ContentPart(style = ContentPartStyle.body, text = "一句")),
        )
        val one = page(blocks = listOf(block(0)))
        val two = page(blocks = listOf(block(0), block(1)))
        val expected = ShareLayout.BETWEEN_ENTRIES + ShareLayout.ENTRY_HEAD_H + ShareLayout.AFTER_ENTRY_HEAD +
            ShareLayout.CARD_PAD_V * 2 + ShareLayout.BODY_LH
        assertEquals(expected, two.height - one.height, 1e-6)
    }
}
