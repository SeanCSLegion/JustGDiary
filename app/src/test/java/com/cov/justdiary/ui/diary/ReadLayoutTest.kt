package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.TextRun
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 阅读渲染模型与在篇内搜索的测试。
 *
 * 图片：**宽 = 正文列宽**（规范 `docs/android-design/04-diary.md` §2.6），
 * 高 = `max(40, ratio × 宽)`，缺 `w/h` 时按 0.75 兜底。
 * iOS 的 `min(宽 − 24, 343)` 是旧版列宽下的规则，v3 已去掉那个 343 上限。
 *
 * 搜索：计数与高亮**共用** `DiarySearch.findMatches`（规范 §2.7），
 * 列表 / 待办逐项成段，命中不会跨项。
 */
class ReadLayoutTest {

    @Test
    fun `image display width is the text column width with a 60dp floor`() {
        // 660 的列宽 − 卡片内边距 12×2 = 636 的正文宽：图就用它，没有 343 那种上限
        assertEquals(636.0, ReadLayout.imageDisplayWidth(636.0), 1e-6)
        assertEquals(1000.0, ReadLayout.imageDisplayWidth(1000.0), 1e-6)
        assertEquals(ReadLayout.IMAGE_MIN_WIDTH, ReadLayout.imageDisplayWidth(20.0), 1e-6)
        assertTrue("可用宽极小时也不能变成 0 或负数", ReadLayout.imageDisplayWidth(0.0) > 0)
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
        // 普通段落用 -1（整段），待办用项下标
        assertEquals(-1, matches[0].itemIndex)
        assertEquals(0, matches[1].itemIndex)
    }

    @Test
    fun `list items are searched per item so a match never spans two items`() {
        // 拼接后是 "甲乙丙"，跨项搜索会得到 "乙丙" 这种既数得到、又画不出的命中
        val parts = listOf(
            ContentPart(style = ContentPartStyle.list, items = listOf("甲", "乙", "丙")),
        )
        assertTrue(DiarySearch.findMatches(parts, "乙丙").isEmpty())
        val matches = DiarySearch.findMatches(parts, "丙")
        assertEquals(1, matches.size)
        assertEquals(2, matches.single().itemIndex)
        assertEquals(0, matches.single().start)
        assertEquals(1, matches.single().end)
    }

    @Test
    fun `highlight ranges are derived from the very same matches`() {
        val parts = listOf(
            ContentPart(style = ContentPartStyle.body, text = "深圳的天气，深圳的人"),
            ContentPart(style = ContentPartStyle.todo, items = listOf("去深圳", "在家")),
        )
        val matches = DiarySearch.findMatches(parts, "深圳")
        assertEquals(3, matches.size)

        // 段落：两个命中，都不是"当前命中"
        assertEquals(
            listOf(0 until 2, 6 until 8),
            DiarySearch.highlights(matches, partIndex = 0, itemIndex = -1).map { it.range },
        )
        assertTrue(
            "没给当前命中时，任何一处都不该被标成当前",
            DiarySearch.highlights(matches, 0, -1).none { it.current },
        )
        // 第二处命中标成"当前"：只有它 current = true（同一个区间，样式不同）
        val second = matches[1]
        assertEquals(
            listOf(false, true),
            DiarySearch.highlights(matches, 0, -1, current = second).map { it.current },
        )
        // 待办第一项：一个命中；第二项：没有
        assertEquals(listOf(1 until 3), DiarySearch.highlights(matches, 1, 0).map { it.range })
        assertTrue(DiarySearch.highlights(matches, 1, 1).isEmpty())
        // 数出来的命中总数 == 各单元区间数之和（一套算法的直接结果）
        val drawn = (0 until 2).sumOf { part -> DiarySearch.highlights(matches, part, -1).size } +
            (0 until 2).sumOf { item -> DiarySearch.highlights(matches, 1, item).size }
        assertEquals(matches.size, drawn)
    }

    @Test
    fun `empty keyword yields no matches`() {
        val parts = listOf(ContentPart(style = ContentPartStyle.body, text = "正文"))
        assertTrue(DiarySearch.findMatches(parts, "").isEmpty())
        assertTrue(DiarySearch.findMatches(parts, "   ").isEmpty())
    }

    // ------------------------------------------------------------------ 命中定位的下标

    @Test
    fun `scroll index counts the hero card`() {
        // 列表顺序：Hero → 卡片…（搜索栏是悬浮的，不进列表）
        assertEquals(1, DiaryScroll.LEADING_ITEMS)
        assertEquals(1, DiaryScroll.itemIndexForBlock(0))
        // 第 2 张卡 = 下标 3 —— 少算 Hero 那一项就会滚到相邻的卡上（真机实测过）
        assertEquals(3, DiaryScroll.itemIndexForBlock(2))
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
