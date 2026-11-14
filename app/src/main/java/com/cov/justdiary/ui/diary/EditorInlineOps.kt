package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.TextRun

/**
 * 从 [EditorModel] 拆出的纯函数分组；对外仍从 [EditorModel] 调用。
 */
internal object EditorInlineOps {

    /**
     * 把一组行内样式**整体**应用到 `[start, end)`（不做取反）。
     *
     * 这是"待输入样式"（typing attributes）落地的动作：用户在有光标、无选区时点了 B，
     * 之后输入的字符应当加粗 —— 由调用方在文本插入后对**插入区间**调用本函数。
     */
    fun applyInlineFlags(
        line: EditorLine,
        start: Int,
        end: Int,
        flags: InlineFlags,
    ): EditorLine {
        val text = line.text
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from == to || text.isEmpty()) return line

        val runs = materializeRuns(line)
        val rebuilt = mutableListOf<TextRun>()
        var cursor = 0
        for (run in runs) {
            val runStart = cursor
            val runEnd = cursor + run.text.length
            cursor = runEnd
            if (runEnd <= from || runStart >= to) {
                rebuilt.add(run)
                continue
            }
            if (runStart < from) rebuilt.add(run.copy(text = run.text.substring(0, from - runStart)))
            val covered = run.text.substring((from - runStart).coerceAtLeast(0), minOf(to, runEnd) - runStart)
            rebuilt.add(
                run.copy(
                    text = covered,
                    bold = flags.bold || run.bold == true,
                    italic = flags.italic || run.italic == true,
                    strike = flags.strike || run.strike == true,
                    underline = flags.underline || run.underline == true,
                ),
            )
            if (runEnd > to) rebuilt.add(run.copy(text = run.text.substring(to - runStart)))
        }
        return line.copy(spans = mergeRuns(rebuilt))
    }

    /** 勾选/取消待办项。 */
    fun setTodoDone(lines: List<EditorLine>, index: Int, done: Boolean): List<EditorLine> {
        val line = lines.getOrNull(index) ?: return lines
        if (!line.isTodoItem) return lines
        return lines.toMutableList().also { it[index] = line.copy(done = done) }
    }

    // ------------------------------------------------------------------ 行内样式

    /**
     * 在整行（或给定区间）上切换行内样式。
     *
     * 与 iOS 一致：
     *  - 已开启则关闭、未开启则开启；**是否"已开启"按区间内是否全部开启判定**（混合选区 → 开启）；
     *  - 关闭后的 run 只保留仍然开启的标记 —— 落库时不写 `false`（由 `ContentFlatten` 统一保证）。
     *
     * @param start inclusive 字符偏移
     * @param end exclusive 字符偏移；`start == end` 时不做任何修改（空选区由调用方记为"待输入样式"）
     */
    fun toggleInline(
        line: EditorLine,
        start: Int,
        end: Int,
        flag: InlineFlag,
    ): EditorLine {
        val text = line.text
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from == to) return line

        val current = activeFlags(line, from, to)
        val enable = !current.has(flag)

        val runs = materializeRuns(line)
        val rebuilt = mutableListOf<TextRun>()
        var cursor = 0
        for (run in runs) {
            val runStart = cursor
            val runEnd = cursor + run.text.length
            cursor = runEnd
            if (runEnd <= from || runStart >= to) {
                rebuilt.add(run)
                continue
            }
            // 与选区相交：先补上左侧未覆盖的部分
            if (runStart < from) {
                rebuilt.add(run.copy(text = run.text.substring(0, from - runStart)))
            }
            val covered = run.text.substring((from - runStart).coerceAtLeast(0), (minOf(to, runEnd) - runStart))
            rebuilt.add(applyFlag(run.copy(text = covered), flag, enable))
            if (runEnd > to) {
                rebuilt.add(run.copy(text = run.text.substring(to - runStart)))
            }
        }
        return line.copy(spans = mergeRuns(rebuilt), text = text)
    }

    /** 区间内**全部**开启才算开启（混合选区视为未开启，与多数编辑器的行为一致）。 */
    fun activeFlags(line: EditorLine, start: Int, end: Int): InlineFlags {
        val text = line.text
        val from = start.coerceIn(0, text.length)
        val to = end.coerceIn(from, text.length)
        if (from == to) {
            // 空选区：取光标处的样式（用于工具栏高亮）
            val at = from.coerceAtMost((text.length - 1).coerceAtLeast(0))
            return flagsAt(line, at)
        }
        var result = InlineFlags(bold = true, italic = true, strike = true, underline = true)
        for (offset in from until to) {
            val flags = flagsAt(line, offset)
            result = InlineFlags(
                bold = result.bold && flags.bold,
                italic = result.italic && flags.italic,
                strike = result.strike && flags.strike,
                underline = result.underline && flags.underline,
            )
        }
        return result
    }

    /**
     * **光标处**的样式（按钮高亮与"接下来输入的样式"都用它）。
     *
     * 规则与 UIKit 的 `typingAttributes` 一致：取光标**前一个字符**的样式
     * （在行首则取后一个）—— 于是"在加粗的字后面接着打字仍然是加粗的"。
     */
    fun caretFlags(line: EditorLine, offset: Int): InlineFlags {
        val text = line.text
        if (text.isEmpty()) return InlineFlags.NONE
        val at = offset.coerceIn(0, text.length)
        val probe = if (at == 0) 0 else at - 1
        return flagsAt(line, probe)
    }

    /** 某个字符位置的行内样式。 */
    fun flagsAt(line: EditorLine, offset: Int): InlineFlags {
        var cursor = 0
        for (run in materializeRuns(line)) {
            val end = cursor + run.text.length
            if (offset < end) {
                return InlineFlags(
                    bold = run.bold == true,
                    italic = run.italic == true,
                    strike = run.strike == true,
                    underline = run.underline == true,
                )
            }
            cursor = end
        }
        return InlineFlags.NONE
    }

    // ------------------------------------------------------------------ 倒序应用（T3）

    /**
     * 对 [indices] 里的行应用 [transform]，**从后往前**执行。
     *
     * ## 为什么必须倒序（方案 §5.4 的 T3）
     *
     * iOS 在 `toggleQuote` 里写的是 `for range in ranges.reversed()`。原因不是习惯：
     * 段落级操作会改变后续段落的位置/形态（例如把列表行摊平成多行、或把多行合并成一条列表），
     * 正序遍历时前面刚改过的行会让后面的下标整体错位，最终改错行。
     *
     * 这里的实现按 `indices.last` → `indices.first` 逆序调用 [transform]，
     * 每次都用**最新**的列表，因此即使 [transform] 改变了行数也不会错位。
     * `EditorModelTest` 里有一条对照测试：正序实现会得到错误结果。
     */
    fun applyToLines(
        lines: List<EditorLine>,
        indices: IntRange,
        transform: (EditorLine) -> EditorLine,
    ): List<EditorLine> {
        var result = lines
        for (index in indices.last downTo indices.first) {
            val line = result.getOrNull(index) ?: continue
            result = result.toMutableList().also { it[index] = transform(line) }
        }
        return result
    }

    // ------------------------------------------------------------------ run 工具

    /** 把一行摊平成 run 序列；空 spans 时用整行文本构造一个无样式 run。 */
    fun materializeRuns(line: EditorLine): List<TextRun> =
        if (line.spans.isEmpty()) {
            if (line.text.isEmpty()) emptyList() else listOf(TextRun(text = line.text))
        } else {
            val joined = line.spans.joinToString(separator = "") { it.text }
            // 存储里的 run 划分与文本不一致时（外部改过文本），以文本为准重建
            if (joined == line.text) line.spans else listOf(TextRun(text = line.text))
        }

    /** 合并相邻的同样式 run，并丢弃空 run（落库前的规范化）。 */
    fun mergeRuns(runs: List<TextRun>): List<TextRun> {
        val merged = mutableListOf<TextRun>()
        for (run in runs) {
            if (run.text.isEmpty()) continue
            val last = merged.lastOrNull()
            if (last != null && sameInlineStyle(last, run)) {
                merged[merged.size - 1] = last.copy(text = last.text + run.text)
            } else {
                merged.add(run)
            }
        }
        return merged
    }

    private fun sameInlineStyle(a: TextRun, b: TextRun): Boolean =
        (a.bold == true) == (b.bold == true) &&
            (a.italic == true) == (b.italic == true) &&
            (a.strike == true) == (b.strike == true) &&
            (a.underline == true) == (b.underline == true)

    private fun applyFlag(run: TextRun, flag: InlineFlag, enable: Boolean): TextRun = when (flag) {
        InlineFlag.Bold -> run.copy(bold = if (enable) true else null)
        InlineFlag.Italic -> run.copy(italic = if (enable) true else null)
        InlineFlag.Strike -> run.copy(strike = if (enable) true else null)
        InlineFlag.Underline -> run.copy(underline = if (enable) true else null)
    }

    /** 取 [from, to) 区间的 run 切片（[splitLine] 用）。 */
    internal fun sliceRuns(line: EditorLine, from: Int, to: Int): List<TextRun> {
        if (from >= to) return emptyList()
        val result = mutableListOf<TextRun>()
        var cursor = 0
        for (run in materializeRuns(line)) {
            val runStart = cursor
            val runEnd = cursor + run.text.length
            cursor = runEnd
            if (runEnd <= from || runStart >= to) continue
            val startInRun = (from - runStart).coerceAtLeast(0)
            val endInRun = (minOf(to, runEnd) - runStart).coerceAtMost(run.text.length)
            if (startInRun < endInRun) {
                result.add(run.copy(text = run.text.substring(startInRun, endInRun)))
            }
        }
        return mergeRuns(result)
    }
}
