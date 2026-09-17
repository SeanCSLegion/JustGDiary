package com.cov.justdiary.ui.diary

/**
 * 编辑器的撤销栈（方案 §5.4 的 **T5**）。
 *
 * ## 为什么 Android 侧要自己做一个
 *
 * iOS 的编辑器**根本没有撤销栈**（全仓无 `undoManager` / `allowsUndo`），
 * 它每次 `apply()` 都 `replaceCharacters(in: 全文范围)`，既摧毁了隐式撤销栈，
 * 又是 O(n) 的全量替换。唯一的结构化回退是"放弃修改"（回到进入编辑时的快照）。
 *
 * Android 侧把"内容快照栈"做成显式的纯逻辑：
 *  - 每次结构性修改前压栈（粒度由调用方决定）；
 *  - [undo] / [redo] 在栈之间移动；
 *  - 新的修改会清空 redo 分支（与所有编辑器的行为一致）。
 *
 * 这样撤销/重做的语义**可以被单测断言**，而不是依赖平台控件的隐式行为。
 * 这也是方案里说的"白送的功能增强"。
 */
class EditorHistory(
    private val limit: Int = DEFAULT_LIMIT,
    initial: List<EditorLine> = listOf(EditorModel.EMPTY_LINE),
) {

    private val past = ArrayDeque<List<EditorLine>>()
    private val future = ArrayDeque<List<EditorLine>>()

    /** 当前状态。 */
    var current: List<EditorLine> = initial
        private set

    val canUndo: Boolean get() = past.isNotEmpty()
    val canRedo: Boolean get() = future.isNotEmpty()

    /** 压入一个新状态；[before] 之前的当前状态进入撤销栈。 */
    fun push(next: List<EditorLine>) {
        past.addLast(current)
        while (past.size > limit) past.removeFirst()
        current = next
        future.clear()
    }

    /** 用 [transform] 变换当前状态并压栈。 */
    fun mutate(transform: (List<EditorLine>) -> List<EditorLine>) {
        push(transform(current))
    }

    /** 撤销一步；没有可撤销的返回 false。 */
    fun undo(): Boolean {
        val previous = past.removeLastOrNull() ?: return false
        future.addLast(current)
        current = previous
        return true
    }

    /** 重做一步；没有可重做的返回 false。 */
    fun redo(): Boolean {
        val next = future.removeLastOrNull() ?: return false
        past.addLast(current)
        current = next
        return true
    }

    /** 清空历史（例如保存之后：此时"当前状态"成为新的基线）。 */
    fun reset(to: List<EditorLine> = current) {
        past.clear()
        future.clear()
        current = to
    }

    /** 历史深度（测试用）。 */
    val undoDepth: Int get() = past.size
    val redoDepth: Int get() = future.size

    companion object {
        const val DEFAULT_LIMIT = 100
    }
}
