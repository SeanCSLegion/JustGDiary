package com.cov.justdiary.ui.diary

import android.text.SpannableStringBuilder
import com.cov.justdiary.model.ContentPartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 编辑器状态机的**运行时**回归（Robolectric：需要真的 `Spannable`）。
 *
 * 规范 §3.2 / §3.4 里最容易做错的几条：
 *  - 有选区 / 无选区时 B/I/S/U 的作用域完全不同（无选区只改"接下来输入的样式"）；
 *  - 光标移动要跟着更新"当前样式"（不然工具栏会停在上一处）；
 *  - 回车在列表里续项、在空项上结束列表。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorStateTest {

    private fun state(vararg lines: EditorLine) = EditorTextState(lines.toList())

    /** 模拟一次用户输入：把文本写进 `Spannable` 再交给状态（与 `TextWatcher` 同路径）。 */
    private fun EditorTextState.typeAt(text: SpannableStringBuilder, at: Int, insert: String) {
        text.insert(at, insert)
        onTextReplaced(at until at + insert.length)
        onUserText(text)
        consumeInsertedRange()
    }

    @Test
    fun `toggling a flag with a collapsed caret changes only the pending style`() {
        val state = state(EditorLine(text = "正文"))
        state.onSelectionChanged(EditorSelection(0, 2, 2))

        state.toggleInline(InlineFlag.Bold)

        assertTrue("按钮高亮跟着待输入样式", state.activeFlags.bold)
        assertEquals("已输入的字一个都不能动", "正文", state.lines.single().text)
        assertTrue("模型里也不该有行内样式", state.lines.single().spans.isEmpty())
    }

    @Test
    fun `text typed after a pending toggle comes out styled`() {
        val state = state(EditorLine(text = "ab"))
        state.onSelectionChanged(EditorSelection(0, 2, 2))
        state.toggleInline(InlineFlag.Bold)

        val text = SpannableStringBuilder("ab")
        state.typeAt(text, at = 2, insert = "cd")

        val spans = state.lines.single().spans
        assertEquals("ab", spans.first().text)
        assertEquals("cd", spans.last().text)
        assertEquals(true, spans.last().bold)
        assertEquals(false, spans.first().bold == true)
    }

    @Test
    fun `moving the caret adopts the style under it`() {
        val line = EditorLine(
            text = "粗体普通",
            spans = listOf(
                com.cov.justdiary.model.TextRun(text = "粗体", bold = true),
                com.cov.justdiary.model.TextRun(text = "普通"),
            ),
        )
        val state = state(line)

        // 光标停在"粗体"之后 → 继续输入是粗的
        state.onSelectionChanged(EditorSelection(0, 2, 2))
        assertTrue(state.activeFlags.bold)

        // 光标移到末尾 → 不再是粗的
        state.onSelectionChanged(EditorSelection(0, 4, 4))
        assertFalse(state.activeFlags.bold)
    }

    @Test
    fun `a selection toggles the whole range in one direction`() {
        val state = state(EditorLine(text = "一二三四"))
        state.onSelectionChanged(EditorSelection(0, 1, 3))

        state.toggleInline(InlineFlag.Underline)
        assertEquals(
            listOf("一", "二三", "四"),
            state.lines.single().spans.map { it.text },
        )
        assertTrue(state.lines.single().spans[1].underline == true)

        // 再来一次（整个选区都带 → 取消）
        state.onSelectionChanged(EditorSelection(0, 1, 3))
        state.toggleInline(InlineFlag.Underline)
        assertTrue("取消之后不该再留下样式 run", state.lines.single().spans.size <= 1)
    }

    @Test
    fun `enter inside a list continues the list`() {
        val state = state(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet))
        // EditText 只会插入换行
        val text = SpannableStringBuilder("• 甲")
        state.typeAt(text, at = text.length, insert = "\n")

        assertEquals(2, state.lines.size)
        assertEquals(EditorLine.MarkerKind.Bullet, state.lines[1].marker)
        // 修补改了模型 → 必须请求回写平台文本（回写本身在 Compose 的 update 里做）：
        // 不回写的话用户看不到新行的 `• ` 前缀，下一次解析还会把标记丢掉。
        assertTrue("回车续项之后要请求回写平台文本", state.revision > 0)
    }

    @Test
    fun `enter on an empty list item ends the list`() {
        val state = state(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "", marker = EditorLine.MarkerKind.Bullet),
        )
        val text = SpannableStringBuilder("• 甲\n• ")
        state.typeAt(text, at = text.length, insert = "\n")

        assertEquals(3, state.lines.size)
        assertEquals("空条目回车 → 结束列表：上一行去掉标记", null, state.lines[1].marker)
        assertEquals(null, state.lines[2].marker)
    }

    @Test
    fun `starting to type on an empty title line keeps the title`() {
        val state = state(EditorLine(text = "", blockStyle = ContentPartStyle.title))
        val text = SpannableStringBuilder("")
        state.typeAt(text, at = 0, insert = "标")

        assertEquals(ContentPartStyle.title, state.lines.single().blockStyle)
    }

    // ------------------------------------------------------------------ 插图后的光标位置

    @Test
    fun `inserting an image puts the caret on the line below it`() {
        val state = state(EditorLine(text = "第一行"), EditorLine(text = "第二行"))
        state.onSelectionChanged(EditorSelection(0, 3, 3))

        state.insertImage(src = "images/a.jpg", width = 800.0, height = 600.0)

        // 图片落在第 1 行，光标应该在它**下面**（第 2 行）—— 否则插图之后接着打字会打在上面
        assertEquals("images/a.jpg", state.lines[1].imageSrc)
        assertEquals(2, state.selection.lineIndex)
        assertTrue(state.selection.isCollapsed)
        assertEquals(0, state.selection.start)

        assertEquals("只插了一张图", 1, state.lines.count { it.imageSrc != null })
        // "第二行"还在（排在图片之后），不是被顶掉
        assertEquals("第二行", state.lines[2].text)
    }

    @Test
    fun `inserting an image at the end appends an empty line for the caret`() {
        val state = state(EditorLine(text = "只有一行"))
        state.onSelectionChanged(EditorSelection(0, 4, 4))

        state.insertImage(src = "images/b.jpg", width = 100.0, height = 100.0)

        assertEquals(3, state.lines.size)
        assertEquals("images/b.jpg", state.lines[1].imageSrc)
        assertEquals(2, state.selection.lineIndex)
        assertEquals("", state.lines[2].text)
        assertTrue("新加的空行不是图片行", state.lines[2].imageSrc == null)
    }

    @Test
    fun `inserting an image into an empty new entry takes that line`() {
        // 新片段：模型就是一张空行，光标在上面
        val state = state(EditorModel.EMPTY_LINE)
        state.insertImage(src = "images/only.jpg", width = 800.0, height = 600.0)

        assertEquals("图片落在光标那一行，不留空行", 1, state.lines.count { it.imageSrc != null })
        assertEquals(0, state.lines.indexOfFirst { it.imageSrc != null })
        assertEquals("光标落到图片下面的那一行", 1, state.selection.lineIndex)
        assertEquals(2, state.lines.size)
    }

    // ------------------------------------------------------------------ 行内样式不随段落级改动丢失

    /**
     * 真机缺陷（用户 2026-09-28）：一条"待办 + 加粗/斜体/删除线/下划线"的行，
     * 切一次段落样式，四种行内样式全没了。这里走编辑器的**真实链路**
     * （模型 → Spannable 回写 EditText → 模型）把它钉住。
     */
    @Test
    fun `a paragraph style change keeps every inline style`() {
        val state = state(
            EditorLine(
                text = "styled",
                marker = EditorLine.MarkerKind.Todo,
                spans = listOf(
                    com.cov.justdiary.model.TextRun(
                        text = "styled",
                        bold = true,
                        italic = true,
                        strike = true,
                        underline = true,
                    ),
                ),
            ),
        )

        // 格式栏：段落样式 → 小标题（作用范围 = 当前行）
        state.apply { EditorModel.setBlockStyle(it, state.lineRange, ContentPartStyle.heading) }

        val line = state.lines.single()
        assertEquals(ContentPartStyle.heading, line.blockStyle)
        assertEquals("换段落样式会丢标记（与 iOS 一致）", null, line.marker)

        // 四种行内样式必须原样保留 —— 它们与段落样式是两件事
        val run = line.spans.single()
        assertTrue("加粗没了", run.bold == true)
        assertTrue("斜体没了", run.italic == true)
        assertTrue("删除线没了", run.strike == true)
        assertTrue("下划线没了", run.underline == true)

        // 再经过一次"回写平台文本 → 读回模型"（编辑器每次改样式都会走这一趟）
        val back = SpannableBridge.fromSpannable(
            SpannableBridge.toSpannable(state.lines, imageProvider = null, containerWidthPx = 600),
        ).single()
        assertTrue("往返之后加粗没了", back.spans.single().bold == true)
        assertTrue("往返之后下划线没了", back.spans.single().underline == true)

        // 落库的 runs 也带着这些样式
        val part = EditorModel.linesToParts(listOf(back)).single()
        assertEquals(listOf(true), part.runs?.map { it.bold })
        assertEquals(listOf(true), part.runs?.map { it.strike })
    }

    @Test
    fun `toggling list and todo from the format bar keeps the inline styles`() {
        val state = state(
            EditorLine(
                text = "styled",
                spans = listOf(com.cov.justdiary.model.TextRun(text = "styled", bold = true)),
            ),
        )

        // 格式栏：列表（作用范围 = 当前行）
        state.apply {
            EditorModel.setMarkerOnRange(
                it,
                state.lineRange,
                EditorLine.MarkerKind.Bullet,
                cursorIndex = state.selection.lineIndex,
            )
        }
        assertEquals(EditorLine.MarkerKind.Bullet, state.lines.single().marker)
        assertTrue("开列表时加粗没了", state.lines.single().spans.single().bold == true)

        // 再点一次取消标记
        state.apply {
            EditorModel.setMarkerOnRange(
                it,
                state.lineRange,
                EditorLine.MarkerKind.Bullet,
                cursorIndex = state.selection.lineIndex,
            )
        }
        assertEquals(null, state.lines.single().marker)
        assertTrue("取消列表时加粗没了", state.lines.single().spans.single().bold == true)
    }

    // ------------------------------------------------------------------ 图片段的居中
    @Test
    fun `an image paragraph is centred in the editable text`() {
        val lines = listOf(EditorLine(text = "正文"), EditorLine(imageSrc = "images/a.jpg", imageWidth = 100.0, imageHeight = 80.0))
        val spanned = SpannableBridge.toSpannable(lines, imageProvider = null, containerWidthPx = 600)

        val alignments = spanned.getSpans(0, spanned.length, android.text.style.AlignmentSpan::class.java)
        // 每一段都有显式对齐 span（左对齐也挂，见 SpannableBridge），居中的只有图片那一段
        val centered = alignments.filter { it.alignment == android.text.Layout.Alignment.ALIGN_CENTER }
        assertEquals("只有图片段带居中 span", 1, centered.size)
        assertEquals(android.text.Layout.Alignment.ALIGN_CENTER, centered[0].alignment)
        // 居中只覆盖图片那一段（占位字符 + 它的终止换行），不能糊到正文上
        val start = spanned.getSpanStart(centered[0])
        assertEquals("图片占位字符在正文之后", spanned.indexOf(SpannableBridge.IMAGE_PLACEHOLDER), start)
    }

    @Test
    fun `the image centring span never leaks into the model`() {
        val lines = listOf(EditorLine(imageSrc = "images/a.jpg", imageWidth = 100.0, imageHeight = 80.0))
        val spanned = SpannableBridge.toSpannable(lines, imageProvider = null, containerWidthPx = 600)

        // 居中只是**渲染规则**：`content_json` 里图片段不带 `align`（与 iOS 一致，
        // 那边段落样式也只挂在附件上，不写进 JSON）
        val back = SpannableBridge.fromSpannable(spanned)
        assertEquals(1, back.size)
        assertEquals("images/a.jpg", back[0].imageSrc)
        assertEquals(null, back[0].align)
        assertEquals(listOf(ContentPartStyle.image), EditorModel.linesToParts(back).map { it.style })
    }

    @Test
    fun `format is ignored while the ime is composing`() {
        val state = state(EditorLine(text = "正文"))
        state.onSelectionChanged(EditorSelection(0, 2, 2))
        // 组合中：改 span 会被输入法覆盖（FormatGate 的规则）
        state.setComposingForTest(true)
        state.toggleInline(InlineFlag.Bold)
        assertFalse("组合期间不动样式", state.activeFlags.bold)

        state.setComposingForTest(false)
        state.toggleInline(InlineFlag.Bold)
        assertTrue(state.activeFlags.bold)
    }

    // ------------------------------------------------------------------ 用户 2026-09-28 规则 4 / 5 / 6

    /** 规则 4：**居中的空行**再敲回车 → 按键与输入法两条路都要吃掉。 */
    @Test
    fun `rule 4 a newline on a centered blank line is swallowed on both input paths`() {
        val state = state(
            EditorLine(text = "居中正文", align = EditorModel.ALIGN_CENTER),
            EditorLine(text = "", align = EditorModel.ALIGN_CENTER),
        )
        state.onSelectionChanged(EditorSelection(1, 0, 0))

        assertTrue("按键路径（dispatchKeyEvent）要吃掉这次回车", state.blocksNewlineHere())

        // 输入法路径：commitText("\n")
        val text = SpannableStringBuilder("居中正文\n")
        text.insert(text.length, "\n")
        state.onTextReplaced(text.length - 1 until text.length)
        state.onUserText(text)
        state.consumeInsertedRange()

        assertEquals("模型不能被这次换行改掉", 2, state.lines.size)
        assertTrue("要请求回写平台文本，把那个换行抹掉", state.revision > 0)
    }

    /**
     * 规则 4 的另一半（用户 2026-09-28 原话）："**如果不是居中的时候，是不做限制的**" ——
     * 普通空行想按几个回车就按几个。
     */
    @Test
    fun `rule 4 ordinary blank lines are not limited at all`() {
        val state = state(EditorLine(text = "正文"), EditorLine(text = ""), EditorLine(text = ""))
        state.onSelectionChanged(EditorSelection(2, 0, 0))
        assertFalse("不居中的空行不拦", state.blocksNewlineHere())

        val text = SpannableStringBuilder("正文\n\n")
        text.insert(text.length, "\n")
        state.onTextReplaced(text.length - 1 until text.length)
        state.onUserText(text)
        state.consumeInsertedRange()

        assertEquals("这一次换行要生效（三个空行也随用户）", 4, state.lines.size)
    }

    /** 规则 4：有选区时回车是"替换选区"，不属于"空行上多敲一个回车"。 */
    @Test
    fun `rule 4 a newline that replaces a selection is never blocked`() {
        val state = state(EditorLine(text = "正文"), EditorLine(text = ""), EditorLine(text = ""))
        state.onSelectionChanged(EditorSelection(2, 0, 0, endLineIndex = 2))
        state.onSelectionChanged(EditorSelection(1, 0, 0, endLineIndex = 2))
        assertFalse("跨行选区上回车不能被吃掉", state.blocksNewlineHere())
    }

    /** 规则 5：选区里**任何一行**是列表/引用/待办 → 居中按钮置灰。 */
    @Test
    fun `rule 5 center is disabled when any selected line is list quote or todo`() {
        val state = state(
            EditorLine(text = "正文"),
            EditorLine(text = "引用", blockStyle = ContentPartStyle.quote),
        )
        state.onSelectionChanged(EditorSelection(0, 0, 0))
        assertTrue("正文行上居中可用", state.centerEnabled)

        state.onSelectionChanged(EditorSelection(0, 0, 0, endLineIndex = 1))
        assertFalse("选区里有一行是引用 → 置灰", state.centerEnabled)

        val list = state(EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet))
        list.onSelectionChanged(EditorSelection(0, 0, 0))
        assertFalse("列表行上置灰", list.centerEnabled)

        val todo = state(EditorLine(text = "", marker = EditorLine.MarkerKind.Todo))
        todo.onSelectionChanged(EditorSelection(0, 0, 0))
        assertFalse("待办行上置灰", todo.centerEnabled)
    }

    /** 规则 3：在**空行**上选好标题再回车，格式不能掉（末行没有字符可挂 span）。 */
    @Test
    fun `rule 3 a pending title on a blank line survives the enter`() {
        // 单行空文档 + 大标题：整份文本是 ""，这一段连终止换行都没有
        val state = state(EditorLine(text = "", blockStyle = ContentPartStyle.title))
        val text = SpannableStringBuilder("")
        state.typeAt(text, at = 0, insert = "\n")

        assertEquals("回车之后多了一行", 2, state.lines.size)
        assertEquals("上一行还是大标题", ContentPartStyle.title, state.lines[0].blockStyle)
        assertEquals("新行继承大标题（规则 3：直到用户取消格式）", ContentPartStyle.title, state.lines[1].blockStyle)
    }

    /** 规则 4 / D10：**空的引用行**回车 = 消费按键 + 结束引用（用户："按照 iOS 的结束引用"）。 */
    @Test
    fun `rule 4 an empty quote line ends the quote without adding a line`() {
        val state = state(EditorLine(text = "上一行"), EditorLine(text = "", blockStyle = ContentPartStyle.quote))
        state.onSelectionChanged(EditorSelection(1, 0, 0))

        assertTrue("空的引用行要吃掉回车", state.blocksNewlineHere())
        assertEquals("行数不变", 2, state.lines.size)
        assertEquals("这一行结束引用", ContentPartStyle.body, state.lines[1].blockStyle)
        assertTrue("模型变了要回写平台文本", state.revision > 0)
    }

    /** 规则 3：把一行的文字删空，格式留下来（iOS 的 `typingAttributes` 删除后也不重置）。 */
    @Test
    fun `rule 3 deleting a line's text keeps its paragraph style`() {
        val state = state(EditorLine(text = "标题", blockStyle = ContentPartStyle.title, align = EditorModel.ALIGN_CENTER))
        // 用户把两个字符都删掉
        val text = SpannableStringBuilder("标题")
        text.delete(0, 2)
        state.onTextReplaced(IntRange.EMPTY)
        state.onUserText(text)
        state.consumeInsertedRange()

        assertEquals(ContentPartStyle.title, state.lines.single().blockStyle)
        assertEquals(EditorModel.ALIGN_CENTER, state.lines.single().align)
    }

    /**
     * 段落 span **不能粘到下一行**（真机缺陷，用户 2026-09-28："居中的作用范围不对劲……
     * 取消的时候没有真的取消，不知道是不是和换行有关系"）。
     *
     * 段落 span 的右端**含本行的终止换行**，也就是正好落在下一行的行首。两头 `INCLUSIVE` 时，
     * 用户在下一行行首敲的字会被上一段吞进去 —— 屏幕上那一行还带着上一段的居中 / 字号，
     * 而模型里它明明是正文（解析按"span 起点 == 行首"判定，所以模型一直是对的，
     * 这正是"看着没取消、其实已经不是居中"的原因）。
     */
    @Test
    fun `a paragraph span never swallows the next line`() {
        val state = state(
            EditorLine(text = "AAA"),
            EditorLine(text = "BBB", align = EditorModel.ALIGN_CENTER),
        )
        val text = SpannableBridge.toSpannable(state.lines, imageProvider = null, containerWidthPx = 600)

        // 用户在 BBB 行尾回车、再输入 CCC（EditText 就是这么往 Spannable 里插字符的）
        text.insert(text.length, "\nCCC")
        state.onTextReplaced((text.length - 3) until text.length)
        state.onUserText(text)
        state.consumeInsertedRange()

        assertEquals("模型里 CCC 是正文", null, state.lines[2].align)
        val centered = text.getSpans(0, text.length, android.text.style.AlignmentSpan::class.java)
            .filter { it.alignment == android.text.Layout.Alignment.ALIGN_CENTER }
        assertEquals("平台上只剩一个居中 span", 1, centered.size)
        assertEquals(
            "而且它只盖到 BBB 自己那一行（不能把 CCC 吞进来）",
            7,
            text.getSpanEnd(centered[0]),
        )
    }

    /** 规则 2 / 6：六种段落格式都要能**穿过 Spannable 往返**（回写 → 解析）原样回来。 */
    @Test
    fun `rule 2 every paragraph format survives the spannable round trip`() {
        for (format in BlockFormat.entries) {
            val state = state(EditorLine(text = "文字"))
            state.apply { EditorModel.applyBlockFormat(it, state.lineRange, format) }

            val back = SpannableBridge.fromSpannable(
                SpannableBridge.toSpannable(state.lines, imageProvider = null, containerWidthPx = 600),
            ).single()
            assertEquals("$format 往返之后变了", format, BlockFormat.ofLine(back))
            assertEquals("$format 往返之后文字变了", "文字", back.text)
        }
    }

    /** 规则 6：末尾空行的视图级字号/居中跟着它的段落格式走（光标就是按这个画的）。 */
    /**
     * 段落样式是**整行**的：在带样式的行尾继续打字，必须回写一次平台文本。
     *
     * Android 的段落 span 起点包含、终点不包含 —— 在行尾插入的字符拿不到字号 / 行高 span，
     * 会按正文字号画出来（真机：切成大标题后接着打的字仍是正文大小，用户 2026-10-01 报的）。
     * `revision` 递增就是"请 EditText 按模型重画一遍"的信号。
     */
    @Test
    fun `typing at the end of a styled line rewrites the whole line`() {
        listOf(ContentPartStyle.title, ContentPartStyle.heading, ContentPartStyle.quote).forEach { style ->
            val state = state(EditorLine(text = "标题", blockStyle = style))
            val text = SpannableStringBuilder(SpannableBridge.toSpannable(state.lines))
            val before = state.revision
            state.typeAt(text, at = 2, insert = "续")
            assertTrue(
                "$style 行尾打字要触发整行回写",
                state.revision > before,
            )
            assertEquals("整行都还是那个样式", style, state.lines.single().blockStyle)
            assertEquals("标题续", state.lines.single().text)
        }

        // 正文行不必回写（字号与行高就是默认的那一套）
        val body = state(EditorLine(text = "正文"))
        val bodyText = SpannableStringBuilder(SpannableBridge.toSpannable(body.lines))
        val before = body.revision
        body.typeAt(bodyText, at = 2, insert = "续")
        assertEquals("正文行不需要额外回写", before, body.revision)

        // 在行中间插入：span 会自然包住新字符，也不回写
        val middle = state(EditorLine(text = "标题很长", blockStyle = ContentPartStyle.title))
        val middleText = SpannableStringBuilder(SpannableBridge.toSpannable(middle.lines))
        val middleBefore = middle.revision
        middle.typeAt(middleText, at = 2, insert = "续")
        assertEquals("行中间插入不需要额外回写", middleBefore, middle.revision)
    }

    @Test
    fun `rule 6 the trailing blank line drives the view level cursor metrics`() {
        val body = SpannableBridge.BlockMetrics(textSizePx = 48, lineHeightPx = 60, paragraphSpacingPx = 0)
        val title = SpannableBridge.BlockMetrics(textSizePx = 84, lineHeightPx = 100, paragraphSpacingPx = 0)
        val metrics: (String) -> SpannableBridge.BlockMetrics = { style ->
            if (style == ContentPartStyle.title) title else body
        }

        val plain = listOf(EditorLine(text = "正文"), EditorLine(text = ""))
        assertEquals("空行是正文 → 光标用正文字号", 48, SpannableBridge.tailDefaults(plain, metrics)?.textSizePx)
        assertEquals(false, SpannableBridge.tailCentered(plain))

        val titled = listOf(EditorLine(text = "标题", blockStyle = ContentPartStyle.title), EditorLine(text = "", blockStyle = ContentPartStyle.title))
        assertEquals("空行是大标题 → 光标跟着变大", 84, SpannableBridge.tailDefaults(titled, metrics)?.textSizePx)

        val centered = listOf(EditorLine(text = "正文"), EditorLine(text = "", align = EditorModel.ALIGN_CENTER))
        assertEquals("空行居中 → 光标居中", true, SpannableBridge.tailCentered(centered))

        // 末尾一行**有内容**时不需要视图级兜底：那一行自己有 span
        assertEquals(null, SpannableBridge.tailDefaults(listOf(EditorLine(text = "有内容")), metrics))
    }

    // -------------------------------------------------- 行首回车：段落 span 会"漏"到上面那个空行

    /**
     * 平台把新插入的换行**并进段落 span**（起点包含是必须的，否则行首打的字会掉出这一段），
     * 于是在行首敲回车会让上面那个空行也带上下一段的字号 / 对齐 / 引用内缩 / 列表缩进。
     *
     * 这个判据是"要不要按模型回写一次"的依据（`EditorTextState.onUserText` ⑤）。
     */
    @Test
    fun `a paragraph span leaked over an inserted newline is detected`() {
        val markerStyle = SpannableBridge.MarkerStyle(
            indentPx = 28, centerXPx = 9f, bulletDiameterPx = 8f,
            checkboxSizePx = 18f, checkboxRadiusPx = 2f, checkboxBorderPx = 2f,
            outlineColor = 0, fillColor = 0, checkColor = 0, doneTextColor = 0,
        )
        val metrics = { _: String -> SpannableBridge.BlockMetrics(textSizePx = 40, lineHeightPx = 50, paragraphSpacingPx = 0) }
        val quoteStyle = SpannableBridge.QuoteStyle(
            backgroundColor = 0, cornerRadiusPx = 4f, textInsetPx = 48, paddingVerticalPx = 6,
        )

        // 干净文本：一个都不该报（否则每次打字都会触发整篇回写）
        val clean = listOf(
            SpannableBridge.toSpannable(
                listOf(EditorLine(text = "引用", blockStyle = ContentPartStyle.quote)),
                metricsFor = metrics, quoteStyle = quoteStyle,
            ),
            SpannableBridge.toSpannable(
                listOf(EditorLine(text = "条目", marker = EditorLine.MarkerKind.Bullet)),
                metricsFor = metrics, markerStyle = markerStyle,
            ),
            SpannableBridge.toSpannable(
                listOf(EditorLine(text = "大标题", blockStyle = ContentPartStyle.title)), metricsFor = metrics,
            ),
        )
        for (sb in clean) {
            assertFalse("造出来的文本不该有漏出去的段落 span", SpannableBridge.hasLeakedParagraphSpan(sb))
        }

        // 行首回车：三种格式都会漏
        val quoted = SpannableBridge.toSpannable(
            listOf(EditorLine(text = "Q", blockStyle = ContentPartStyle.quote)),
            metricsFor = metrics, quoteStyle = quoteStyle,
        ).also { it.insert(0, "\n") }
        assertTrue("引用：内缩漏到了上面的空行", SpannableBridge.hasLeakedParagraphSpan(quoted))

        val listed = SpannableBridge.toSpannable(
            listOf(EditorLine(text = "L", marker = EditorLine.MarkerKind.Bullet)),
            metricsFor = metrics, markerStyle = markerStyle,
        ).also { it.insert(0, "\n") }
        assertTrue("列表：标记缩进漏到了上面的空行", SpannableBridge.hasLeakedParagraphSpan(listed))

        val titled = SpannableBridge.toSpannable(
            listOf(EditorLine(text = "T", blockStyle = ContentPartStyle.title)), metricsFor = metrics,
        ).also { it.insert(0, "\n") }
        assertTrue("大标题：字号漏到了上面的空行", SpannableBridge.hasLeakedParagraphSpan(titled))

        // 行尾插入换行不会漏
        val atEnd = SpannableBridge.toSpannable(
            listOf(EditorLine(text = "Q", blockStyle = ContentPartStyle.quote)),
            metricsFor = metrics, quoteStyle = quoteStyle,
        ).also { it.insert(it.length, "\n") }
        assertFalse("行尾回车不该算漏", SpannableBridge.hasLeakedParagraphSpan(atEnd))
    }

    /**
     * 用户在**引用段前**回车（光标在引用行行首）：上面那个空行**不能**是引用
     * （用户 2026-10-01："理论上没有引用的空行"），而引用文字那一行要把引用接回来。
     *
     * 这一条走完整的输入链路：平台文本 → 状态机（模型纠正 + 请求回写）。
     */
    @Test
    fun `enter at the start of a quote line leaves a body blank line and keeps the quote`() {
        val state = state(EditorLine(text = "Q", blockStyle = ContentPartStyle.quote))
        state.onSelectionChanged(EditorSelection(0, 0, 0))
        val text = SpannableStringBuilder(SpannableBridge.toSpannable(state.lines))
        val before = state.revision

        state.typeAt(text, at = 0, insert = "\n")

        assertEquals(
            "空行给正文、引用文字那一行接回引用",
            listOf("" to ContentPartStyle.body, "Q" to ContentPartStyle.quote),
            state.lines.map { it.text to it.blockStyle },
        )
        assertTrue("平台 span 漏了 → 必须按模型回写一次", state.revision > before)
    }

    /** 大标题：行首回车之后两条都还是大标题（规则 3 的"回车之后也不取消格式"）。 */
    @Test
    fun `enter at the start of a title line keeps the title on both lines`() {
        val state = state(EditorLine(text = "T", blockStyle = ContentPartStyle.title))
        state.onSelectionChanged(EditorSelection(0, 0, 0))
        val text = SpannableStringBuilder(SpannableBridge.toSpannable(state.lines))

        state.typeAt(text, at = 0, insert = "\n")

        assertEquals(
            "标题延续在两条上（空行也还是标题）",
            listOf("" to ContentPartStyle.title, "T" to ContentPartStyle.title),
            state.lines.map { it.text to it.blockStyle },
        )
    }
}
