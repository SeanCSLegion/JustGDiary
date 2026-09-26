package com.cov.justdiary.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 日记页顶栏形态。
 *
 * 这一层存在的理由：阅读 / 编辑 / 多选是**三个**维度而不是两态，
 * 写成 `if (isRead) 阅读 else 编辑` 会把多选态误判成编辑态 ——
 * 而多选态在阅读态里就能进（长按卡片），两者并不互斥。
 * 这类错误不会崩，只会"某个按钮不见了"，所以必须靠断言钉住。
 */
class DiaryTopBarStateTest {

    private fun of(
        isRead: Boolean = true,
        editingIndex: Int? = null,
        selectMode: Boolean = false,
        selectedIds: Set<Long> = emptySet(),
        canEditToday: Boolean = false,
        showSearch: Boolean = false,
    ) = DiaryTopBarState.of(
        isRead = isRead,
        editingIndex = editingIndex,
        selectMode = selectMode,
        selectedIds = selectedIds,
        canEditToday = canEditToday,
        showSearch = showSearch,
    )

    // ------------------------------------------------------------------ 三态判定

    @Test
    fun `reading a day that is not today offers neither write nor edit`() {
        val bar = of(canEditToday = false)
        assertEquals(DiaryTopBarState.Mode.Read, bar.mode)
        assertFalse("历史日期不给「写日记」", bar.showWrite)
        assertTrue(bar.showSearch)
        assertTrue(bar.showShare)
    }

    @Test
    fun `reading today offers the write entry`() {
        assertTrue(of(canEditToday = true).showWrite)
    }

    @Test
    fun `editing an existing block is edit mode`() {
        val bar = of(isRead = false, editingIndex = 2)
        assertEquals(DiaryTopBarState.Mode.Edit, bar.mode)
        assertTrue(bar.canSave)
        assertFalse("编辑态没有分享", bar.showShare)
        assertFalse("编辑态没有搜索", bar.showSearch)
        assertFalse("编辑态也没有写日记入口", bar.showWrite)
    }

    @Test
    fun `creating a new block is edit mode even with a null editing index`() {
        val bar = of(isRead = false, editingIndex = null)
        assertEquals(DiaryTopBarState.Mode.Edit, bar.mode)
    }

    /**
     * 最关键的一条：**多选态是在阅读态里长出来的**，`isRead` 仍然是 `true`。
     */
    @Test
    fun `select mode wins over read even though isRead is still true`() {
        val bar = of(isRead = true, selectMode = true, selectedIds = setOf(1L, 2L))
        assertEquals(DiaryTopBarState.Mode.Select, bar.mode)
        assertEquals(2, bar.selectTitleCount)
        assertTrue(bar.canDeleteSelected)
    }

    @Test
    fun `select mode also wins over edit`() {
        // 理论上不会同时发生，但优先级必须是确定的：多选 > 编辑 > 阅读
        val bar = of(isRead = false, editingIndex = 1, selectMode = true, selectedIds = setOf(9L))
        assertEquals(DiaryTopBarState.Mode.Select, bar.mode)
    }

    // ------------------------------------------------------------------ 动作可用性

    @Test
    fun `delete is disabled when nothing is selected`() {
        val bar = of(selectMode = true, selectedIds = emptySet())
        assertEquals(0, bar.selectTitleCount)
        assertFalse("一项都没选时删除键必须禁用", bar.canDeleteSelected)
    }

    @Test
    fun `non-select modes report no select count`() {
        assertNull(of().selectTitleCount)
        assertNull(of(isRead = false, editingIndex = 0).selectTitleCount)
    }

    @Test
    fun `save is only available while editing`() {
        assertTrue(of(isRead = false, editingIndex = 0).canSave)
        assertFalse(of().canSave)
        assertFalse(of(selectMode = true).canSave)
    }

    /**
     * `showSearch` 这个标志位在编辑态可能还残留着（用户先开了搜索再点卡片进编辑），
     * 但**顶栏的搜索高亮与搜索栏都只能出现在阅读态**。
     */
    @Test
    fun `a stale showSearch flag does not leak into edit mode`() {
        val bar = of(isRead = false, editingIndex = 0, showSearch = true)
        assertEquals(DiaryTopBarState.Mode.Edit, bar.mode)
        assertFalse(bar.searchActive)
        assertFalse(bar.showSearch)
    }

    @Test
    fun `search active only when reading with the flag on`() {
        assertTrue(of(showSearch = true).searchActive)
        assertFalse(of(showSearch = false).searchActive)
    }

    // ------------------------------------------------------------------ 进度

    @Test
    fun `hit ordinal is one based and clamps out of range`() {
        assertEquals(1, DiaryProgress.hitOrdinal(0, 3))
        assertEquals(3, DiaryProgress.hitOrdinal(2, 3))
        // 片段被删掉之后 hitIndex 可能还指着旧位置
        assertEquals(0, DiaryProgress.hitOrdinal(5, 3))
        assertEquals(0, DiaryProgress.hitOrdinal(-1, 3))
        // 没有任何命中
        assertEquals(0, DiaryProgress.hitOrdinal(0, 0))
    }

    @Test
    fun `todo progress never shows nonsense numbers`() {
        assertEquals(3 to 5, DiaryProgress.todoProgress(3, 5))
        // done 超出 total（数据被外部改坏）时夹住，不显示 7/5
        assertEquals(5 to 5, DiaryProgress.todoProgress(7, 5))
        assertEquals(0 to 0, DiaryProgress.todoProgress(0, 0))
        assertFalse("没有待办时不显示进度，免得出现 0/0", DiaryProgress.showTodoProgress(0, 0))
        assertTrue(DiaryProgress.showTodoProgress(0, 3))
    }
}
