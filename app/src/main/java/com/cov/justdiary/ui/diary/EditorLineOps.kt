package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentPartStyle

/**
 * 从 [EditorModel] 拆出的纯函数分组；对外仍从 [EditorModel] 调用。
 */
internal object EditorLineOps {

    // ------------------------------------------------------------------ 行的增删（Enter / Backspace）

    /**
     * 在行的 [offset] 处拆成两行（Enter）。样式、对齐、标记与行内样式都继承给新行。
     *
     * 与 iOS 的差异（有意修正 D8）：iOS 在"空段落 + 居中"时**直接吞掉换行**
     * （`shouldBlockNewline`），用户无法在居中段落后面另起一行。这里允许换行，
     * 只是新行继承居中 —— 吞掉按键属于"按键无响应"，比多一个空行更难理解。
     */
    fun splitLine(lines: List<EditorLine>, index: Int, offset: Int): List<EditorLine> {
        val line = lines.getOrNull(index) ?: return lines
        val at = offset.coerceIn(0, line.text.length)
        val left = line.copy(text = line.text.substring(0, at), spans = EditorInlineOps.sliceRuns(line, 0, at))
        val right = line.copy(
            text = line.text.substring(at),
            spans = EditorInlineOps.sliceRuns(line, at, line.text.length),
            // 待办的新行默认未勾选（与 iOS 的新待办项一致）
            done = false,
        )
        return lines.toMutableList().also {
            it[index] = left
            it.add(index + 1, right)
        }
    }

    /** 在第 [index] 行之后插入一个空行（Enter 到末尾、或列表里新增一项）。 */
    fun insertLineAfter(lines: List<EditorLine>, index: Int): List<EditorLine> {
        val line = lines.getOrNull(index) ?: return lines
        val newLine = EditorLine(
            blockStyle = line.blockStyle,
            align = line.align,
            marker = line.marker,
            done = false,
        )
        return lines.toMutableList().also { it.add(index + 1, newLine) }
    }

    /**
     * 把第 [index] 行并入上一行（Backspace 在行首）。
     *
     * 列表/待办里删除一个空条目 = 退出列表（合并后的行不再带标记），
     * 这样"空项目符号按退格"就能回到正文 —— 与 iOS 上用户的实际预期一致。
     */
    fun mergeWithPrevious(lines: List<EditorLine>, index: Int): List<EditorLine> {
        if (index <= 0 || index >= lines.size) return lines
        val previous = lines[index - 1]
        val current = lines[index]
        val merged = if (current.text.isEmpty() && current.marker == null) {
            previous
        } else {
            previous.copy(
                text = previous.text + current.text,
                spans = EditorModel.mergeRuns(EditorModel.materializeRuns(previous) + EditorModel.materializeRuns(current)),
            )
        }
        return lines.toMutableList().also {
            it[index - 1] = merged
            it.removeAt(index)
        }
    }

    // ------------------------------------------------------------------ 图片

    /**
     * 图片在编辑器里的表示。
     *
     * Android 侧把图片作为**独立一行**的附件渲染（iOS 是正文里的文本附件）。
     * 落库时由 [linesToParts] 转成 `ContentPartStyle.image` —— 注意图片行不参与列表分组。
     *
     * ## 一行一张图
     *
     * 图片**永远独占一行**（这就是"每一行只允许放一张图片"的落点）：
     * - 光标所在行是**空行**（没有文字、没有标记）→ 图片就落在这张空行上，
     *   不额外留一行空白（iOS 是在光标处原地插入附件，语义相同）；
     * - 其它情况 → 图片插在光标行**下面**新开的一行，正文一个字都不动。
     *
     * 返回值里图片行的下标由 [EditorModel.imageLineIndexAfterInsert] 给出（插图后要把光标放到它下面）。
     */
    fun insertImageLine(
        lines: List<EditorLine>,
        index: Int,
        src: String,
        width: Double? = null,
        height: Double? = null,
    ): List<EditorLine> {
        val image = EditorLine(imageSrc = src, imageWidth = width, imageHeight = height)
        val target = index.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val current = lines.getOrNull(target)
        return lines.toMutableList().also { result ->
            if (current != null && isBlankEditableLine(current)) {
                result[target] = image
            } else {
                result.add((target + 1).coerceIn(0, lines.size), image)
            }
        }
    }

    /** 插图之后，图片行落在第几行（光标要去它下面那一行）。 */
    fun imageLineIndexAfterInsert(lines: List<EditorLine>, caretIndex: Int): Int {
        if (lines.isEmpty()) return 0
        val target = caretIndex.coerceIn(0, lines.size - 1)
        val current = lines.getOrNull(target)
        return if (current != null && isBlankEditableLine(current)) target else target + 1
    }

    /**
     * 是不是一张"什么内容都没有"的可编辑行。
     *
     * 图片行不算（不能在图片行上再压一张图）、带标记的空条目不替换（用户可能正要输入条目）。
     */
    private fun isBlankEditableLine(line: EditorLine): Boolean =
        line.imageSrc == null && line.marker == null && line.text.isEmpty()
}
