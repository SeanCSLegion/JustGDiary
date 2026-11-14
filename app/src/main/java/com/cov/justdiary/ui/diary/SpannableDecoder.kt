package com.cov.justdiary.ui.diary

import android.graphics.Typeface
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.cov.justdiary.model.ContentPartStyle

/**
 * `Spannable` → 行模型（编辑器唯一的解码入口）。
 *
 * 从 [SpannableBridge] 拆出：只负责"读"平台文本，段落语义的 span 类型在
 * [EditorSpans] 里，写回平台文本走 [SpannableEncoder]。
 */

internal object SpannableDecoder {

/**
 * `Spannable` → 行模型。
 *
 * 与 iOS `PartsCodec.parts(from:)` 的解析规则一致：
 *  - 按 `\n` 切行，空白行丢弃（但保留至少一行，编辑器不能没有可编辑位置）；
 *  - 行首的 `• ` / `☐ ` / `☑ ` 前缀决定标记与勾选状态；
 *  - 块样式优先读 [BlockStyleSpan]；**没有字号推断这条兜底路径**；
 *  - 行内样式按 span 的实际覆盖区间切 run，并且**只记录开启的项**。
 *
 * 唯一的例外是**补位换行**（[PhantomLineSpan]）：末尾空引用行为了能挂段落样式而多出来的
 * 那个空段落不属于模型，这里按标记把它丢掉（标记必须正好落在文本末端才算数 ——
 * 编辑过程中零长度 span 可能被留在别处，那种情况一律不当补位行）。
 */
fun fromSpannable(text: Spanned): List<EditorLine> {
    if (text.isEmpty()) return listOf(EditorModel.EMPTY_LINE)

    val lines = mutableListOf<EditorLine>()
    var lineStart = 0
    while (lineStart <= text.length) {
        var lineEnd = lineStart
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        if (lineStart == lineEnd && lineStart == text.length && isPhantomLine(text)) {
            break
        }
        // **空行必须保留**：它是"光标所在的那一段"。
        // 以前这里丢掉空行，于是按回车后新行在模型里根本不存在 —— 工具栏会把操作
        // 落到**上一行**（真机缺陷）。落库时空白行由 `linesToParts` 丢弃，两者不冲突。
        lines.add(parseLine(text, lineStart, lineEnd))
        lineStart = lineEnd + 1
        if (lineEnd >= text.length) break
    }
    if (lines.isEmpty()) lines.add(EditorModel.EMPTY_LINE)
    return lines
}

/** 文本末端那个空段落是不是**补位行**（[PhantomLineSpan]，见 [toSpannable]）。 */
fun isPhantomLine(text: Spanned): Boolean {
    if (text.isEmpty()) return false
    val end = text.length
    return text.getSpans(end, end, PhantomLineSpan::class.java)
        .any { text.getSpanStart(it) == end && text.getSpanEnd(it) == end }
}

/**
 * 段落 span 用到的全部类型（[hasLeakedParagraphSpan] 逐个查；补位行标记不算段落样式）。
 */
private val ParagraphSpanKinds: List<Class<*>> = listOf(
    BlockStyleSpan::class.java,
    BlockMetricsSpan::class.java,
    android.text.style.AlignmentSpan::class.java,
    android.text.style.LeadingMarginSpan::class.java,
)

/**
 * 平台文本里有没有**被新插入的换行漏过去**的段落 span。
 *
 * ## 什么时候会漏
 *
 * 段落 span 的起点是**包含**的（[ParagraphSpanFlags]，否则在行首打的字会掉出这一段）。
 * 于是把光标放在某一行行首敲回车时，新插入的 `\n` 落进了 span 里面：段落字号 / 对齐 /
 * 引用内缩 / 列表缩进一起"漏"到了上面那个**空行**上（Robolectric 实测：引用 `[0,2)`、
 * 大标题 `[0,2)`、列表缩进 `[0,4)`，都把空行连同下一段一起盖住）。
 *
 * 模型侧的样式归属由 `EditorModel.repairSplitAtParagraphStart` 纠正；平台的 span 只能靠
 * **回写一次**（`EditorTextState.revision`）清掉 —— 这个判据就是"该不该回写"。
 *
 * 判据：某个段落 span 的**起点正好落在一条空行的行首**，但终点越过了这条空行的终止换行
 * （也就是它同时盖住了下一段）。正常的段落 span 一定止于自己那一段的终止换行，因此不会误报
 * （[toSpannable] 造出来的文本永远不满足这个条件，回写一次之后就不再成立）。
 */
fun hasLeakedParagraphSpan(text: Spanned): Boolean {
    if (text.isEmpty()) return false
    var lineStart = 0
    while (lineStart <= text.length) {
        var lineEnd = lineStart
        while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
        // 只看**空行**（有文字的行不受影响：它的样式归属本来就看行首有没有 span）
        if (lineEnd == lineStart && lineEnd < text.length) {
            val paragraphEnd = lineEnd + 1
            val queryEnd = (lineStart + 1).coerceAtMost(text.length)
            for (kind in ParagraphSpanKinds) {
                val leaked = text.getSpans(lineStart, queryEnd, kind).any {
                    text.getSpanStart(it) == lineStart && text.getSpanEnd(it) > paragraphEnd
                }
                if (leaked) return true
            }
        }
        lineStart = lineEnd + 1
        if (lineEnd >= text.length) break
    }
    return false
}

private fun parseLine(text: Spanned, start: Int, end: Int): EditorLine {
    val raw = text.subSequence(start, end).toString()
    // 图片行：src 挂在 ImagePayloadSpan 上
    text.getSpans(start, end, ImagePayloadSpan::class.java).firstOrNull()?.let { span ->
        return EditorLine(
            text = "",
            imageSrc = span.src,
            imageWidth = span.width,
            imageHeight = span.height,
        )
    }

    // 标记前缀
    var marker: EditorLine.MarkerKind? = null
    var done = false
    var contentStart = start
    when {
        raw.startsWith(EditorModel.MARKER_BULLET) -> {
            marker = EditorLine.MarkerKind.Bullet
            contentStart = start + EditorModel.MARKER_BULLET.length
        }

        raw.startsWith(EditorModel.MARKER_TODO_DONE) -> {
            marker = EditorLine.MarkerKind.Todo
            done = true
            contentStart = start + EditorModel.MARKER_TODO_DONE.length
        }

        raw.startsWith(EditorModel.MARKER_TODO_OPEN) -> {
            marker = EditorLine.MarkerKind.Todo
            contentStart = start + EditorModel.MARKER_TODO_OPEN.length
        }
    }

    val content = text.subSequence(contentStart, end).toString()

    // 块样式：读自定义 span；列表/待办行按正文处理（与 iOS 的 EditorBlockStyle 一致）
    // 段落样式/对齐：认**起点正好落在本行行首**的那个 span。
    // （段落 span 的右端含终止换行，相邻两行会互相"够到"彼此的边界，按起点认最稳。）
    val blockStyle = if (marker != null) {
        ContentPartStyle.body
    } else {
        text.getSpans(start, end.coerceAtLeast(start) + 1, BlockStyleSpan::class.java)
            .firstOrNull { text.getSpanStart(it) == start }
            ?.style
            ?: ContentPartStyle.body
    }

    val centered = freeStartSpans(text, start, android.text.style.AlignmentSpan::class.java)
        .any { it.alignment == android.text.Layout.Alignment.ALIGN_CENTER }

    return EditorLine(
        text = content,
        blockStyle = blockStyle,
        align = if (centered) EditorModel.ALIGN_CENTER else null,
        spans = extractRuns(text, contentStart, end),
        marker = marker,
        done = done,
    )
}

/** 按行内 span 的实际覆盖区间把内容切成 run；只记录**开启**的样式。 */
private fun extractRuns(text: Spanned, start: Int, end: Int): List<com.cov.justdiary.model.TextRun> {
    if (start >= end) return emptyList()
    val boundaries = sortedSetOf(start, end)
    val styleSpans = text.getSpans(start, end, StyleSpan::class.java)
    val strikeSpans = text.getSpans(start, end, StrikethroughSpan::class.java)
    val underlineSpans = text.getSpans(start, end, UnderlineSpan::class.java)
    for (span in styleSpans) {
        boundaries.add(text.getSpanStart(span).coerceIn(start, end))
        boundaries.add(text.getSpanEnd(span).coerceIn(start, end))
    }
    for (span in strikeSpans) {
        boundaries.add(text.getSpanStart(span).coerceIn(start, end))
        boundaries.add(text.getSpanEnd(span).coerceIn(start, end))
    }
    for (span in underlineSpans) {
        boundaries.add(text.getSpanStart(span).coerceIn(start, end))
        boundaries.add(text.getSpanEnd(span).coerceIn(start, end))
    }

    val ordered = boundaries.toList()
    val runs = mutableListOf<com.cov.justdiary.model.TextRun>()
    for (i in 0 until ordered.size - 1) {
        val from = ordered[i]
        val to = ordered[i + 1]
        if (from >= to) continue
        val sub = text.subSequence(from, to).toString()
        if (sub.isEmpty()) continue
        val bold = styleSpans.any {
            covers(it, text, from, to) &&
                (it.style == Typeface.BOLD || it.style == Typeface.BOLD_ITALIC)
        }
        val italic = styleSpans.any {
            covers(it, text, from, to) &&
                (it.style == Typeface.ITALIC || it.style == Typeface.BOLD_ITALIC)
        }
        runs.add(
            com.cov.justdiary.model.TextRun(
                text = sub,
                bold = if (bold) true else null,
                italic = if (italic) true else null,
                strike = if (strikeSpans.any { covers(it, text, from, to) }) true else null,
                underline = if (underlineSpans.any { covers(it, text, from, to) }) true else null,
            ),
        )
    }
    return EditorModel.mergeRuns(runs)
}

/** 取"起点落在 [start]"的 span（段落级 span 的边界会互相够到，按起点认最稳）。 */
private fun <T : Any> freeStartSpans(text: Spanned, start: Int, kind: Class<T>): List<T> =
    text.getSpans(start, (start + 1).coerceAtMost(text.length), kind)
        .filter { text.getSpanStart(it) == start }

private fun covers(span: Any, text: Spanned, from: Int, to: Int): Boolean {
    val spanStart = text.getSpanStart(span)
    val spanEnd = text.getSpanEnd(span)
    return spanStart <= from && spanEnd >= to
}
}
