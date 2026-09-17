package com.cov.justdiary.ui.diary

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 编辑器撤销栈测试（方案 §5.4 的 E5）。
 *
 * iOS 侧根本没有撤销栈（每次 `apply()` 全量 `replaceCharacters` 把它摧毁了），
 * 所以这是 Android 侧的净新增能力 —— 因此更要把语义钉住。
 */
class EditorHistoryTest {

    private fun history(vararg texts: String, limit: Int = EditorHistory.DEFAULT_LIMIT): EditorHistory {
        val initial = texts.firstOrNull()?.let { listOf(EditorLine(text = it)) } ?: listOf(EditorModel.EMPTY_LINE)
        return EditorHistory(limit = limit, initial = initial).also { h ->
            texts.drop(1).forEach { text -> h.push(listOf(EditorLine(text = text))) }
        }
    }

    @Test
    fun `undo and redo move between states`() {
        val h = history("a", "b", "c")
        assertEquals("c", h.current.single().text)
        assertTrue(h.undo())
        assertEquals("b", h.current.single().text)
        assertTrue(h.undo())
        assertEquals("a", h.current.single().text)
        assertTrue(h.redo())
        assertEquals("b", h.current.single().text)
        assertTrue(h.redo())
        assertEquals("c", h.current.single().text)
    }

    @Test
    fun `undo at the beginning and redo at the end are no-ops`() {
        val h = history("only")
        assertFalse(h.canUndo)
        assertFalse(h.undo())
        assertEquals("only", h.current.single().text)
        assertFalse(h.canRedo)
        assertFalse(h.redo())
    }

    @Test
    fun `a new edit clears the redo branch`() {
        val h = history("a", "b")
        h.undo()
        assertEquals("a", h.current.single().text)
        assertTrue(h.canRedo)
        // 新的修改发生在撤销之后 → redo 分支必须被丢弃
        h.push(listOf(EditorLine(text = "a2")))
        assertFalse(h.canRedo)
        assertEquals("a2", h.current.single().text)
        assertTrue(h.undo())
        assertEquals("a", h.current.single().text)
    }

    @Test
    fun `mutate applies a transform through the history`() {
        val h = EditorHistory(initial = listOf(EditorLine(text = "文字")))
        h.mutate { lines -> EditorModel.toggleInline(lines.single(), 0, 2, InlineFlag.Bold).let { listOf(it) } }
        assertTrue(EditorModel.activeFlags(h.current.single(), 0, 2).bold)
        assertTrue(h.undo())
        assertFalse(EditorModel.activeFlags(h.current.single(), 0, 2).bold)
    }

    @Test
    fun `history depth is capped by the limit`() {
        val h = EditorHistory(limit = 3, initial = listOf(EditorLine(text = "0")))
        repeat(10) { index -> h.push(listOf(EditorLine(text = "第${index + 1}次"))) }
        assertEquals(3, h.undoDepth)
        // 只能撤销最近 3 步：当前是"第10次"，撤销三次回到"第7次"
        assertTrue(h.undo())
        assertEquals("第9次", h.current.single().text)
        assertTrue(h.undo())
        assertEquals("第8次", h.current.single().text)
        assertTrue(h.undo())
        assertEquals("第7次", h.current.single().text)
        assertFalse(h.undo())
    }

    @Test
    fun `reset makes the current state the new baseline`() {
        val h = history("a", "b")
        assertTrue(h.canUndo)
        h.reset()
        assertFalse(h.canUndo)
        assertFalse(h.canRedo)
        assertEquals("b", h.current.single().text)
    }

    @Test
    fun `reset can jump to a different baseline`() {
        val h = history("a", "b")
        h.reset(to = listOf(EditorLine(text = "重新开始")))
        assertEquals("重新开始", h.current.single().text)
        assertFalse(h.canUndo)
    }

    @Test
    fun `default initial state is one empty line`() {
        val h = EditorHistory()
        assertEquals(1, h.current.size)
        assertEquals("", h.current.single().text)
        assertFalse(h.canUndo)
    }
}
