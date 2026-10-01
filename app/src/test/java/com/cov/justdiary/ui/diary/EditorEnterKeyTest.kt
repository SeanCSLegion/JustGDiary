package com.cov.justdiary.ui.diary

import android.text.Editable
import android.text.SpannableStringBuilder
import android.text.TextWatcher
import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 回车拦截在**真实 `EditText`** 上的回归（用户 2026-09-28 规则 4 的两条输入路径）。
 *
 * `EditorModel.blocksNewline` 是纯判断，`EditorStateTest` 已经钉住了；
 * 这里补的是**平台的接线**：判断有没有真的接到按键与输入法上。
 *
 * | 路径 | 谁负责 | 这里的用例 |
 * | --- | --- | --- |
 * | 软键盘 `sendKeyEvent(KEYCODE_ENTER)` | `DiaryEditText.dispatchKeyEvent` 吃掉 | [the enter key is swallowed when the state blocks it] |
 * | 输入法 `commitText("\n")` | `EditorTextState.onUserText` 把换行退回去 | [a committed newline on a blank line is rolled back] |
 *
 * 视图按 `EditorTextArea` 的真实接法接线（`TextWatcher` → 状态机 → 版本号回写）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class EditorEnterKeyTest {

    /** 与 `EditorTextArea` 里的 watcher 同一套动作（含"平台光标 → 行内选区"那一条）。 */
    private fun wiredView(state: EditorTextState): DiaryEditText {
        val view = DiaryEditText(RuntimeEnvironment.getApplication())
        view.shouldBlockNewline = { state.blocksNewlineHere() }
        // 光标移动（`setSelection` / 点击）也要回报 —— 与 `EditorTextArea` 的接线一致
        view.onSelectionChangedListener = {
            if (!state.suppressWatcher) {
                state.onSelectionChanged(selectionOf(view.text!!, view.selectionStart, view.selectionEnd))
            }
        }
        view.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    state.onTextReplaced(if (count > 0) start until (start + count) else IntRange.EMPTY)
                }

                override fun afterTextChanged(s: Editable?) {
                    if (state.suppressWatcher) return
                    val spanned = s ?: return
                    state.onUserText(spanned)
                    state.updateComposition(spanned)
                    state.consumeInsertedRange()
                    // 平台的光标位置也要换算回行内选区（`EditorTextArea.afterTextChanged` 同此）
                    state.onSelectionChanged(selectionOf(spanned, view.selectionStart, view.selectionEnd))
                }
            },
        )
        return view
    }

    /** 扁平选区 → 行内选区（`EditorTextArea.currentSelection` 的同一套换算）。 */
    private fun selectionOf(text: android.text.Spanned, start: Int, end: Int): EditorSelection {
        val lines = SpannableBridge.fromSpannable(text)
        val a = EditorSelectionMap.locate(lines, start)
        val b = EditorSelectionMap.locate(lines, end)
        return if (a.lineIndex == b.lineIndex) {
            EditorSelection(a.lineIndex, a.offsetInLine, b.offsetInLine)
        } else {
            EditorSelection(a.lineIndex, a.offsetInLine, b.offsetInLine, b.lineIndex)
        }
    }

    /** 编辑器收到"模型变了"之后的回写（`EditorTextArea.update` 里那一步）。 */
    private fun rewrite(view: DiaryEditText, state: EditorTextState) {
        state.suppressWatcher = true
        view.setText(SpannableBridge.toSpannable(state.lines))
        view.setSelection(EditorSelectionMap.flatOffset(state.lines, state.selection.lineIndex, state.selection.start))
        state.suppressWatcher = false
    }

    @Test
    fun `the enter key is swallowed when the state blocks it`() {
        // 居中的空行：这次回车必须被吃掉（用户 2026-09-28：居中的时候不让有空行）
        val state = EditorTextState(
            listOf(
                EditorLine(text = "居中正文", align = EditorModel.ALIGN_CENTER),
                EditorLine(text = "", align = EditorModel.ALIGN_CENTER),
            ),
        )
        state.onSelectionChanged(EditorSelection(1, 0, 0))
        val view = wiredView(state)
        view.setText(SpannableStringBuilder("居中正文\n"))
        view.setSelection(5)
        state.onSelectionChanged(EditorSelection(1, 0, 0))

        val down = KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
        assertTrue("被拦的回车要返回 true（消费掉，不再往下传）", view.dispatchKeyEvent(down))
        assertEquals("文本一个字符都不能多", "居中正文\n", view.text.toString())

        // 反过来：本行有内容时必须**真的换行**（否则用户永远换不了行）
        val free = EditorTextState(listOf(EditorLine(text = "正文")))
        free.onSelectionChanged(EditorSelection(0, 2, 2))
        val freeView = wiredView(free)
        freeView.setText(SpannableStringBuilder("正文"))
        freeView.setSelection(2, 2)
        freeView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        freeView.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))
        assertEquals("能换行的时候要落到文本里", "正文\n", freeView.text.toString())
        assertEquals("模型也要跟着多一行", 2, free.lines.size)
    }

    @Test
    fun `a committed newline on a centered blank line is rolled back`() {
        val state = EditorTextState(
            listOf(
                EditorLine(text = "居中正文", align = EditorModel.ALIGN_CENTER),
                EditorLine(text = "", align = EditorModel.ALIGN_CENTER),
            ),
        )
        state.onSelectionChanged(EditorSelection(1, 0, 0))
        val view = wiredView(state)
        view.setText(SpannableStringBuilder("居中正文\n"))
        view.setSelection(view.text!!.length)

        val revisionBefore = state.revision
        // 输入法直接提交一个换行（Gboard 在某些版本上就是这么干的）
        view.text!!.insert(view.text!!.length, "\n")

        assertEquals("模型不能被这次换行改掉", 2, state.lines.size)
        assertTrue("要请求回写，把那个换行抹掉", state.revision > revisionBefore)

        // 编辑器按版本号回写之后，文本里不该留下那个换行
        rewrite(view, state)
        assertEquals("居中正文\n", view.text.toString())
    }

    @Test
    fun `a committed newline on an ordinary blank line is kept`() {
        // 不居中的空行**不受限制**（用户 2026-09-28："如果不是居中的时候，是不做限制的"）
        val state = EditorTextState(listOf(EditorLine(text = "正文"), EditorLine(text = "")))
        state.onSelectionChanged(EditorSelection(1, 0, 0))
        val view = wiredView(state)
        view.setText(SpannableStringBuilder("正文\n"))
        view.setSelection(view.text!!.length)

        view.text!!.insert(view.text!!.length, "\n")
        view.text!!.insert(view.text!!.length, "\n")

        assertEquals("想按几个空行就按几个", 4, state.lines.size)
        rewrite(view, state)
        assertEquals("正文\n\n\n", view.text.toString())
    }

    @Test
    fun `an empty quote line ends the quote when the enter is swallowed`() {
        val state = EditorTextState(
            listOf(
                EditorLine(text = "上一行"),
                EditorLine(text = "", blockStyle = com.cov.justdiary.model.ContentPartStyle.quote),
            ),
        )
        state.onSelectionChanged(EditorSelection(1, 0, 0))
        val view = wiredView(state)
        view.setText(SpannableStringBuilder("上一行\n"))
        view.setSelection(view.text!!.length)

        val consumed = view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))

        assertTrue("空的引用行要吃掉这次回车", consumed)
        assertEquals("行数不变", 2, state.lines.size)
        assertEquals(
            "而且这一行要结束引用（iOS 同）",
            com.cov.justdiary.model.ContentPartStyle.body,
            state.lines[1].blockStyle,
        )
        rewrite(view, state)
        assertEquals("文本不变", "上一行\n", view.text.toString())
    }

    /**
     * 用户 2026-10-01 定稿的第二条：**有内容的引用行回车，新行也要回到正文**
     * （引用不跟着换行延续）。
     *
     * 这一条走的是平台自己的换行路径：`EditText` 插入 `\n` → `TextWatcher` →
     * [EditorTextState.onUserText] → [EditorModel.repairAfterEnter] 把新行改成正文。
     * 已经打好的那一行仍然是引用（它是一整段引用文字），只是引用到这里结束。
     */
    @Test
    fun `a newline after a content quote line does not continue the quote`() {
        val state = EditorTextState(
            listOf(EditorLine(text = "引用", blockStyle = com.cov.justdiary.model.ContentPartStyle.quote)),
        )
        state.onSelectionChanged(EditorSelection(0, 2, 2))
        val view = wiredView(state)
        view.setText(SpannableBridge.toSpannable(state.lines))
        view.setSelection(2)

        // 有内容 → 这次回车不能被吃掉，要真的换行
        assertTrue(
            "有内容的引用行要真的换行",
            view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)),
        )
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertEquals("文本里要有这个换行", "引用\n", view.text.toString())
        assertEquals("模型要多一行", 2, state.lines.size)
        assertEquals(
            "上一行仍然是引用",
            com.cov.justdiary.model.ContentPartStyle.quote,
            state.lines[0].blockStyle,
        )
        assertEquals(
            "新行必须是正文（引用不延续）",
            com.cov.justdiary.model.ContentPartStyle.body,
            state.lines[1].blockStyle,
        )
    }

    /** 大小标题不受这条影响：回车之后仍然延续（用户 2026-09-28 规则 3）。 */
    @Test
    fun `a newline after a title line still continues the title`() {
        val state = EditorTextState(
            listOf(EditorLine(text = "标题", blockStyle = com.cov.justdiary.model.ContentPartStyle.title)),
        )
        state.onSelectionChanged(EditorSelection(0, 2, 2))
        val view = wiredView(state)
        view.setText(SpannableBridge.toSpannable(state.lines))
        view.setSelection(2)

        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
        view.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER))

        assertEquals(2, state.lines.size)
        assertEquals(
            "大标题要延续到新行",
            com.cov.justdiary.model.ContentPartStyle.title,
            state.lines[1].blockStyle,
        )
    }

    /**
     * 末尾那个**空的引用行**（文档里还有别的行）上打字：整条链路跑通 ——
     *
     * 它在平台文本里靠**补位换行**撑住段落样式（[SpannableBridge.PhantomLineSpan]），
     * 打字之后补位行要被丢掉、模型不多不少、光标停在刚打的字后面。
     */
    @Test
    fun `typing on the trailing empty quote line keeps the quote and the caret`() {
        val state = EditorTextState(
            listOf(
                EditorLine(text = "上一行"),
                EditorLine(text = "", blockStyle = com.cov.justdiary.model.ContentPartStyle.quote),
            ),
        )
        state.onSelectionChanged(EditorSelection(1, 0, 0))
        val view = wiredView(state)
        // 与 `EditorTextArea.update` 同一条回写路径（含末尾补位换行）
        rewrite(view, state)
        assertEquals("补位换行：文本比模型多一个空段落", "上一行\n\n", view.text.toString())
        assertEquals("光标在引用行的行首", 4, view.selectionStart)

        // 打一个字（平台只插入字符，其余交给 watcher）
        view.text!!.insert(view.selectionStart, "A")

        assertEquals("模型还是两行（补位行被丢掉）", 2, state.lines.size)
        assertEquals("打进去的字在这一行上", "A", state.lines[1].text)
        assertEquals(
            "这一行仍然是引用",
            com.cov.justdiary.model.ContentPartStyle.quote,
            state.lines[1].blockStyle,
        )
        assertEquals("光标跟到字后面", EditorSelection(1, 1, 1), state.selection)

        // 回写之后补位换行消失（这一行已经有内容，它自己就能挂段落样式）
        rewrite(view, state)
        assertEquals("上一行\nA", view.text.toString())
    }
}
