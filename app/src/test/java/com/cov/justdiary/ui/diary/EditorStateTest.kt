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
}
