package com.cov.justdiary.ui.diary

/**
 * 格式栏 9 个按钮 → 编辑模型的**唯一接线点**。
 *
 * ## 为什么单独成一层
 *
 * 按钮的语义全在 [EditorModel] 里（互斥、作用域、方向），但"每个按钮传哪一段区间、
 * 用不用光标行定方向"是**接线**，一样会写错 —— 例如把「列表」的 `cursorIndex` 写成
 * 选区的第一行，多行选区里"开还是关"就会跟着第一行走，而不是跟着光标走。
 *
 * 以前这段接线散落在 `EditorScreen` 的六个 lambda 里，只有真机能验；现在它是一层
 * 可以被单测直接驱动的纯接线（`EditorFormatActionsTest` 把九个按钮逐个按一遍），
 * `EditorScreen` 只留下"把结果反映到界面"的部分。
 *
 * 对应关系（用户 2026-09-28 的六条规则，口径见 `04-diary.md` §3.3）：
 *
 * | 按钮 | 作用域 | 方向 |
 * | --- | --- | --- |
 * | 段落样式（大/小标题·正文） | 选区覆盖到的每一行 | 只应用，不切换（规则 2） |
 * | 居中 | 同上 | 光标那一行是否已居中（规则 4） |
 * | 引用 | 同上 | 区间内**全部**已是引用才取消（iOS `toggleQuote`） |
 * | 列表 / 待办 | 同上 | **只看光标那一行**（iOS `toggleMarker`） |
 * | B / I / S / U | 有选区 = 选区；无选区 = 待输入样式 | 整个选区统一一个方向（规则 1） |
 */
object EditorFormatActions {

    /** 段落样式菜单：大标题 / 小标题 / 正文（**引用不在菜单里**，它有专门的按钮）。 */
    fun applyBlockStyle(state: EditorTextState, style: EditorBlockStyle) {
        state.apply { lines ->
            EditorModel.applyBlockFormat(lines, state.lineRange, BlockFormat.ofPartStyle(style.partStyle))
        }
    }

    /**
     * 居中：本行**有内容** → 整行居中 / 取消；本行**空** → 改的是光标的位置（规则 4）。
     *
     * 方向按**光标那一行**取（多行选区里只要光标那一行已经居中，这一次就是取消）。
     * 列表 / 引用 / 待办行上 [EditorModel.setAlignment] 会拒绝（规则 5），
     * 格式栏也会在那三类行上把按钮置灰。
     */
    fun toggleCenter(state: EditorTextState) {
        state.apply { lines ->
            val centered = lines.getOrNull(state.selection.lineIndex)?.align == EditorModel.ALIGN_CENTER
            EditorModel.setAlignment(lines, state.lineRange, center = !centered)
        }
    }

    /** 「列表」按钮：先取消本行已有的段落格式，再套列表（规则 2/3）。 */
    fun toggleList(state: EditorTextState) = toggleMarker(state, BlockFormat.List)

    /** 「待办」按钮：同上，套待办。 */
    fun toggleTodo(state: EditorTextState) = toggleMarker(state, BlockFormat.Todo)

    /**
     * 「引用」按钮。
     *
     * 方向与列表 / 待办**不同**：引用看的是"区间内**全部**已是引用"（iOS `toggleQuote`），
     * 因为引用的作用域是整段文字，而列表 / 待办的方向只看光标那一行。
     */
    fun toggleQuote(state: EditorTextState) {
        state.apply { lines ->
            EditorModel.toggleBlockFormat(lines, state.lineRange, BlockFormat.Quote)
        }
    }

    /** B / I / S / U：有选区改选区，无选区只改"接下来输入的样式"（规则 1）。 */
    fun toggleInline(state: EditorTextState, flag: InlineFlag) = state.toggleInline(flag)

    private fun toggleMarker(state: EditorTextState, format: BlockFormat) {
        state.apply { lines ->
            EditorModel.toggleBlockFormat(
                lines,
                state.lineRange,
                format,
                // ⚠️ 方向**只看光标那一行**：选区只决定范围（iOS toggleMarker，规范 §3.2）
                cursorIndex = state.selection.lineIndex,
            )
        }
    }
}
