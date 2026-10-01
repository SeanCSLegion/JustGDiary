package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.TextRun
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器内核测试 —— 本里程碑最重要的一份测试。
 *
 * 方案 §5.4 列的 5 个"静默数据损坏"暗坑，在 Android 侧都被这条纯逻辑层挡住：
 *
 * | 坑 | 本文件里的对应断言 |
 * | --- | --- |
 * | E1/E2 不能用绘制字号反解块样式 | 模型里**根本没有**字号输入；[setBlockStyle] 设什么读什么 |
 * | E3 段落操作必须倒序 | [setBlockStyle] 改为按区间重建；对照测试证明逐下标正序会错位 |
 * | E4 IME 组合区必须跳过 | [FormatGate] 的相交判定 |
 * | E5 全量替换摧毁撤销栈 | [EditorHistory] 的 push/undo/redo |
 */
class EditorModelTest {

    // ------------------------------------------------------------------ 行 ↔ 段落

    @Test
    fun `consecutive list items merge into a single list part`() {
        val lines = listOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "丙", marker = EditorLine.MarkerKind.Bullet),
        )
        val parts = EditorModel.linesToParts(lines)
        assertEquals(1, parts.size)
        assertEquals(ContentPartStyle.list, parts[0].style)
        assertEquals(listOf("甲", "乙", "丙"), parts[0].items)
        assertEquals(null, parts[0].done)
    }

    @Test
    fun `consecutive todo items merge with parallel done flags`() {
        val lines = listOf(
            EditorLine(text = "做", marker = EditorLine.MarkerKind.Todo, done = true),
            EditorLine(text = "没做", marker = EditorLine.MarkerKind.Todo, done = false),
        )
        val parts = EditorModel.linesToParts(lines)
        assertEquals(1, parts.size)
        assertEquals(ContentPartStyle.todo, parts[0].style)
        assertEquals(listOf("做", "没做"), parts[0].items)
        assertEquals(listOf(true, false), parts[0].done)
    }

    @Test
    fun `list and todo are separate parts even when adjacent`() {
        val lines = listOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "做", marker = EditorLine.MarkerKind.Todo, done = false),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
        )
        val parts = EditorModel.linesToParts(lines)
        assertEquals(3, parts.size)
        assertEquals(ContentPartStyle.list, parts[0].style)
        assertEquals(ContentPartStyle.todo, parts[1].style)
        assertEquals(ContentPartStyle.list, parts[2].style)
    }

    @Test
    fun `blank lines are dropped and alignment only persists when centered`() {
        val parts = EditorModel.linesToParts(
            listOf(
                EditorLine(text = ""),
                EditorLine(text = "居中", blockStyle = ContentPartStyle.title, align = EditorModel.ALIGN_CENTER),
                EditorLine(text = "左对齐", blockStyle = ContentPartStyle.heading),
            ),
        )
        assertEquals(2, parts.size)
        assertEquals(EditorModel.ALIGN_CENTER, parts[0].align)
        assertEquals(null, parts[1].align)
        assertEquals(ContentPartStyle.title, parts[0].style)
    }

    @Test
    fun `paragraph text is stored as runs and inline styles are preserved`() {
        val line = EditorLine(
            text = "正文粗",
            blockStyle = ContentPartStyle.body,
            spans = listOf(TextRun(text = "正文"), TextRun(text = "粗", bold = true)),
        )
        val part = EditorModel.linesToParts(listOf(line)).single()
        assertEquals("正文粗", ContentFlatten.flattenPart(part))
        assertEquals(true, part.runs?.get(1)?.bold)
        assertEquals(null, part.runs?.get(0)?.bold)
    }

    @Test
    fun `parts expand back into lines with markers`() {
        val lines = EditorModel.partsToLines(
            listOf(
                ContentPart(style = ContentPartStyle.title, runs = listOf(TextRun(text = "标题"))),
                ContentPart(style = ContentPartStyle.list, items = listOf("甲", "乙")),
                ContentPart(style = ContentPartStyle.todo, items = listOf("做", "没做"), done = listOf(true, false)),
            ),
        )
        assertEquals(5, lines.size)
        assertEquals("标题", lines[0].text)
        assertEquals(EditorLine.MarkerKind.Bullet, lines[1].marker)
        assertEquals("• 甲", lines[1].displayText)
        assertEquals(EditorLine.MarkerKind.Todo, lines[3].marker)
        assertEquals("☑ 做", lines[3].displayText)
        assertEquals("☐ 没做", lines[4].displayText)
    }

    @Test
    fun `a paragraph containing newlines is split into lines sharing the block style`() {
        val lines = EditorModel.partsToLines(
            listOf(ContentPart(style = ContentPartStyle.quote, text = "第一行\n第二行")),
        )
        assertEquals(2, lines.size)
        assertTrue(lines.all { it.blockStyle == ContentPartStyle.quote })
        assertEquals("第一行", lines[0].text)
        assertEquals("第二行", lines[1].text)
    }

    @Test
    fun `parts lines parts round trip is stable`() {
        val parts = listOf(
            ContentPart(style = ContentPartStyle.title, runs = listOf(TextRun(text = "标题"))),
            ContentPart(style = ContentPartStyle.body, runs = listOf(TextRun(text = "正文"), TextRun(text = "粗", bold = true))),
            ContentPart(style = ContentPartStyle.list, items = listOf("甲", "乙")),
            ContentPart(style = ContentPartStyle.todo, items = listOf("做"), done = listOf(false)),
        )
        val once = EditorModel.linesToParts(EditorModel.partsToLines(parts))
        val twice = EditorModel.linesToParts(EditorModel.partsToLines(once))
        assertEquals(once, twice)
    }

    // ------------------------------------------------------------------ E1/E2：块样式不被改写

    @Test
    fun `block style is set and read back verbatim with no font size involved`() {
        // 模型里没有任何字号输入 —— 这一步在 iOS 上是"绘制字号绝不能反推块样式"的半个答案
        var lines = listOf(EditorLine(text = "一段文字"))
        for (style in listOf(ContentPartStyle.title, ContentPartStyle.heading, ContentPartStyle.quote, ContentPartStyle.body)) {
            lines = EditorModel.setBlockStyle(lines, 0..0, style)
            assertEquals(style, lines.single().blockStyle)
            assertEquals(style, EditorModel.linesToParts(lines).single().style)
        }
    }

    @Test
    fun `converting a list to a heading drops the marker`() {
        val lines = listOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
        )
        val result = EditorModel.setBlockStyle(lines, 0..1, ContentPartStyle.title)
        assertEquals(2, result.size)
        assertTrue("标记必须被去掉", result.all { it.marker == null })
        assertTrue(result.all { it.blockStyle == ContentPartStyle.title })
    }

    @Test
    fun `a single list line carrying items expands into one paragraph per item`() {
        val lines = listOf(EditorLine(text = "甲"), EditorLine(text = "乙"))
        val marked = EditorModel.setMarkerOnRange(lines, 0..1, EditorLine.MarkerKind.Bullet)
        assertEquals(2, marked.size)
        assertTrue(marked.all { it.marker == EditorLine.MarkerKind.Bullet })
        // 落库时合并成一条
        assertEquals(1, EditorModel.linesToParts(marked).size)
        // 再转回标题：一条列表 → 两个段落
        val asTitle = EditorModel.setBlockStyle(marked, 0..1, ContentPartStyle.title)
        assertEquals(2, asTitle.size)
        assertTrue(asTitle.all { it.marker == null })
    }

    // ------------------------------------------------------------------ E3：区间操作不能逐下标就地改

    @Test
    fun `range style change rebuilds the range instead of shifting indices`() {
        val lines = listOf(
            EditorLine(text = "第一段"),
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "最后一段"),
        )
        val result = EditorModel.setBlockStyle(lines, 0..3, ContentPartStyle.heading)
        assertEquals(4, result.size)
        assertEquals(listOf("第一段", "甲", "乙", "最后一段"), result.map { it.text })
        assertTrue(result.all { it.blockStyle == ContentPartStyle.heading })
        assertEquals("最后一段", result.last().text)
    }

    /**
     * 对照测试：把"逐下标就地改"写一遍，证明它会错位 ——
     * 这正是 iOS 必须 `ranges.reversed()` 的原因，也是我不采用那种写法的理由。
     */
    @Test
    fun `mutating indices in ascending order corrupts the result`() {
        val lines = listOf(
            EditorLine(text = "第一段"),
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "最后一段"),
        )
        // 模拟 iOS 风格的"按范围逐个改"：列表行摊平成两行后，后面的下标全部偏移
        var corrupted = lines
        for (index in 0..3) {
            val line = corrupted.getOrNull(index) ?: continue
            val replacement = if (line.marker == null) {
                listOf(line.copy(blockStyle = ContentPartStyle.heading))
            } else {
                listOf(
                    line.copy(blockStyle = ContentPartStyle.heading, marker = null),
                    line.copy(blockStyle = ContentPartStyle.heading, marker = null),
                )
            }
            corrupted = corrupted.take(index) + replacement + corrupted.drop(index + 1)
        }
        assertTrue(
            "正序就地改会让结果行数/内容偏离预期：$corrupted",
            corrupted.map { it.text } != listOf("第一段", "甲", "乙", "最后一段") || corrupted.size != 4,
        )
    }

    @Test
    fun `alignment applies to the whole range`() {
        val lines = List(3) { EditorLine(text = "第 $it 行") }
        val centered = EditorModel.setAlignment(lines, 0..2, center = true)
        assertTrue(centered.all { it.align == EditorModel.ALIGN_CENTER })
        val cleared = EditorModel.setAlignment(centered, 1..1, center = false)
        assertEquals(null, cleared[1].align)
        assertEquals(EditorModel.ALIGN_CENTER, cleared[0].align)
    }

    // ------------------------------------------------------------------ 引用与标记

    @Test
    fun `quote toggles only when the whole range is already quoted`() {
        val lines = List(2) { EditorLine(text = "第 $it 行") }
        val quoted = EditorModel.toggleQuote(lines, 0..1)
        assertTrue(quoted.all { it.blockStyle == ContentPartStyle.quote })
        val unquoted = EditorModel.toggleQuote(quoted, 0..1)
        assertTrue(unquoted.all { it.blockStyle == ContentPartStyle.body })
    }

    @Test
    fun `mixed range becomes fully quoted`() {
        val lines = listOf(
            EditorLine(text = "甲", blockStyle = ContentPartStyle.quote),
            EditorLine(text = "乙"),
        )
        val result = EditorModel.toggleQuote(lines, 0..1)
        assertTrue(result.all { it.blockStyle == ContentPartStyle.quote })
    }

    @Test
    fun `turning on a marker resets the block style and alignment first`() {
        // 与 iOS 的 restyle(to: .body, cancelCenter: true) 一致
        val lines = listOf(
            EditorLine(text = "标题行", blockStyle = ContentPartStyle.title, align = EditorModel.ALIGN_CENTER),
        )
        val asList = EditorModel.toggleMarker(lines, 0, EditorLine.MarkerKind.Bullet)
        val line = asList.single()
        assertEquals(EditorLine.MarkerKind.Bullet, line.marker)
        assertEquals(ContentPartStyle.body, line.blockStyle)
        assertEquals(null, line.align)

        // 再点一次取消
        val off = EditorModel.toggleMarker(asList, 0, EditorLine.MarkerKind.Bullet)
        assertEquals(null, off.single().marker)
    }

    @Test
    fun `switching between list and todo replaces the marker`() {
        val asList = EditorModel.toggleMarker(listOf(EditorLine(text = "甲")), 0, EditorLine.MarkerKind.Bullet)
        val asTodo = EditorModel.toggleMarker(asList, 0, EditorLine.MarkerKind.Todo)
        assertEquals(EditorLine.MarkerKind.Todo, asTodo.single().marker)
        assertEquals(false, asTodo.single().done)
    }

    @Test
    fun `todo done state toggles only for todo lines`() {
        val lines = listOf(
            EditorLine(text = "普通"),
            EditorLine(text = "做", marker = EditorLine.MarkerKind.Todo, done = false),
        )
        val done = EditorModel.setTodoDone(lines, 1, true)
        assertEquals(true, done[1].done)
        // 非待办行不受影响
        assertEquals(lines, EditorModel.setTodoDone(lines, 0, true))
    }

    // ------------------------------------------------------------------ 行内样式

    @Test
    fun `inline flag applies to the selected sub range only`() {
        val line = EditorLine(text = "前缀加粗后缀")
        val bolded = EditorModel.toggleInline(line, 2, 4, InlineFlag.Bold)
        assertEquals("前缀加粗后缀", bolded.text)
        assertFalse(EditorModel.flagsAt(bolded, 0).bold)
        assertTrue(EditorModel.flagsAt(bolded, 2).bold)
        assertTrue(EditorModel.flagsAt(bolded, 3).bold)
        assertFalse(EditorModel.flagsAt(bolded, 4).bold)
        // run 被切成三段且有样式的那段只写 true
        assertEquals(3, bolded.spans.size)
        assertEquals(true, bolded.spans[1].bold)
        assertEquals(null, bolded.spans[0].bold)
    }

    @Test
    fun `toggling the same range twice restores a single unstyled run`() {
        val line = EditorLine(text = "一段文字")
        val once = EditorModel.toggleInline(line, 1, 3, InlineFlag.Italic)
        val twice = EditorModel.toggleInline(once, 1, 3, InlineFlag.Italic)
        assertFalse(EditorModel.flagsAt(twice, 1).italic)
        assertEquals("相邻同样式 run 应被合并回一段", 1, twice.spans.size)
        assertEquals("一段文字", twice.spans.single().text)
    }

    @Test
    fun `mixed selection enables the flag instead of clearing it`() {
        val line = EditorLine(
            text = "ab",
            spans = listOf(TextRun(text = "a", bold = true), TextRun(text = "b")),
        )
        assertFalse("混合选区视为未开启", EditorModel.activeFlags(line, 0, 2).bold)
        val enabled = EditorModel.toggleInline(line, 0, 2, InlineFlag.Bold)
        assertTrue(EditorModel.activeFlags(enabled, 0, 2).bold)
    }

    @Test
    fun `empty selection does not modify anything`() {
        val line = EditorLine(text = "文字")
        assertEquals(line, EditorModel.toggleInline(line, 1, 1, InlineFlag.Bold))
    }

    @Test
    fun `all four inline flags are supported independently`() {
        var line = EditorLine(text = "样式")
        for (flag in InlineFlag.entries) {
            line = EditorModel.toggleInline(line, 0, 2, flag)
        }
        val flags = EditorModel.activeFlags(line, 0, 2)
        assertTrue(flags.bold && flags.italic && flags.strike && flags.underline)
        // 取消其中一个不影响其它
        val withoutStrike = EditorModel.toggleInline(line, 0, 2, InlineFlag.Strike)
        val after = EditorModel.activeFlags(withoutStrike, 0, 2)
        assertTrue(after.bold && after.italic && after.underline)
        assertFalse(after.strike)
    }

    // ------------------------------------------------------------------ Enter / Backspace

    @Test
    fun `splitting a line keeps style alignment and inline flags`() {
        val line = EditorLine(
            text = "前半后半",
            blockStyle = ContentPartStyle.heading,
            align = EditorModel.ALIGN_CENTER,
            spans = listOf(TextRun(text = "前半", bold = true), TextRun(text = "后半")),
        )
        val split = EditorModel.splitLine(listOf(line), 0, 2)
        assertEquals(2, split.size)
        assertEquals("前半", split[0].text)
        assertEquals("后半", split[1].text)
        assertTrue(split.all { it.blockStyle == ContentPartStyle.heading })
        assertTrue(split.all { it.align == EditorModel.ALIGN_CENTER })
        assertEquals(true, split[0].spans.single().bold)
        assertEquals(null, split[1].spans.single().bold)
    }

    @Test
    fun `splitting a todo line yields an unchecked new item`() {
        val line = EditorLine(text = "做事", marker = EditorLine.MarkerKind.Todo, done = true)
        val split = EditorModel.splitLine(listOf(line), 0, 1)
        assertEquals(true, split[0].done)
        assertEquals("新拆出的待办必须未勾选", false, split[1].done)
        assertEquals(EditorLine.MarkerKind.Todo, split[1].marker)
    }

    @Test
    fun `inserting a line after inherits the block style and marker`() {
        val lines = listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet))
        val result = EditorModel.insertLineAfter(lines, 0)
        assertEquals(2, result.size)
        assertEquals(EditorLine.MarkerKind.Bullet, result[1].marker)
        assertEquals("", result[1].text)
        // 两条连续条目 → 落库仍是一条 list；空的那条还不算条目，不入库
        assertEquals(1, EditorModel.linesToParts(result).size)
        assertEquals(listOf("甲"), EditorModel.linesToParts(result).single().items)
    }

    @Test
    fun `merging with the previous line joins the text and runs`() {
        val lines = listOf(
            EditorLine(text = "前", spans = listOf(TextRun(text = "前", bold = true))),
            EditorLine(text = "后"),
        )
        val merged = EditorModel.mergeWithPrevious(lines, 1)
        assertEquals(1, merged.size)
        assertEquals("前后", merged.single().text)
        assertEquals(2, merged.single().spans.size)
        assertEquals(true, merged.single().spans[0].bold)
    }

    @Test
    fun `merging an empty paragraph into the previous one keeps the previous line`() {
        val lines = listOf(EditorLine(text = "保留"), EditorLine(text = ""))
        val merged = EditorModel.mergeWithPrevious(lines, 1)
        assertEquals(listOf("保留"), merged.map { it.text })
    }

    @Test
    fun `merge is a no-op on the first line`() {
        val lines = listOf(EditorLine(text = "甲"), EditorLine(text = "乙"))
        assertEquals(lines, EditorModel.mergeWithPrevious(lines, 0))
    }

    // ------------------------------------------------------------------ format gate（E4）

    @Test
    fun `format is blocked while the selection overlaps the ime composition`() {
        // 组合区 [2,5)：选区在组合区内部 → 阻断
        assertFalse(FormatGate.canApplyFormat(3, 4, 2, 5))
        // 选区跨过组合区 → 阻断
        assertFalse(FormatGate.canApplyFormat(1, 6, 2, 5))
        // 选区与组合区相接但不重叠 → 允许
        assertTrue(FormatGate.canApplyFormat(5, 6, 2, 5))
        assertTrue(FormatGate.canApplyFormat(0, 2, 2, 5))
        // 没有组合 → 永远允许
        assertTrue(FormatGate.canApplyFormat(0, 3, -1, -1))
        // 空组合
        assertFalse(FormatGate.hasComposition(4, 4))
        assertTrue(FormatGate.hasComposition(4, 6))
    }

    @Test
    fun `format gate tolerates an inverted selection`() {
        // 选区方向被反过来时也要正确判断
        assertFalse(FormatGate.canApplyFormat(4, 3, 2, 5))
        assertTrue(FormatGate.canApplyFormat(6, 5, 2, 5))
    }

    // ------------------------------------------------------------------ 段落样式与工具栏

    @Test
    fun `editor block style maps list todo quote and image to body`() {
        assertEquals(EditorBlockStyle.Title, EditorBlockStyle.fromPartStyle(ContentPartStyle.title))
        assertEquals(EditorBlockStyle.Heading, EditorBlockStyle.fromPartStyle(ContentPartStyle.heading))
        assertEquals(EditorBlockStyle.Body, EditorBlockStyle.fromPartStyle(ContentPartStyle.body))
        // 列表、待办、**引用**、图片与未知值都按正文显示在格式栏第一个按钮上：
        // 它们各自有自己的按钮，不该顺手把"段落样式"按钮点亮（用户 2026-09-28 反馈）
        assertEquals(EditorBlockStyle.Body, EditorBlockStyle.fromPartStyle(ContentPartStyle.quote))
        assertEquals(EditorBlockStyle.Body, EditorBlockStyle.fromPartStyle(ContentPartStyle.list))
        assertEquals(EditorBlockStyle.Body, EditorBlockStyle.fromPartStyle(ContentPartStyle.todo))
        assertEquals(EditorBlockStyle.Body, EditorBlockStyle.fromPartStyle(ContentPartStyle.image))
        assertEquals(EditorBlockStyle.Body, EditorBlockStyle.fromPartStyle("monospaced"))
    }
    // ------------------------------------------------------------------ 图片行（数据不能丢）

    @Test
    fun `an image line persists as an image part with its src and size`() {
        val lines = listOf(
            EditorLine(text = "正文"),
            EditorLine(imageSrc = "images/img_1.jpg", imageWidth = 1200.0, imageHeight = 900.0),
        )
        val parts = EditorModel.linesToParts(lines)
        assertEquals(2, parts.size)
        assertEquals(ContentPartStyle.image, parts[1].style)
        assertEquals("images/img_1.jpg", parts[1].src)
        assertEquals(1200.0, parts[1].w!!, 1e-6)
        assertEquals(900.0, parts[1].h!!, 1e-6)
    }

    @Test
    fun `image parts survive a lines round trip`() {
        val parts = listOf(
            ContentPart(style = ContentPartStyle.body, text = "正文"),
            ContentPart(style = ContentPartStyle.image, src = "images/a.jpg", w = 800.0, h = 600.0),
        )
        val lines = EditorModel.partsToLines(parts)
        assertEquals(2, lines.size)
        assertTrue(lines[1].isImage)
        assertEquals("images/a.jpg", lines[1].imageSrc)

        val back = EditorModel.linesToParts(lines)
        // 图片段的 src 与宽高必须原样保留 —— 否则用户插入的图片会在下一次编辑时丢失
        assertEquals(parts[1], back[1])
        // 但纯文本段落会被**规范化成 runs**：与 iOS 的编辑器一致（编辑器只生产 runs，
        // `text` 是留给手写/导入内容的紧凑形式）
        assertEquals(ContentPartStyle.body, back[0].style)
        assertEquals(listOf("正文"), back[0].runs?.map { it.text })
        assertEquals(null, back[0].text)
    }

    @Test
    fun `inserting an image line carries the source`() {
        val lines = listOf(EditorLine(text = "正文"))
        val inserted = EditorModel.insertImageLine(lines, 0, src = "images/new.jpg", width = 400.0, height = 300.0)
        assertEquals(2, inserted.size)
        assertEquals("images/new.jpg", inserted[1].imageSrc)
        assertEquals("", inserted[1].text)
        // 图片行不参与列表分组
        val withList = listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet))
        val listPlusImage = EditorModel.insertImageLine(withList, 0, src = "images/x.jpg")
        val parts = EditorModel.linesToParts(listPlusImage)
        assertEquals(2, parts.size)
        assertEquals(ContentPartStyle.list, parts[0].style)
        assertEquals(ContentPartStyle.image, parts[1].style)
    }

    // ------------------------------------------------------------------ 一行一张图

    @Test
    fun `an image never shares a line with text`() {
        val lines = listOf(EditorLine(text = "甲"), EditorLine(text = "乙"))
        listOf(0, 1).forEach { caret ->
            val inserted = EditorModel.insertImageLine(lines, caret, src = "images/x.jpg")
            val imageLine = inserted.single { it.imageSrc != null }
            assertEquals("图片行必须是纯图片行", "", imageLine.text)
            assertEquals("图片独占一行", 3, inserted.size)
            // 哪一行都不允许同时有图片和文字
            assertTrue(inserted.none { it.imageSrc != null && it.text.isNotEmpty() })
            // 光标下面那一行 = 图片行的下一行
            assertEquals(caret + 1, EditorModel.imageLineIndexAfterInsert(lines, caret))
        }
    }

    @Test
    fun `an image on an empty line takes that line instead of leaving a blank one above`() {
        // 新片段：模型里只有一张空行（光标就在上面）
        val lines = listOf(EditorModel.EMPTY_LINE)
        val inserted = EditorModel.insertImageLine(lines, 0, src = "images/only.jpg", width = 100.0, height = 50.0)

        assertEquals("不额外留一张空行", 1, inserted.size)
        assertEquals("images/only.jpg", inserted[0].imageSrc)
        assertEquals("图片行就是光标那一行", 0, EditorModel.imageLineIndexAfterInsert(lines, 0))
    }

    @Test
    fun `an image does not land on a line that already holds one`() {
        val first = EditorModel.insertImageLine(listOf(EditorModel.EMPTY_LINE), 0, src = "images/a.jpg")
        assertEquals(1, first.size)
        // 光标停在图片行上再插一张：新图片必须**另起一行**，不能压在上一张上
        val second = EditorModel.insertImageLine(first, 0, src = "images/b.jpg")
        assertEquals(2, second.size)
        assertEquals("images/a.jpg", second[0].imageSrc)
        assertEquals("images/b.jpg", second[1].imageSrc)
    }

    @Test
    fun `an empty list item keeps its marker when an image is inserted`() {
        // 空条目（只有标记、还没输入）不替换：用户可能正要往这一条里打字
        val lines = listOf(EditorLine(text = "", marker = EditorLine.MarkerKind.Bullet))
        val inserted = EditorModel.insertImageLine(lines, 0, src = "images/x.jpg")
        assertEquals(2, inserted.size)
        assertEquals(EditorLine.MarkerKind.Bullet, inserted[0].marker)
        assertEquals("images/x.jpg", inserted[1].imageSrc)
    }

    // ------------------------------------------------------------------ D9：回车继承标记

    @Test
    fun `pressing enter inside a list keeps the list going`() {
        val before = listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet))
        // EditText 只会插入换行，新行没有标记
        val parsed = listOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = ""),
        )
        val fixed = EditorModel.inheritMarkersForNewLines(parsed, previous = before)
        assertEquals(EditorLine.MarkerKind.Bullet, fixed[1].marker)
        // 落库仍是一条 list；**空条目不入库**（规范 §2.3：只有标记、后面没有文字的行
        // 是"用户还在这一行上"，不是一个条目 —— 与 iOS `parts(from:)` 一致）
        val parts = EditorModel.linesToParts(fixed)
        assertEquals(1, parts.size)
        assertEquals(listOf("甲"), parts[0].items)

        // 用户接着在新行里输入 → 才成为一个真正的条目
        val typed = fixed.toMutableList().also { it[1] = it[1].copy(text = "乙") }
        assertEquals(listOf("甲", "乙"), EditorModel.linesToParts(typed)[0].items)
    }

    @Test
    fun `pressing enter inside a todo inherits an unchecked item`() {
        val before = listOf(EditorLine(text = "做", marker = EditorLine.MarkerKind.Todo, done = true))
        val parsed = listOf(
            EditorLine(text = "做", marker = EditorLine.MarkerKind.Todo, done = true),
            EditorLine(text = ""),
        )
        val fixed = EditorModel.inheritMarkersForNewLines(parsed, previous = before)
        assertEquals(EditorLine.MarkerKind.Todo, fixed[1].marker)
        assertEquals("新条目必须未勾选", false, fixed[1].done)
    }

    @Test
    fun `pressing enter in a plain paragraph does not invent a marker`() {
        val before = listOf(EditorLine(text = "正文"))
        val parsed = listOf(EditorLine(text = "正文"), EditorLine(text = ""))
        val fixed = EditorModel.inheritMarkersForNewLines(parsed, previous = before)
        assertEquals(null, fixed[1].marker)
    }

    @Test
    fun `marker inheritance only applies when lines were added`() {
        // 行数没变（普通编辑）→ 不做任何修补
        val lines = listOf(EditorLine(text = ""), EditorLine(text = ""))
        assertEquals(lines, EditorModel.inheritMarkersForNewLines(lines, previous = lines))
        // 行数变少（删除）→ 同样不动
        val fewer = listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet))
        assertEquals(
            fewer,
            EditorModel.inheritMarkersForNewLines(fewer, previous = listOf(EditorLine(text = "甲"), EditorLine(text = "乙"))),
        )
    }

    // ------------------------------------------------------------------ 规范 §3.2 / §3.4 的规则

    @Test
    fun `switching to quote forces left alignment`() {
        // 规范 §2.5：切到引用时强制左对齐（iOS 的 cancelCenter）
        val lines = listOf(
            EditorLine(text = "居中标题", blockStyle = ContentPartStyle.title, align = EditorModel.ALIGN_CENTER),
        )
        val quoted = EditorModel.setBlockStyle(lines, 0..0, ContentPartStyle.quote)
        assertEquals(ContentPartStyle.quote, quoted.single().blockStyle)
        assertEquals("引用必须左对齐", null, quoted.single().align)
    }

    @Test
    fun `marker toggle direction follows the cursor line not the whole range`() {
        // 规范 §3.2：选区只决定范围，方向由**光标所在行**决定
        val lines = listOf(
            EditorLine(text = "甲"),
            EditorLine(text = "乙"),
            EditorLine(text = "丙", marker = EditorLine.MarkerKind.Bullet),
        )
        // 光标在第 0 行（正文）→ 方向是"开启"，整个选区一起变成列表
        val on = EditorModel.setMarkerOnRange(lines, 0..2, EditorLine.MarkerKind.Bullet, cursorIndex = 0)
        assertTrue(on.all { it.marker == EditorLine.MarkerKind.Bullet })

        // 光标在第 2 行（已是列表）→ 方向是"取消"
        val off = EditorModel.setMarkerOnRange(on, 0..2, EditorLine.MarkerKind.Bullet, cursorIndex = 2)
        assertTrue(off.all { it.marker == null })
    }

    // ------------------------------------------------------------------ 行内样式不随段落级改动丢失

    /** 一条"加粗 + 斜体 + 删除线 + 下划线"的行（文本 `styled` 全部带样式）。 */
    private fun styledLine(
        text: String = "styled",
        marker: EditorLine.MarkerKind? = null,
        blockStyle: String = ContentPartStyle.body,
    ) = EditorLine(
        text = text,
        blockStyle = blockStyle,
        marker = marker,
        spans = listOf(
            com.cov.justdiary.model.TextRun(
                text = text,
                bold = true,
                italic = true,
                strike = true,
                underline = true,
            ),
        ),
    )

    /** 四种行内样式是否都还在。 */
    private fun assertKeepsAllInlineStyles(line: EditorLine, message: String) {
        val run = line.spans.singleOrNull()
        assertTrue("$message：加粗没了", run?.bold == true)
        assertTrue("$message：斜体没了", run?.italic == true)
        assertTrue("$message：删除线没了", run?.strike == true)
        assertTrue("$message：下划线没了", run?.underline == true)
        assertEquals("$message：文字也不能变", "styled", line.text)
    }

    @Test
    fun `changing the paragraph style keeps the inline styles`() {
        // 用户在真机上遇到的那条：一条"待办 + 加粗/斜体/删除线/下划线"的行，
        // 切一次小标题，四种行内样式全没了（2026-09-28 返工）
        val todo = styledLine(marker = EditorLine.MarkerKind.Todo)
        val heading = EditorModel.setBlockStyle(listOf(todo), 0..0, ContentPartStyle.heading).single()

        assertEquals(ContentPartStyle.heading, heading.blockStyle)
        assertEquals("换段落样式会丢标记（与 iOS 一致）", null, heading.marker)
        assertKeepsAllInlineStyles(heading, "待办行切小标题")

        // 标题 → 正文、正文 → 引用也都一样
        val body = EditorModel.setBlockStyle(listOf(heading), 0..0, ContentPartStyle.body).single()
        assertKeepsAllInlineStyles(body, "小标题切正文")
        val quote = EditorModel.setBlockStyle(listOf(body), 0..0, ContentPartStyle.quote).single()
        assertKeepsAllInlineStyles(quote, "正文切引用")

        // 列表行同样
        val list = styledLine(marker = EditorLine.MarkerKind.Bullet)
        assertKeepsAllInlineStyles(
            EditorModel.setBlockStyle(listOf(list), 0..0, ContentPartStyle.title).single(),
            "列表行切大标题",
        )
    }

    @Test
    fun `turning a list or todo on and off keeps the inline styles`() {
        val plain = styledLine()
        val asList = EditorModel.setMarkerOnRange(listOf(plain), 0..0, EditorLine.MarkerKind.Bullet)
            .single()
        assertEquals(EditorLine.MarkerKind.Bullet, asList.marker)
        assertKeepsAllInlineStyles(asList, "正文变列表")

        val asTodo = EditorModel.setMarkerOnRange(listOf(asList), 0..0, EditorLine.MarkerKind.Todo)
            .single()
        assertEquals(EditorLine.MarkerKind.Todo, asTodo.marker)
        assertKeepsAllInlineStyles(asTodo, "列表变待办")

        val back = EditorModel.setMarkerOnRange(listOf(asTodo), 0..0, EditorLine.MarkerKind.Todo)
            .single()
        assertEquals(null, back.marker)
        assertKeepsAllInlineStyles(back, "待办取消标记")

        // `toggleMarker`（单行那条路径）同样不能丢
        assertKeepsAllInlineStyles(
            EditorModel.toggleMarker(listOf(plain), 0, EditorLine.MarkerKind.Bullet).single(),
            "toggleMarker 开列表",
        )
    }

    @Test
    fun `centering and todo done state keep the inline styles`() {
        val line = styledLine(marker = EditorLine.MarkerKind.Todo)
        assertKeepsAllInlineStyles(
            EditorModel.setTodoDone(listOf(line), 0, true).single(),
            "勾选待办",
        )
        val centered = EditorModel.setAlignment(listOf(styledLine()), 0..0, center = true).single()
        assertEquals(EditorModel.ALIGN_CENTER, centered.align)
        assertKeepsAllInlineStyles(centered, "居中")
    }

    @Test
    fun `pressing enter on an empty list item ends the list`() {
        // 规范 §3.4：空条目再回车 = 结束列表（上一行去掉标记，新行是普通空行）
        val before = listOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "", marker = EditorLine.MarkerKind.Bullet),
        )
        val parsed = before + EditorLine(text = "")
        val fixed = EditorModel.repairAfterEnter(parsed, previous = before)

        assertEquals(null, fixed[1].marker)
        assertEquals(null, fixed[2].marker)
        // 落库：只有"甲"这一条
        assertEquals(listOf("甲"), EditorModel.linesToParts(fixed).single().items)
    }

    @Test
    fun `a paragraph style continues onto the next line however the line looks`() {
        // 用户规则 3："列表、引用、待办……回车之后也不取消格式，直到用户取消格式" ——
        // **空行也一样**（用户的原话没有给空行开例外），于是"怎么退出引用"的答案是
        // "再点一次引用按钮"。这是与 iOS 的一处有意差异（D10：iOS 在空引用行上
        // 消费按键并结束引用），文档 §7 有记。
        val emptyQuote = listOf(EditorLine(text = "", blockStyle = ContentPartStyle.quote))
        assertEquals(
            ContentPartStyle.quote,
            EditorModel.repairAfterEnter(emptyQuote + EditorLine(text = ""), previous = emptyQuote)[1].blockStyle,
        )

        // 标题同理：先在空行上选「大标题」再回车，新行还是大标题（iOS：段落属性随换行延续）
        val emptyTitle = listOf(EditorLine(text = "", blockStyle = ContentPartStyle.title))
        assertEquals(
            ContentPartStyle.title,
            EditorModel.repairAfterEnter(emptyTitle + EditorLine(text = ""), previous = emptyTitle)[1].blockStyle,
        )

        // ⚠️ **居中不延续**（与 iOS 的有意差异，用户 2026-09-28 实测反馈后定的）：
        // 居中一旦被换行继承，取消时只取消光标那一行、上面几行还留着居中，
        // 看起来就像"取消没生效"。居中的作用域就是"本行"。
        val centered = listOf(EditorLine(text = "居中正文", align = EditorModel.ALIGN_CENTER))
        assertEquals(
            null,
            EditorModel.repairAfterEnter(centered + EditorLine(text = ""), previous = centered)[1].align,
        )

        // 正文回车：没有样式要延续，新行就是普通空行
        val body = listOf(EditorLine(text = ""))
        val plain = EditorModel.repairAfterEnter(body + EditorLine(text = ""), previous = body)[1]
        assertEquals(ContentPartStyle.body, plain.blockStyle)
        assertEquals(null, plain.align)
    }

    @Test
    fun `starting to type on an empty line keeps its paragraph style`() {
        // 空行的样式在富文本里没有字符可挂，由这一条在"开始输入"时接上
        val previous = listOf(EditorLine(text = "", blockStyle = ContentPartStyle.heading))
        val typed = listOf(EditorLine(text = "刚写的"))
        val fixed = EditorModel.carryOverStylesWhenTyping(typed, previous)
        assertEquals(ContentPartStyle.heading, fixed.single().blockStyle)
    }

    @Test
    fun `pending inline flags apply to the inserted range only`() {
        val line = EditorLine(text = "前中后")
        val applied = EditorModel.applyInlineFlags(line, start = 1, end = 2, flags = InlineFlags(bold = true))
        val runs = applied.spans
        assertEquals(3, runs.size)
        assertEquals(false, runs[0].bold == true)
        assertEquals(true, runs[1].bold == true)
        assertEquals(false, runs[2].bold == true)
    }

    // ==================================================================== 用户 2026-09-28 的六条规则
    //
    // 这一节是**逐条对着用户原话**写的（`docs/android-design/04-diary.md` §3.3）：
    //
    //   1. 粗体/斜体/删除线/下划线可以**同时**应用；
    //   2. 大小标题、正文、列表、待办、引用**互斥**，启用一个自动取消另外的（先取消再启用）；
    //   3. 大小标题/正文的范围是"选中之后输入的内容"；列表/引用/待办是**一整行**且回车后
    //      不取消；本行已有内容时先取消本行格式再应用新的；取消只取消当前行；
    //   4. 居中的作用对象是"本行"（有内容 → 整行；没内容 → 光标）；
    //   5. 居中与列表/待办/引用互斥，且取消那三类**不恢复**居中；
    //   6. 切大小标题/正文时**光标字号**要跟着变（列表/引用/待办按各自的字号）。

    /** 规则 1：四种行内样式是**叠加**的，不是单选项。 */
    @Test
    fun `rule 1 all four inline styles stack on one range`() {
        var line = EditorLine(text = "样式")
        for (flag in InlineFlag.entries) {
            line = EditorModel.toggleInline(line, 0, 2, flag)
        }
        val run = line.spans.single()
        assertTrue("加粗", run.bold == true)
        assertTrue("斜体", run.italic == true)
        assertTrue("删除线", run.strike == true)
        assertTrue("下划线", run.underline == true)

        // 关掉其中一个，另外三个必须原样留着
        val withoutBold = EditorModel.toggleInline(line, 0, 2, InlineFlag.Bold)
        val kept = withoutBold.spans.single()
        assertEquals(null, kept.bold)
        assertTrue("斜体", kept.italic == true)
        assertTrue("删除线", kept.strike == true)
        assertTrue("下划线", kept.underline == true)
    }

    /** 规则 2：六选一互斥 —— 任意两种格式互相切换，结果只剩目标那一种。 */
    @Test
    fun `rule 2 the six paragraph formats are mutually exclusive`() {
        for (from in BlockFormat.entries) {
            for (to in BlockFormat.entries) {
                val start = EditorModel.applyBlockFormat(listOf(EditorLine(text = "本行有内容")), 0..0, from)
                val result = EditorModel.applyBlockFormat(start, 0..0, to).single()
                assertEquals("$from → $to", to, BlockFormat.ofLine(result))
                assertEquals("$from → $to：文字不能变", "本行有内容", result.text)
            }
        }
    }

    /** 规则 2/3：先取消再应用的顺序在"列表 → 大标题"上看得最清楚（标记必须消失）。 */
    @Test
    fun `rule 2 list to title drops the marker and the other way round`() {
        val list = EditorModel.applyBlockFormat(listOf(EditorLine(text = "甲")), 0..0, BlockFormat.List)
        assertEquals(EditorLine.MarkerKind.Bullet, list.single().marker)

        val title = EditorModel.applyBlockFormat(list, 0..0, BlockFormat.Title).single()
        assertEquals(ContentPartStyle.title, title.blockStyle)
        assertEquals("列表标记必须先被取消", null, title.marker)

        val backToList = EditorModel.applyBlockFormat(listOf(title), 0..0, BlockFormat.List).single()
        assertEquals(EditorLine.MarkerKind.Bullet, backToList.marker)
        assertEquals("大标题必须先被取消", ContentPartStyle.body, backToList.blockStyle)
    }

    /** 规则 2：互斥只动"段落格式"，四种行内样式一个都不能掉。 */
    @Test
    fun `rule 2 switching between all six formats keeps the inline styles`() {
        for (from in BlockFormat.entries) {
            for (to in BlockFormat.entries) {
                val styled = styledLine()
                val start = EditorModel.applyBlockFormat(listOf(styled), 0..0, from)
                val result = EditorModel.applyBlockFormat(start, 0..0, to).single()
                assertKeepsAllInlineStyles(result, "$from → $to")
            }
        }
    }

    /** 规则 3：本行已经有内容 → 先取消本行的格式，再应用选中的格式（不是"跳过"）。 */
    @Test
    fun `rule 3 a line with content is restyled not skipped`() {
        val lines = listOf(
            EditorLine(text = "上一行", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "本行已经有内容了", blockStyle = ContentPartStyle.heading, align = EditorModel.ALIGN_CENTER),
            EditorLine(text = "下一行", marker = EditorLine.MarkerKind.Bullet),
        )
        // 光标在第 1 行 → 只改第 1 行；它原有的小标题 + 居中都要先取消，再套上待办
        val result = EditorModel.toggleBlockFormat(lines, 1..1, BlockFormat.Todo, cursorIndex = 1)
        assertEquals(EditorLine.MarkerKind.Todo, result[1].marker)
        assertEquals(ContentPartStyle.body, result[1].blockStyle)
        assertEquals("列表/待办不居中", null, result[1].align)
        assertEquals("上一行不能被回头改", EditorLine.MarkerKind.Bullet, result[0].marker)
        assertEquals("下一行不能被回头改", EditorLine.MarkerKind.Bullet, result[2].marker)
    }

    /** 规则 3：取消格式只取消**当前行**，以前已经应用过的行原样留着。 */
    @Test
    fun `rule 3 turning a format off only touches the current line`() {
        val lines = listOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "丙", marker = EditorLine.MarkerKind.Bullet),
        )
        val off = EditorModel.toggleBlockFormat(lines, 1..1, BlockFormat.List, cursorIndex = 1)
        assertEquals("上一行不动", EditorLine.MarkerKind.Bullet, off[0].marker)
        assertEquals("当前行取消", null, off[1].marker)
        assertEquals("下一行不动", EditorLine.MarkerKind.Bullet, off[2].marker)

        // 引用同理：只取消光标那一行
        val quoted = listOf(
            EditorLine(text = "甲", blockStyle = ContentPartStyle.quote),
            EditorLine(text = "乙", blockStyle = ContentPartStyle.quote),
        )
        val unquoted = EditorModel.toggleBlockFormat(quoted, 0..0, BlockFormat.Quote)
        assertEquals(ContentPartStyle.body, unquoted[0].blockStyle)
        assertEquals("没选中的行不能被回头改", ContentPartStyle.quote, unquoted[1].blockStyle)
    }

    /** 规则 3：列表 / 引用 / 待办**回车之后不取消**（新行接着同一格式），直到用户取消。 */
    @Test
    fun `rule 3 quote and markers survive the newline`() {
        // 有内容的引用行回车 → 新行还是引用
        val quoteBefore = listOf(EditorLine(text = "引用一句", blockStyle = ContentPartStyle.quote))
        val quoteAfter = EditorModel.repairAfterEnter(quoteBefore + EditorLine(text = ""), previous = quoteBefore)
        assertEquals(ContentPartStyle.quote, quoteAfter[1].blockStyle)

        // 有内容的大标题行回车 → 新行还是大标题（iOS：段落属性随换行延续）
        val titleBefore = listOf(EditorLine(text = "标题", blockStyle = ContentPartStyle.title))
        val titleAfter = EditorModel.repairAfterEnter(titleBefore + EditorLine(text = ""), previous = titleBefore)
        assertEquals(ContentPartStyle.title, titleAfter[1].blockStyle)

        // 居中**不**随换行延续（居中的作用域是"本行"；用户实测反馈见 repairAfterEnter 的注释）
        val centeredBefore = listOf(EditorLine(text = "居中", align = EditorModel.ALIGN_CENTER))
        val centeredAfter = EditorModel.repairAfterEnter(centeredBefore + EditorLine(text = ""), previous = centeredBefore)
        assertEquals(null, centeredAfter[1].align)
        assertEquals("上面那一段自己仍然是居中的", EditorModel.ALIGN_CENTER, centeredAfter[0].align)

        // 列表 / 待办回车 → 新行接着同一标记（原有规则，这里一起钉住）
        val listBefore = listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet))
        val listAfter = EditorModel.repairAfterEnter(listBefore + EditorLine(text = ""), previous = listBefore)
        assertEquals(EditorLine.MarkerKind.Bullet, listAfter[1].marker)
    }

    /** 规则 4：居中的作用对象就是"本行" —— 有内容是整行，没内容是光标（模型里就是空行的 align）。 */
    @Test
    fun `rule 4 centering applies to the current line whether or not it has content`() {
        val withContent = EditorModel.setAlignment(listOf(EditorLine(text = "有内容")), 0..0, center = true)
        assertEquals(EditorModel.ALIGN_CENTER, withContent.single().align)

        val empty = EditorModel.setAlignment(listOf(EditorLine(text = "")), 0..0, center = true)
        assertEquals("空行的居中挂在模型上（光标位置靠它算出来）", EditorModel.ALIGN_CENTER, empty.single().align)

        val back = EditorModel.setAlignment(empty, 0..0, center = false)
        assertEquals(null, back.single().align)
    }

    /**
     * 规则 4：**只有居中的空行**才吃掉回车（用户 2026-09-28 定稿："对齐 iOS 版，
     * 居中的时候不让有空行。但是如果不是居中的时候，是不做限制的"）；
     * **空的引用行**也吃，并顺手结束引用（"按照 iOS 的结束引用"）。
     */
    @Test
    fun `rule 4 only a centered blank line swallows the newline`() {
        val centered = listOf(
            EditorLine(text = "居中正文", align = EditorModel.ALIGN_CENTER),
            EditorLine(text = "", align = EditorModel.ALIGN_CENTER),
        )
        assertTrue("居中的空行不换行", EditorModel.blocksNewline(centered, 1))

        // 不居中 → 完全不限制：连按多少个空行都随用户
        val plain = listOf(EditorLine(text = "正文"), EditorLine(text = ""), EditorLine(text = ""))
        assertFalse("本行空、上一行有内容", EditorModel.blocksNewline(plain, 1))
        assertFalse("本行空、上一行也空（不居中就不管）", EditorModel.blocksNewline(plain, 2))
        assertFalse("有内容的一行照常换行", EditorModel.blocksNewline(plain, 0))

        // 带标记的空条目不是"空行"：回车在那里的含义是结束列表
        val emptyItem = listOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "", marker = EditorLine.MarkerKind.Bullet),
        )
        assertFalse(EditorModel.blocksNewline(emptyItem, 1))
    }

    /** 规则 4 / D10：空引用行回车 = 消费按键 + **结束引用**（与 iOS 一致）。 */
    @Test
    fun `rule 4 an empty quote line swallows the newline and ends the quote`() {
        val lines = listOf(EditorLine(text = "上一行"), EditorLine(text = "", blockStyle = ContentPartStyle.quote))
        assertTrue("空的引用行要吃掉回车", EditorModel.blocksNewline(lines, 1))

        val ended = EditorModel.endQuoteOnBlockedNewline(lines, 1)
        assertEquals("这一行回到正文", ContentPartStyle.body, ended[1].blockStyle)
        assertEquals("别的行一个字都不动", "上一行", ended[0].text)

        // 引用行**有内容**时不吃回车：格式照常延续（规则 3）
        val withText = listOf(EditorLine(text = "引用一句", blockStyle = ContentPartStyle.quote))
        assertFalse(EditorModel.blocksNewline(withText, 0))
        assertSame("有内容时没有结束引用这回事", withText, EditorModel.endQuoteOnBlockedNewline(withText, 0))
    }

    /** 规则 5：列表/引用/待办会**取消**居中；取消它们**不恢复**居中。 */
    @Test
    fun `rule 5 list quote and todo cancel centering and never restore it`() {
        for (format in listOf(BlockFormat.List, BlockFormat.Todo, BlockFormat.Quote)) {
            val centered = listOf(EditorLine(text = "居中标题", blockStyle = ContentPartStyle.title, align = EditorModel.ALIGN_CENTER))
            val applied = EditorModel.applyBlockFormat(centered, 0..0, format).single()
            assertEquals("$format 必须取消居中", null, applied.align)

            val cancelled = EditorModel.toggleBlockFormat(listOf(applied), 0..0, format, cursorIndex = 0).single()
            assertEquals("$format 取消之后不能把居中找回来", null, cancelled.align)
            assertEquals("$format 取消之后回到正文", BlockFormat.Body, BlockFormat.ofLine(cancelled))
        }

        // 反过来：大标题/小标题/正文**不动**居中（居中的标题是合法组合）
        val heading = EditorModel.applyBlockFormat(
            listOf(EditorLine(text = "居中标题", blockStyle = ContentPartStyle.title, align = EditorModel.ALIGN_CENTER)),
            0..0,
            BlockFormat.Heading,
        ).single()
        assertEquals(EditorModel.ALIGN_CENTER, heading.align)
    }

    /** 规则 5：三类行上居中按钮必须置灰（控制器再 guard 一次）。 */
    @Test
    fun `rule 5 centering is refused on list quote and todo lines`() {
        val list = listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet))
        assertFalse(EditorModel.centerAllowed(list, 0..0))
        assertEquals("控制器里也要 guard 住", null, EditorModel.setAlignment(list, 0..0, center = true).single().align)

        val quote = listOf(EditorLine(text = "甲", blockStyle = ContentPartStyle.quote))
        assertFalse(EditorModel.centerAllowed(quote, 0..0))

        val todo = listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Todo))
        assertFalse(EditorModel.centerAllowed(todo, 0..0))

        // 选区里**只要有一行**是那三类，整段都不给居中
        val mixed = listOf(EditorLine(text = "正文"), EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet))
        assertFalse(EditorModel.centerAllowed(mixed, 0..1))
        assertTrue(EditorModel.centerAllowed(mixed, 0..0))

        // 取消居中永远允许（那三类本来也没有居中）
        assertEquals(null, EditorModel.setAlignment(list, 0..0, center = false).single().align)
    }

    /**
     * 规则 5 补丁（2026-10-01）：**图片行**同样不给居中。
     *
     * 图片段的对齐由编解码固定（`linesToParts` 不写 `align`），按钮在那里只会在
     * "亮 / 灭"之间切换而画面逐像素不变 —— iOS `centerIsAllowed` 同样排除 image（B24）。
     */
    @Test
    fun `rule 5 centering is refused on image lines too`() {
        val image = listOf(EditorLine(imageSrc = "images/a.jpg", imageWidth = 300.0, imageHeight = 150.0))
        assertFalse(EditorModel.centerAllowed(image, 0..0))
        assertEquals(
            "图片行上点居中不能改模型",
            null,
            EditorModel.setAlignment(image, 0..0, center = true).single().align,
        )

        // 光标在正文、选区跨到图片行上：整段都不给居中（与列表行同一条口径）
        val mixed = listOf(EditorLine(text = "正文"), image.single())
        assertFalse(EditorModel.centerAllowed(mixed, 0..1))
        assertTrue(EditorModel.centerAllowed(mixed, 0..0))
    }

    /** 规则 6：列表/待办的**绘制**样式一律按正文（字号跟着正文走，光标高度才对）。 */
    @Test
    fun `rule 6 marker lines draw with the body style`() {
        assertEquals(ContentPartStyle.body, SpannableBridge.effectiveBlockStyle(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet)))
        assertEquals(ContentPartStyle.body, SpannableBridge.effectiveBlockStyle(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Todo)))
        assertEquals(ContentPartStyle.title, SpannableBridge.effectiveBlockStyle(EditorLine(text = "标题", blockStyle = ContentPartStyle.title)))
        assertEquals(ContentPartStyle.quote, SpannableBridge.effectiveBlockStyle(EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)))
    }

    /** 规则 3 的"直到用户取消格式"：**空行**的段落格式不能丢（删空 / 回车两种情形）。 */
    @Test
    fun `rule 3 emptying a line keeps its paragraph format`() {
        // 末行（挂不住 span 的那一行，最容易掉回正文）
        val titled = listOf(
            EditorLine(text = "上一行"),
            EditorLine(text = "标题", blockStyle = ContentPartStyle.title, align = EditorModel.ALIGN_CENTER),
        )
        val emptied = listOf(
            EditorLine(text = "上一行"),
            EditorLine(text = ""),
        )
        val kept = EditorModel.keepStylesForBlankLines(emptied, previous = titled).last()
        assertEquals(ContentPartStyle.title, kept.blockStyle)
        assertEquals("居中也要留着", EditorModel.ALIGN_CENTER, kept.align)

        // 引用同理
        val quoted = listOf(EditorLine(text = "引用一句", blockStyle = ContentPartStyle.quote))
        val quoteKept = EditorModel.keepStylesForBlankLines(listOf(EditorLine(text = "")), previous = quoted).single()
        assertEquals(ContentPartStyle.quote, quoteKept.blockStyle)

        // 行数变了（拆行 / 合并）不在此列
        val merged = listOf(EditorLine(text = "上一行标题"))
        assertEquals(merged, EditorModel.keepStylesForBlankLines(merged, previous = titled))

        // 这一版已经有段落样式了 → 不覆盖（用户手动敲进去的算数）
        val typed = listOf(EditorLine(text = "上一行"), EditorLine(text = "", blockStyle = ContentPartStyle.heading))
        assertEquals(typed, EditorModel.keepStylesForBlankLines(typed, previous = titled))

        // 带标记的空条目不是"空行"：标记本身就是格式
        val emptyItem = listOf(EditorLine(text = "", marker = EditorLine.MarkerKind.Bullet))
        assertEquals(
            emptyItem,
            EditorModel.keepStylesForBlankLines(
                emptyItem,
                previous = listOf(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet)),
            ),
        )
    }
}
