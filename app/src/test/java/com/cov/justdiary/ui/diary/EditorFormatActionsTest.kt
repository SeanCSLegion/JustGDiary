package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPartStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * **格式栏九个按钮**的接线回归：逐个按钮按下去，看模型变成什么。
 *
 * 这一层补的是"语义对了、接线错了"的那类缺陷 —— 比如「列表」的 toggle 方向本该看
 * **光标那一行**，接线时写成选区的第一行，多行选区里就会开反。语义在
 * `EditorModelTest`（纯模型）与 `EditorStateTest`（状态机）里已经钉住，这里钉的是
 * [EditorFormatActions]（`EditorScreen` 真正调用的那一层）：
 *
 * | 按钮 | 这里钉什么 |
 * | --- | --- |
 * | 段落样式菜单 | 作用域 = 选中的每一行；只应用不切换；空行 = 待输入样式 |
 * | 居中 | 有内容整行居中 / 空行居中 / 列表行上拒绝；再点取消 |
 * | 列表 · 待办 | 方向只看光标行；开新格式会取消旧的；再点取消 |
 * | 引用 | 整段全引用才取消；切引用会取消居中 |
 * | B / I / S / U | 无选区 = 只改待输入样式；有选区 = 改选区 |
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorFormatActionsTest {

    private fun state(vararg lines: EditorLine) = EditorTextState(lines.toList())

    /**
     * `startLine` = 选区的**起始行**（`EditorSelection.lineIndex` 的语义就是这个 ——
     * Android 只给 `getSelectionStart/End`，不暴露"焦点在哪一端"；iOS 的
     * `selectedRange.location` 同样是起始端，所以两边的"光标那一行"在多行选区上口径一致）。
     */
    private fun stateOf(vararg lines: EditorLine, startLine: Int, start: Int, end: Int = start, endLine: Int = startLine) =
        EditorTextState(lines.toList()).also {
            it.onSelectionChanged(EditorSelection(startLine, start, end, endLineIndex = endLine))
        }

    // ------------------------------------------------------------------ 段落样式菜单（规则 2/3）

    @Test
    fun `the paragraph style menu restyles the caret line`() {
        val s = stateOf(EditorLine(text = "正文"), startLine = 0, start = 2)
        EditorFormatActions.applyBlockStyle(s, EditorBlockStyle.Title)
        assertEquals(ContentPartStyle.title, s.lines.single().blockStyle)

        // 菜单是"只应用、不切换"：再点一次同一项还是它（不会掉回正文）
        EditorFormatActions.applyBlockStyle(s, EditorBlockStyle.Title)
        assertEquals(ContentPartStyle.title, s.lines.single().blockStyle)

        EditorFormatActions.applyBlockStyle(s, EditorBlockStyle.Body)
        assertEquals(ContentPartStyle.body, s.lines.single().blockStyle)
    }

    @Test
    fun `the paragraph style menu covers every selected line and drops list markers`() {
        val s = stateOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "丙"),
            startLine = 0,
            start = 0,
            end = 0,
            endLine = 1,
        )
        EditorFormatActions.applyBlockStyle(s, EditorBlockStyle.Heading)

        assertEquals(ContentPartStyle.heading, s.lines[0].blockStyle)
        assertEquals(ContentPartStyle.heading, s.lines[1].blockStyle)
        assertEquals("列表标记要跟着取消", null, s.lines[0].marker)
        assertEquals("没选中的行一个字都不许动", ContentPartStyle.body, s.lines[2].blockStyle)
    }

    @Test
    fun `the paragraph style menu on a blank line becomes the pending style`() {
        val s = stateOf(EditorLine(text = ""), startLine = 0, start = 0)
        EditorFormatActions.applyBlockStyle(s, EditorBlockStyle.Title)

        assertEquals("空行上选样式 = 记下接下来输入的样式", ContentPartStyle.title, s.lines.single().blockStyle)
    }

    // ------------------------------------------------------------------ 居中（规则 4/5）

    @Test
    fun `center toggles the line and toggles back`() {
        val s = stateOf(EditorLine(text = "正文"), startLine = 0, start = 1)
        EditorFormatActions.toggleCenter(s)
        assertEquals(EditorModel.ALIGN_CENTER, s.lines.single().align)

        EditorFormatActions.toggleCenter(s)
        assertEquals("再点一次回到左对齐", null, s.lines.single().align)
    }

    @Test
    fun `center on a blank line is remembered as the caret position`() {
        val s = stateOf(EditorLine(text = ""), startLine = 0, start = 0)
        EditorFormatActions.toggleCenter(s)
        assertEquals("空行的居中挂在模型上（光标位置靠它算）", EditorModel.ALIGN_CENTER, s.lines.single().align)
        assertTrue("格式栏据此高亮", s.currentLine.align == EditorModel.ALIGN_CENTER)
    }

    @Test
    fun `center is refused on list quote and todo lines`() {
        for (line in listOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Todo),
            EditorLine(text = "甲", blockStyle = ContentPartStyle.quote),
        )) {
            val s = stateOf(line, startLine = 0, start = 0)
            assertFalse("这一类行上居中按钮应当置灰", s.centerEnabled)
            EditorFormatActions.toggleCenter(s)
            assertEquals("即使点了也不给居中", null, s.lines.single().align)
        }
    }

    @Test
    fun `center applies to the whole selection but is refused once one line is a list`() {
        val plain = stateOf(EditorLine(text = "甲"), EditorLine(text = "乙"), startLine = 0, start = 0, endLine = 1)
        assertTrue(plain.centerEnabled)
        EditorFormatActions.toggleCenter(plain)
        assertTrue("两行都居中", plain.lines.all { it.align == EditorModel.ALIGN_CENTER })

        val mixed = stateOf(
            EditorLine(text = "甲"),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
            startLine = 0,
            start = 0,
            endLine = 1,
        )
        assertFalse("选区里有一行是列表 → 整段都不给居中", mixed.centerEnabled)
        EditorFormatActions.toggleCenter(mixed)
        assertTrue(mixed.lines.all { it.align == null })
    }

    // ------------------------------------------------------------------ 列表 / 待办（规则 2/3/5）

    @Test
    fun `list and todo replace each other and cancel other paragraph formats`() {
        val s = stateOf(EditorLine(text = "甲", blockStyle = ContentPartStyle.title, align = EditorModel.ALIGN_CENTER), startLine = 0, start = 0)

        EditorFormatActions.toggleList(s)
        assertEquals(EditorLine.MarkerKind.Bullet, s.lines.single().marker)
        assertEquals("标题要先被取消", ContentPartStyle.body, s.lines.single().blockStyle)
        assertEquals("居中也要被取消（规则 5）", null, s.lines.single().align)

        EditorFormatActions.toggleTodo(s)
        assertEquals("列表换成待办", EditorLine.MarkerKind.Todo, s.lines.single().marker)

        EditorFormatActions.toggleTodo(s)
        assertEquals("再点一次取消标记", null, s.lines.single().marker)
        assertEquals(ContentPartStyle.body, s.lines.single().blockStyle)
        assertEquals("取消之后不恢复居中", null, s.lines.single().align)
    }

    /**
     * 接线里最容易写错的一条：方向只看**光标那一行**（无选区时就是光标行；有选区时
     * 是选区的**起始行** —— iOS 的 `selectedRange.location` 也是起始端），
     * **不是**"整个选区是不是都已经带标记"。
     */
    @Test
    fun `the marker toggle direction follows the cursor line not every selected line`() {
        // 选区从"已经是列表"的那一行开始 → 方向是"取消"（即使后面跟着普通正文）
        val off = stateOf(
            EditorLine(text = "甲", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
            EditorLine(text = "丙"),
            startLine = 0,
            start = 0,
            end = 0,
            endLine = 2,
        )
        EditorFormatActions.toggleList(off)
        assertTrue("起始行已是列表 → 整个选区取消列表", off.lines.all { it.marker == null })

        // 选区从普通正文那一行开始 → 方向是"开启"
        val on = stateOf(
            EditorLine(text = "甲"),
            EditorLine(text = "乙", marker = EditorLine.MarkerKind.Bullet),
            startLine = 0,
            start = 0,
            end = 0,
            endLine = 1,
        )
        EditorFormatActions.toggleList(on)
        assertTrue(
            "起始行是正文 → 整个选区都变成列表",
            on.lines.all { it.marker == EditorLine.MarkerKind.Bullet },
        )
    }

    // ------------------------------------------------------------------ 引用（规则 2/5）

    @Test
    fun `quote toggles the line and cancels centering`() {
        val s = stateOf(EditorLine(text = "正文", align = EditorModel.ALIGN_CENTER), startLine = 0, start = 0)
        EditorFormatActions.toggleQuote(s)
        assertEquals(ContentPartStyle.quote, s.lines.single().blockStyle)
        assertEquals("切引用要取消居中", null, s.lines.single().align)

        EditorFormatActions.toggleQuote(s)
        assertEquals("再点一次取消引用", ContentPartStyle.body, s.lines.single().blockStyle)
        assertEquals("取消之后不恢复居中", null, s.lines.single().align)
    }

    @Test
    fun `quote only cancels when every selected line is already quoted`() {
        val mixed = stateOf(
            EditorLine(text = "甲", blockStyle = ContentPartStyle.quote),
            EditorLine(text = "乙"),
            startLine = 0,
            start = 0,
            endLine = 1,
        )
        EditorFormatActions.toggleQuote(mixed)
        assertTrue("混合选区 → 全部变引用", mixed.lines.all { it.blockStyle == ContentPartStyle.quote })

        EditorFormatActions.toggleQuote(mixed)
        assertTrue("全引用 → 全部取消", mixed.lines.all { it.blockStyle == ContentPartStyle.body })
    }

    // ------------------------------------------------------------------ B / I / S / U（规则 1）

    @Test
    fun `inline buttons with a selection change the selection only`() {
        val s = stateOf(EditorLine(text = "一二三四"), startLine = 0, start = 1, end = 3)
        EditorFormatActions.toggleInline(s, InlineFlag.Bold)

        val runs = s.lines.single().spans
        assertEquals(listOf("一", "二三", "四"), runs.map { it.text })
        assertTrue("只有选区那一段变粗", runs[1].bold == true)
        assertTrue("格式栏据此高亮", s.activeFlags.bold)
    }

    @Test
    fun `inline buttons with a caret only change what is typed next`() {
        val s = stateOf(EditorLine(text = "已经写好的"), startLine = 0, start = 2)
        EditorFormatActions.toggleInline(s, InlineFlag.Underline)

        assertTrue("按钮亮起", s.activeFlags.underline)
        assertTrue("已经写好的字一个都不能动", s.lines.single().spans.isEmpty())

        // 接着输入的字要带上样式（走真实链路：插入 → watcher → 状态机）
        val text = android.text.SpannableStringBuilder("已经写好的")
        text.insert(2, "新")
        s.onTextReplaced(2 until 3)
        s.onUserText(text)
        s.consumeInsertedRange()

        val runs = s.lines.single().spans
        assertEquals(listOf("已经", "新", "写好的"), runs.map { it.text })
        assertTrue(runs[1].underline == true)
    }
}
