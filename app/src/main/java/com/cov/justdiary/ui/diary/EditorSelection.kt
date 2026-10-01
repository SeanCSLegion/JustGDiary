package com.cov.justdiary.ui.diary

/**
 * 选区在"行模型"里的表示：第几行、行内 `[start, end)` 偏移。
 *
 * 行内偏移**不含标记前缀**（`• ` / `☐ ` / `☑ `）—— 前缀只存在于可编辑文本里，
 * 模型层看不到它们。这一层负责在有前缀的扁平文本与无前缀的行模型之间换算。
 */
data class EditorSelection(
    val lineIndex: Int,
    val start: Int,
    val end: Int,
    /** 跨行选区时的**结束行**；与 [lineIndex] 相同表示单行选区。 */
    val endLineIndex: Int = lineIndex,
) {
    /** 是否是一个空选区（光标）。 */
    val isCollapsed: Boolean get() = lineIndex == endLineIndex && start == end

    /** 选区覆盖的**整段**范围（段落级操作用：规范 §3.2「选中的每一整段」）。 */
    val lineRange: IntRange get() = lineIndex..maxOf(lineIndex, endLineIndex)

    companion object {
        val NONE = EditorSelection(0, 0, 0)
    }
}

/** 扁平文本里的位置。 */
data class LinePosition(val lineIndex: Int, val offsetInLine: Int)

/**
 * 扁平字符偏移 ↔ 行内偏移的换算。
 *
 * ## 为什么单独成一层
 *
 * `EditText` 只给一个扁平字符偏移；而编辑器的所有结构操作都以"第几行的第几个字符"为单位。
 * 这个换算有三个容易错的地方，全部在这里钉住：
 *
 * 1. **每一行在可编辑文本里都带标记前缀**（列表 `• `、待办 `☐ `/`☑ `），
 *    换算时必须把它算进行长，否则光标一进列表就整体偏移；
 * 2. **行之间还有一个 `\n`**，最后一行之后没有；
 * 3. **偏移可能越界**（输入法、程序化设值、`setSelection` 都可能给出超出范围的值），
 *    必须夹住而不是抛异常。
 */
object EditorSelectionMap {

    /** 一行在可编辑文本里的长度（含标记前缀）。 */
    fun displayLength(line: EditorLine): Int = line.displayText.length

    /** 全部行拼起来的总长度（含行间的 `\n`）。 */
    fun totalLength(lines: List<EditorLine>): Int {
        if (lines.isEmpty()) return 0
        return lines.sumOf { displayLength(it) } + (lines.size - 1)
    }

    /**
     * 扁平偏移 → (行号, 行内偏移)。
     *
     * 行内偏移是**去掉标记前缀之后**的偏移，因此可以直接用于 [EditorModel] 的区间操作。
     */
    fun locate(lines: List<EditorLine>, flatOffset: Int): LinePosition {
        if (lines.isEmpty()) return LinePosition(0, 0)
        val clamped = flatOffset.coerceIn(0, totalLength(lines))

        var cursor = 0
        for ((index, line) in lines.withIndex()) {
            val length = displayLength(line)
            if (clamped <= cursor + length) {
                val inDisplay = clamped - cursor
                return LinePosition(index, stripMarker(line, inDisplay))
            }
            // 跳过这一行与它的 '\n'
            cursor += length + 1
        }
        val lastIndex = lines.size - 1
        return LinePosition(lastIndex, lines[lastIndex].text.length)
    }

    /**
     * (行号, 行内偏移) → 扁平偏移。
     *
     * 与 [locate] 互逆（在合法输入下）。
     */
    fun flatOffset(lines: List<EditorLine>, lineIndex: Int, offsetInLine: Int): Int {
        if (lines.isEmpty()) return 0
        val index = lineIndex.coerceIn(0, lines.size - 1)
        var cursor = 0
        for (i in 0 until index) cursor += displayLength(lines[i]) + 1
        val line = lines[index]
        val display = (offsetInLine + markerLength(line)).coerceIn(0, displayLength(line))
        return cursor + display
    }

    /** 把选区规范化到合法范围（起点不大于终点、行号夹住）。 */
    fun normalize(selection: EditorSelection, lines: List<EditorLine>): EditorSelection {
        if (lines.isEmpty()) return EditorSelection.NONE
        val first = selection.lineIndex.coerceIn(0, lines.size - 1)
        val last = selection.endLineIndex.coerceIn(first, lines.size - 1)
        val firstMax = lines[first].text.length
        val lastMax = lines[last].text.length
        val a = selection.start.coerceIn(0, firstMax)
        val b = selection.end.coerceIn(0, lastMax)
        // 同一行内反着选（2→1）要交换；跨行选区按"从前往后"理解
        // （EditText 给的两端已经排好序，行与行之间不做交换）
        return if (first == last && a > b) {
            EditorSelection(first, b, a, last)
        } else {
            EditorSelection(first, a, b, last)
        }
    }

    /**
     * 选区在每一行上覆盖的字符区间（跨行选区的行内样式用）。
     *
     * 首行从 `start` 到行尾、末行从行首到 `end`、中间行整行。
     */
    fun coveredRanges(selection: EditorSelection, lines: List<EditorLine>): List<Pair<Int, IntRange>> {
        val normalized = normalize(selection, lines)
        return (normalized.lineIndex..normalized.endLineIndex).mapNotNull { index ->
            val line = lines.getOrNull(index) ?: return@mapNotNull null
            val from = if (index == normalized.lineIndex) normalized.start else 0
            val to = if (index == normalized.endLineIndex) normalized.end else line.text.length
            index to (from.coerceIn(0, line.text.length)..to.coerceIn(0, line.text.length))
        }
    }

    private fun markerLength(line: EditorLine): Int = displayLength(line) - line.text.length

    private fun stripMarker(line: EditorLine, displayOffset: Int): Int =
        (displayOffset - markerLength(line)).coerceIn(0, line.text.length)
}
