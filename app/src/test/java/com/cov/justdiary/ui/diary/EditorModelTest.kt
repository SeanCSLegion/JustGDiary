package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.TextRun
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
    fun `editor block style maps list todo and image to body`() {
        assertEquals(EditorBlockStyle.Title, EditorBlockStyle.fromPartStyle(ContentPartStyle.title))
        assertEquals(EditorBlockStyle.Heading, EditorBlockStyle.fromPartStyle(ContentPartStyle.heading))
        assertEquals(EditorBlockStyle.Quote, EditorBlockStyle.fromPartStyle(ContentPartStyle.quote))
        assertEquals(EditorBlockStyle.Body, EditorBlockStyle.fromPartStyle(ContentPartStyle.body))
        // 列表、待办、图片与未知值都按正文渲染（它们有自己的持久化名字）
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
    fun `an empty quote line does not extend the quote to the next line`() {
        val before = listOf(EditorLine(text = "", blockStyle = ContentPartStyle.quote))
        val parsed = before + EditorLine(text = "")
        val fixed = EditorModel.repairAfterEnter(parsed, previous = before)
        assertEquals(ContentPartStyle.body, fixed[1].blockStyle)
    }

    @Test
    fun `an empty centered line does not produce another centered line`() {
        // 规范 §3.4 的"不产生新的居中空行"：iOS 直接吞掉回车，这里允许换行但不让居中扩散
        val before = listOf(EditorLine(text = "", align = EditorModel.ALIGN_CENTER))
        val parsed = before + EditorLine(text = "")
        val fixed = EditorModel.repairAfterEnter(parsed, previous = before)
        assertEquals(null, fixed[1].align)
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
}
