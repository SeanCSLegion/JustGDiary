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

    /** 与 `EditorTextArea` 里的 watcher 同一套动作（少一条"光标回调"：这里用不到）。 */
    private fun wiredView(state: EditorTextState): DiaryEditText {
        val view = DiaryEditText(RuntimeEnvironment.getApplication())
        view.shouldBlockNewline = { state.blocksNewlineHere() }
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
                }
            },
        )
        return view
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
}
