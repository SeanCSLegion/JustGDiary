package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读渲染模型与在篇内搜索的测试。
 *
 * 图片尺寸两条规则（显示 `min(宽-24, 343)`、高 `max(40, ratio × 宽)`）来自
 * `MediaViews.swift:25` 与 `RichTextEngine.swift:677-680`；渲染模型对应
 * `ReadTextView` 用 `PartsCodec.attributedString` 建出来的东西。
 */
class ReadLayoutTest {

    @Test
    fun `image display width is capped by both the margin and the design maximum`() {
        assertEquals(343.0, ReadLayout.imageDisplayWidth(1000.0), 1e-6)
        assertEquals(343.0, ReadLayout.imageDisplayWidth(367.0), 1e-6)
        assertEquals(330.0, ReadLayout.imageDisplayWidth(354.0), 1e-6)
        assertEquals(24.0, ReadLayout.imageDisplayWidth(48.0), 1e-6)
        assertTrue("可用宽极小时也不能变成 0 或负数", ReadLayout.imageDisplayWidth(10.0) > 0)
    }

    @Test
    fun `image insert width follows the editor rule`() {
        assertEquals(343.0, ReadLayout.imageInsertWidth(800.0), 1e-6)
        assertEquals(320.0, ReadLayout.imageInsertWidth(344.0), 1e-6)
    }

    @Test
    fun `image height uses the stored ratio with a floor`() {
        val landscape = ContentPart(style = ContentPartStyle.image, src = "images/a.jpg", w = 1200.0, h = 600.0)
        assertEquals(0.5, ReadLayout.imageRatio(landscape), 1e-6)
        assertEquals(343.0 * 0.5, ReadLayout.imageDisplayHeight(landscape, 343.0), 1e-6)

        val tall = ContentPart(style = ContentPartStyle.image, src = "images/a.jpg", w = 600.0, h = 1200.0)
        assertEquals(2.0, ReadLayout.imageRatio(tall), 1e-6)
        assertEquals(686.0, ReadLayout.imageDisplayHeight(tall, 343.0), 1e-6)

        // 缺 w/h → 0.75 兜底
        val unknown = ContentPart(style = ContentPartStyle.image, src = "images/a.jpg")
        assertEquals(0.75, ReadLayout.imageRatio(unknown), 1e-6)

        // 极扁的图被 40 托住
        val flat = ContentPart(style = ContentPartStyle.image, src = "images/a.jpg", w = 2000.0, h = 40.0)
        assertEquals(ReadLayout.IMAGE_MIN_HEIGHT, ReadLayout.imageDisplayHeight(flat, 343.0), 1e-6)
    }

    @Test
    fun `render max pixel is three times the longest stored side`() {
        val part = ContentPart(style = ContentPartStyle.image, src = "i.jpg", w = 1200.0, h = 900.0)
        assertEquals(3600, ReadLayout.renderMaxPixel(part))
        assertNull("没有宽高时交给调用方用预览档", ReadLayout.renderMaxPixel(ContentPart(style = ContentPartStyle.image, src = "i.jpg")))
    }

    @Test
    fun `paragraphs keep runs alignment and styles`() {
        val parts = listOf(
            ContentPart(
                style = ContentPartStyle.body,
                runs = listOf(
                    TextRun(text = "普通"),
                    TextRun(text = "粗", bold = true),
                    TextRun(text = "斜", italic = true),
                    TextRun(text = "删", strike = true),
                    TextRun(text = "线", underline = true),
                ),
                align = "center",
            ),
        )
        val render = ReadLayout.renderModel(parts).single() as ReadLayout.PartRender.Paragraph
        assertEquals(ContentPartStyle.body, render.style)
        assertEquals("普通粗斜删线", render.text)
        assertTrue(render.alignCenter)
        assertEquals(5, render.spans.size)
        assertEquals(true, render.spans[1].bold)
        assertEquals(true, render.spans[2].italic)
        assertEquals(true, render.spans[3].strike)
        assertEquals(true, render.spans[4].underline)
        assertEquals(false, render.spans[0].bold)
    }

    @Test
    fun `text only paragraphs become a single span`() {
        val render = ReadLayout.renderModel(
            listOf(ContentPart(style = ContentPartStyle.quote, text = "引用")),
        ).single() as ReadLayout.PartRender.Paragraph
        assertEquals(1, render.spans.size)
        assertEquals("引用", render.text)
        assertEquals(false, render.alignCenter)
    }

    @Test
    fun `list and todo become items with done flags`() {
        val renders = ReadLayout.renderModel(
            listOf(
                ContentPart(style = ContentPartStyle.list, items = listOf("甲", "乙")),
                ContentPart(style = ContentPartStyle.todo, items = listOf("做", "没做"), done = listOf(true, false)),
            ),
        )
        val list = renders[0] as ReadLayout.PartRender.Items
        assertEquals(false, list.isTodo)
        assertEquals(listOf("甲", "乙"), list.items)
        val todo = renders[1] as ReadLayout.PartRender.Items
        assertEquals(true, todo.isTodo)
        assertEquals(listOf(true, false), todo.done)
    }

    @Test
    fun `images without a src and empty paragraphs are dropped`() {
        val renders = ReadLayout.renderModel(
            listOf(
                ContentPart(style = ContentPartStyle.image),
                ContentPart(style = ContentPartStyle.body),
                ContentPart(style = ContentPartStyle.list, items = emptyList()),
                ContentPart(style = ContentPartStyle.image, src = "images/a.jpg", w = 10.0, h = 20.0),
            ),
        )
        assertEquals(1, renders.size)
        val image = renders.single() as ReadLayout.PartRender.Image
        assertEquals("images/a.jpg", image.src)
        assertEquals(10.0, image.width!!, 1e-6)
    }

    @Test
    fun `highlight segments mark the keyword occurrences`() {
        val part = ContentPart(style = ContentPartStyle.body, text = "深圳的天气，深圳的人")
        val segments = ReadLayout.highlight(part, "深圳")
        val hits = segments.filter { it.hit }.map { it.text }
        assertEquals(listOf("深圳", "深圳"), hits)
        assertEquals(
            "深圳的天气，深圳的人",
            segments.joinToString(separator = "") { it.text },
        )
    }

    @Test
    fun `highlight falls back to one unhit segment without a keyword`() {
        val part = ContentPart(style = ContentPartStyle.body, text = "正文")
        assertEquals(1, ReadLayout.highlight(part, "").size)
        assertEquals(false, ReadLayout.highlight(part, "").single().hit)
    }

    // ------------------------------------------------------------------ 在篇内搜索

    @Test
    fun `matches are ordered by part and position`() {
        val parts = listOf(
            ContentPart(style = ContentPartStyle.body, text = "深圳的天气"),
            ContentPart(style = ContentPartStyle.body, text = "没有命中"),
            ContentPart(style = ContentPartStyle.body, text = "深圳湾在深圳"),
        )
        val matches = DiarySearch.findMatches(parts, "深圳")
        assertEquals(3, matches.size)
        assertEquals(0, matches[0].partIndex)
        assertEquals(0, matches[0].start)
        assertEquals(2, matches[0].end)
        assertEquals(2, matches[1].partIndex)
        assertEquals(0, matches[1].start)
        assertEquals(2, matches[2].partIndex)
        assertEquals(4, matches[2].start)
    }

    @Test
    fun `multiple terms in one part are merged`() {
        val parts = listOf(ContentPart(style = ContentPartStyle.body, text = "深圳 天气 深圳"))
        val matches = DiarySearch.findMatches(parts, "深圳 天气")
        // 三个区间：深圳[0,2)、天气[3,5)、深圳[6,8) → 深圳 与 天气 不相接，仍是 3 个
        assertEquals(3, matches.size)
        assertEquals(0, matches[0].start)
        assertEquals(3, matches[1].start)
        assertEquals(6, matches[2].start)
    }

    @Test
    fun `search is case insensitive and covers items`() {
        val parts = listOf(
            ContentPart(style = ContentPartStyle.body, text = "Hello World"),
            ContentPart(style = ContentPartStyle.todo, items = listOf("hello 一下")),
        )
        val matches = DiarySearch.findMatches(parts, "HELLO")
        assertEquals(2, matches.size)
        assertEquals(1, matches[1].partIndex)
    }

    @Test
    fun `empty keyword yields no matches`() {
        val parts = listOf(ContentPart(style = ContentPartStyle.body, text = "正文"))
        assertTrue(DiarySearch.findMatches(parts, "").isEmpty())
        assertTrue(DiarySearch.findMatches(parts, "   ").isEmpty())
    }

    @Test
    fun `next and previous wrap around the match list`() {
        // 0 → 1 → 2 → 0
        assertEquals(1, DiarySearch.nextIndex(0, 3))
        assertEquals(2, DiarySearch.nextIndex(1, 3))
        assertEquals(0, DiarySearch.nextIndex(2, 3))
        // 2 → 1 → 0 → 2
        assertEquals(1, DiarySearch.previousIndex(2, 3))
        assertEquals(0, DiarySearch.previousIndex(1, 3))
        assertEquals(2, DiarySearch.previousIndex(0, 3))
        // 没有匹配
        assertEquals(-1, DiarySearch.nextIndex(-1, 0))
        assertEquals(-1, DiarySearch.previousIndex(-1, 0))
        // 首个匹配
        assertEquals(0, DiarySearch.nextIndex(-1, 3))
    }
}
