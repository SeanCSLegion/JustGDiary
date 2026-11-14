package com.cov.justdiary.ui.diary

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StrikethroughSpan
import android.text.style.StyleSpan
import android.text.style.UnderlineSpan
import com.cov.justdiary.model.ContentPartStyle

/**
 * 行模型 → `Spannable`（编辑器唯一的编码入口）。
 *
 * 从 [SpannableBridge] 拆出：这里只负责"写"平台文本，段落语义的 span 类型在
 * [EditorSpans] 里，读回模型走 [SpannableDecoder]。
 */

internal object SpannableEncoder {

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
    /** 段落样式 → 编辑器排版；null 表示不设字号（纯文本模式，测试用）。 */
    metricsFor: ((String) -> BlockMetrics?)? = null,
    /** 引用段的底色与内边距；null 表示不画（纯文本模式 / 测试不关心外观时）。 */
    quoteStyle: QuoteStyle? = null,
    /** 列表 / 待办标记的几何与配色；null 表示用字面的 `• ` / `☐ ` 字符画（纯文本模式）。 */
    markerStyle: MarkerStyle? = null,
): SpannableStringBuilder {
    val builder = SpannableStringBuilder()
    val lineStarts = mutableListOf<Pair<Int, Int>>() // (start, end)，不含结尾的 \n
    // 末尾空引用行要补一个终止换行（见 [PhantomLineSpan]）：那一行因此成为"有字符的段落"，
    // 行首内缩、字号、引用底线才挂得上 —— 否则光标会停在列的最左边。
    val phantomTail = SpannableBridge.needsPhantomTerminator(lines)

    lines.forEachIndexed { index, line ->
        // 图片行：插入一个占位字符并把 src 挂在 ImagePayloadSpan 上
        if (line.imageSrc != null) {
            val start = builder.length
            builder.append(SpannableBridge.IMAGE_PLACEHOLDER)
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
                fallbackWidth = bitmap?.width ?: SpannableBridge.IMAGE_PLACEHOLDER_PX,
                fallbackHeight = bitmap?.height ?: SpannableBridge.IMAGE_PLACEHOLDER_PX,
            )
            drawable.setBounds(0, 0, imageW, imageH)
            builder.setSpan(
                ImagePayloadSpan(line.imageSrc, line.imageWidth, line.imageHeight, drawable),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            val hasTerminator = index < lines.size - 1 || phantomTail
            if (hasTerminator) builder.append('\n')
            // **图片段居中**（对应 iOS `PartsCodec.imageParagraphStyle()`：
            // `alignment = .center`，让图片永远不会贴在宽栏的左边）。
            // 这是**渲染规则**，不是持久化数据：`content_json` 里图片段落不带 `align`
            // （iOS 同样只把段落样式挂在附件上、不写进 JSON），`parseLine` 也在读到
            // 图片行时提前返回，因此这个 span 不会污染模型。
            builder.setSpan(
                android.text.style.AlignmentSpan.Standard(android.text.Layout.Alignment.ALIGN_CENTER),
                start,
                if (hasTerminator) end + 1 else end,
                ParagraphSpanFlags,
            )
            return@forEachIndexed
        }

        val text = line.displayText
        val start = builder.length
        builder.append(text)
        val end = builder.length
        lineStarts.add(start to end)
        // 先把终止换行补上，段落级 span 才能覆盖到它（见下）
        val hasTerminator = index < lines.size - 1 || phantomTail
        if (hasTerminator) builder.append('\n')

        // 段落级 span 的右端**含本行的终止换行**（iOS 把块属性挂在终止换行上）：
        // 空行因此也有承载（一个只有标记的空行、或"刚设了样式还没输入"的空行不会丢样式），
        // 行内继续输入时样式也会延续（靠 [ParagraphSpanFlags] 的"起点包含"）。
        val paragraphEnd = if (hasTerminator) end + 1 else end
        // 列表 / 待办：标记仍是两个真实字符，但画成"宽度 = 缩进"的画布，并给整段挂上
        // 悬挂缩进 —— 换行后的第二行因此对齐到正文起点（与阅读态同一个缩进）。
        val markerEnd = (start + SpannableBridge.MARKER_LENGTH).coerceAtMost(end)
        if (line.marker != null && markerStyle != null && markerEnd > start) {
            builder.setSpan(
                MarkerSpan(line.marker, line.done, markerStyle),
                start,
                markerEnd,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            builder.setSpan(
                android.text.style.LeadingMarginSpan.Standard(0, markerStyle.indentPx),
                start,
                paragraphEnd,
                ParagraphSpanFlags,
            )
            if (line.isTodoItem && line.done) {
                // 完成态：删除线 + 变灰（只改绘制，解析器认不出这枚 span，见 [TodoDoneSpan]）
                builder.setSpan(
                    TodoDoneSpan(markerStyle.doneTextColor),
                    start,
                    paragraphEnd,
                    ParagraphSpanFlags,
                )
            }
        }
        if (paragraphEnd > start) {
            val style = SpannableBridge.effectiveBlockStyle(line)
            builder.setSpan(
                BlockStyleSpan(style),
                start,
                paragraphEnd,
                ParagraphSpanFlags,
            )
            // **每一段都显式挂对齐 span**，左对齐也挂（`ALIGN_NORMAL`）。
            // 原因不是"更保险"，而是必须：视图级的 `gravity` 是留给"末尾空行"的
            // （那一行没有字符、挂不上任何 span，见 `EditorTextArea` / `EditorModel.blocksNewline`），
            // 只有把每一段都显式定死，改 gravity 才**只**影响末尾那一行。
            builder.setSpan(
                android.text.style.AlignmentSpan.Standard(
                    if (line.align == EditorModel.ALIGN_CENTER) {
                        android.text.Layout.Alignment.ALIGN_CENTER
                    } else {
                        android.text.Layout.Alignment.ALIGN_NORMAL
                    },
                ),
                start,
                paragraphEnd,
                ParagraphSpanFlags,
            )
            metricsFor?.invoke(style)?.let { metrics ->
                builder.setSpan(
                    BlockMetricsSpan(
                        textSizePx = metrics.textSizePx,
                        lineHeightPx = metrics.lineHeightPx,
                        paragraphSpacingPx = metrics.paragraphSpacingPx,
                    ),
                    start,
                    paragraphEnd,
                    ParagraphSpanFlags,
                )
            }
            // 引用：行首内缩（与阅读态同参数，见 [QuoteStyle]）。
            // **底色不在这里画**：`LineBackgroundSpan` 由平台在**光标之后**绘制
            // （`Editor.onDraw` 先 `drawCursor` 再 `drawLayout`，见
            // `android-37.0/android/widget/Editor.java:2143-2154`），一块不透明的圆角底
            // 会把光标整个盖住 —— 真机实测：引用段里打上字之后光标就再也看不见了。
            // 现在底一律由 `DiaryEditText.onDraw` 在 `super.onDraw` **之前**画（与末尾那个
            // 空行同一条路径），光标因此永远在底之上。
            if (quoteStyle != null && style == ContentPartStyle.quote) {
                builder.setSpan(
                    android.text.style.LeadingMarginSpan.Standard(quoteStyle.textInsetPx),
                    start,
                    paragraphEnd,
                    ParagraphSpanFlags,
                )
            }
        }
        if (end > start) {
            // 行内样式：按 run 的字符偏移挂 span。**标记行要跳过标记前缀** ——
            // `line.text` 不含标记，直接从行首开始挂会让整行样式左移两格
            // （列表项第一字加粗会画到圆点上，回读时那一格又落在正文之外）。
            var cursor = start + if (line.marker != null && markerStyle != null) SpannableBridge.MARKER_LENGTH else 0
            for (run in EditorModel.materializeRuns(line)) {
                val runEnd = cursor + run.text.length
                applyInlineSpans(builder, cursor, runEnd, run)
                cursor = runEnd
            }
        }
    }
    // 末尾那个**空的引用行**：
    //
    // - 模型里有**两行以上**时，上面已经给它补了终止换行（[PhantomLineSpan]），它因此是
    //   一段"有字符的段落"，行首内缩（`LeadingMarginSpan`）与引用底全都照常生效；
    // - 模型**只有这一行**（空文档上点了「引用」）时不能补 —— 补了文本就不为空，
    //   提示语（placeholder）会消失。那一种情况退回到"在这个**位置**挂一个**零长度**的
    //   `LeadingMarginSpan`"：此时位置是 0，`Layout.getParagraphSpans` 对
    //   `start == end && start > 0` 才返回空数组（`text/Layout.java:3489`），
    //   位置 0 是认的 —— 光标因此同样落在内缩处（真机实测 x=77 → 120）。
    if (phantomTail) {
        // 补位换行那段（多出来的空段落）的**标记**：解析时靠它把多出来的空行丢掉。
        // 与 `quoteStyle` 无关 —— 补位换行是"文本形状"的一部分，标记必须跟着它一起出现。
        builder.setSpan(PhantomLineSpan(), builder.length, builder.length, PhantomSpanFlags)
    } else if (quoteStyle != null) {
        val tail = lines.lastOrNull()
        if (tail != null && EditorModel.isBlankLine(tail) &&
            SpannableBridge.effectiveBlockStyle(tail) == ContentPartStyle.quote
        ) {
            builder.setSpan(
                android.text.style.LeadingMarginSpan.Standard(quoteStyle.textInsetPx),
                builder.length,
                builder.length,
                ParagraphSpanFlags,
            )
        }
    }
    return builder
}

private fun applyInlineSpans(builder: SpannableStringBuilder, start: Int, end: Int, run: com.cov.justdiary.model.TextRun) {        if (end <= start) return
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
}
