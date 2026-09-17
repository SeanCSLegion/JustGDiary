package com.cov.justdiary.ui.diary

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import android.graphics.Typeface
import com.cov.justdiary.model.ContentPartStyle

/**
 * [EditorLine] ↔ `Spannable` 的桥接层。
 *
 * ## 为什么这样设计
 *
 * iOS 把"块类型"挂在 `NSAttributedString` 的**自定义键**上（`.diaryBlockStyle`），
 * 并把"未缩放的设计字号"挂在 `.diaryDesignSize` 上 —— 因为 UIKit 会按固定的一组键
 * 从光标处重新同步 `typingAttributes`，**自定义键会在输入第二个字符起丢失**，
 * 于是它不得不加一层"字号精确匹配"的兜底（方案 §5.4 的 E1/E2）。
 *
 * Android 的 `Spannable` 天然支持自定义 `ParagraphStyle`，因此：
 *
 * - 块类型用 [BlockStyleSpan]（一个继承自 `ParagraphStyle` 的自定义 span）承载，
 *   **不依赖字号**，从根上不存在"用绘制字号反解块样式"的问题；
 * - 字号完全不参与：它的唯一来源是块样式 + 系统字体缩放。
 *
 * ## 标记的表达
 *
 * 列表与待办的标记（`• ` / `☐ ` / `☑ `）在可编辑文本里是**真实字符**，
 * 与 iOS 的"文本附件"语义等价 —— `EditorModel` 负责剥掉前缀并合并连续条目，
 * 这里负责在往返时保持前缀与模型一致。
 */
object SpannableBridge {

    /** 承载块样式的自定义段落 span（对应 iOS 的 `.diaryBlockStyle`）。 */
    class BlockStyleSpan(val style: String) : android.text.style.ParagraphStyle

    /**
     * 承载图片来源的图片 span（对应 iOS 的 `PayloadAttachment`）。
     *
     * `src` 与宽高挂在 span 上，因此图片信息**能穿过 EditText 往返**；
     * 没有图片可画时（测试/无 provider）用一个 1×1 的透明位图占位，
     * 但 `src` 依然在 —— 这保证了"插入图片 → 保存"不会丢图。
     */
    class ImagePayloadSpan(
        val src: String,
        val width: Double?,
        val height: Double?,
        drawable: android.graphics.drawable.Drawable,
    ) : android.text.style.ImageSpan(drawable)

    /**
     * 行模型 → `Spannable`。
     *
     * 每行的显示文本（含标记前缀）用 `\n` 连接，然后按行偏移挂 span。
     */
    fun toSpannable(
        lines: List<EditorLine>,
        imageProvider: ((String) -> android.graphics.Bitmap?)? = null,
        /** 可用宽度（像素）：图片按"自然宽度但不超宽"显示，见 [ImageDisplaySize]。 */
        containerWidthPx: Int = 0,
    ): SpannableStringBuilder {
        val builder = SpannableStringBuilder()
        val lineStarts = mutableListOf<Pair<Int, Int>>() // (start, end)，不含结尾的 \n

        lines.forEachIndexed { index, line ->
            // 图片行：插入一个占位字符并把 src 挂在 ImagePayloadSpan 上
            if (line.imageSrc != null) {
                val start = builder.length
                builder.append(IMAGE_PLACEHOLDER)
                val end = builder.length
                lineStarts.add(start to end)
                val bitmap = imageProvider?.invoke(line.imageSrc)
                val drawable = if (bitmap != null) {
                    android.graphics.drawable.BitmapDrawable(null, bitmap)
                } else {
                    android.graphics.drawable.ColorDrawable(android.graphics.Color.TRANSPARENT)
                }
                // 与 iOS `DiaryImageView` 同规则：自然宽度、不超可用宽度、不小于 40，高度按比例
                val (imageW, imageH) = ImageDisplaySize.fit(
                    containerWidthPx = containerWidthPx,
                    storedWidth = line.imageWidth,
                    storedHeight = line.imageHeight,
                    fallbackWidth = bitmap?.width ?: IMAGE_PLACEHOLDER_PX,
                    fallbackHeight = bitmap?.height ?: IMAGE_PLACEHOLDER_PX,
                )
                drawable.setBounds(0, 0, imageW, imageH)
                builder.setSpan(
                    ImagePayloadSpan(line.imageSrc, line.imageWidth, line.imageHeight, drawable),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                if (index < lines.size - 1) builder.append('\n')
                return@forEachIndexed
            }

            val text = line.displayText
            val start = builder.length
            builder.append(text)
            val end = builder.length
            lineStarts.add(start to end)

            if (end > start) {
                // 段落级：块样式与对齐
                builder.setSpan(
                    BlockStyleSpan(line.blockStyle),
                    start,
                    end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
                if (line.align == EditorModel.ALIGN_CENTER) {
                    builder.setSpan(
                        android.text.style.AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_CENTER),
                        start,
                        end,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                    )
                }
                // 行内样式：按 run 的字符偏移挂 span
                var cursor = start
                for (run in EditorModel.materializeRuns(line)) {
                    val runEnd = cursor + run.text.length
                    applyInlineSpans(builder, cursor, runEnd, run)
                    cursor = runEnd
                }
            }

            if (index < lines.size - 1) builder.append('\n')
        }
        return builder
    }

    private fun applyInlineSpans(builder: SpannableStringBuilder, start: Int, end: Int, run: com.cov.justdiary.model.TextRun) {
        if (end <= start) return
        val flag = Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        val style = when {
            run.bold == true && run.italic == true -> Typeface.BOLD_ITALIC
            run.bold == true -> Typeface.BOLD
            run.italic == true -> Typeface.ITALIC
            else -> null
        }
        if (style != null) builder.setSpan(StyleSpan(style), start, end, flag)
        if (run.strike == true) builder.setSpan(StrikethroughSpan(), start, end, flag)
        if (run.underline == true) builder.setSpan(UnderlineSpan(), start, end, flag)
    }

    /**
     * `Spannable` → 行模型。
     *
     * 与 iOS `PartsCodec.parts(from:)` 的解析规则一致：
     *  - 按 `\n` 切行，空白行丢弃（但保留至少一行，编辑器不能没有可编辑位置）；
     *  - 行首的 `• ` / `☐ ` / `☑ ` 前缀决定标记与勾选状态；
     *  - 块样式优先读 [BlockStyleSpan]；**没有字号推断这条兜底路径**；
     *  - 行内样式按 span 的实际覆盖区间切 run，并且**只记录开启的项**。
     */
    fun fromSpannable(text: Spanned): List<EditorLine> {
        if (text.isEmpty()) return listOf(EditorModel.EMPTY_LINE)

        val lines = mutableListOf<EditorLine>()
        var lineStart = 0
        var index = 0
        while (lineStart <= text.length) {
            var lineEnd = lineStart
            while (lineEnd < text.length && text[lineEnd] != '\n') lineEnd++
            val raw = text.subSequence(lineStart, lineEnd).toString()
            if (raw.isNotBlank()) {
                lines.add(parseLine(text, lineStart, lineEnd, raw))
            }
            lineStart = lineEnd + 1
            index++
            if (lineEnd >= text.length) break
        }
        if (lines.isEmpty()) lines.add(EditorModel.EMPTY_LINE)
        return lines
    }

    private fun parseLine(text: Spanned, start: Int, end: Int, raw: String): EditorLine {
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
        val blockStyle = if (marker != null) {
            ContentPartStyle.body
        } else {
            text.getSpans(start, end, BlockStyleSpan::class.java)
                .firstOrNull()
                ?.style
                ?: ContentPartStyle.body
        }

        val centered = text.getSpans(start, end, android.text.style.AlignmentSpan::class.java)
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

    private fun covers(span: Any, text: Spanned, from: Int, to: Int): Boolean {
        val spanStart = text.getSpanStart(span)
        val spanEnd = text.getSpanEnd(span)
        return spanStart <= from && spanEnd >= to
    }

    /**
     * 便捷入口：直接从 `content_json` 的段落建可编辑 `Spannable`。
     *
     * 名字与 [toSpannable] 区分开，否则两个 `List` 参数的函数在 JVM 上签名相同。
     */
    fun toSpannableFromParts(parts: List<com.cov.justdiary.model.ContentPart>): SpannableStringBuilder =
        toSpannable(EditorModel.partsToLines(parts))

    /** 便捷入口：可编辑 `Spannable` → `content_json` 的段落。 */
    fun toParts(text: Spanned): List<com.cov.justdiary.model.ContentPart> =
        EditorModel.linesToParts(fromSpannable(text))

    /** 可编辑文本里图片的占位字符（对应 iOS 的 attachment 位置）。 */
    const val IMAGE_PLACEHOLDER = "\uFFFC"

    /** 占位 drawable 的边长（px）：约一行高。 */
    const val IMAGE_PLACEHOLDER_PX = 64

    /** 组合区（IME）的区间；`[-1, -1]` 表示无组合。 */
    fun compositionRange(text: Spannable): IntRange {
        val start = android.view.inputmethod.BaseInputConnection.getComposingSpanStart(text)
        val end = android.view.inputmethod.BaseInputConnection.getComposingSpanEnd(text)
        return if (start < 0 || end <= start) IntRange.EMPTY else start until end
    }
}
