package com.cov.justdiary.ui.diary

import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import com.cov.justdiary.model.ContentPartStyle

/**
 * [EditorLine] ↔ `Spannable` 的桥接层（对外门面）。
 *
 * 平台 span 类型在 [EditorSpans]，写平台文本在 [SpannableEncoder]，读回模型在
 * [SpannableDecoder]；这里只保留稳定入口、引用几何与"末尾空行"这类视图级规则。
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

    /** 行模型 → `Spannable`；实现在 [SpannableEncoder.toSpannable]。 */
    fun toSpannable(
        lines: List<EditorLine>,
        imageProvider: ((String) -> android.graphics.Bitmap?)? = null,
        containerWidthPx: Int = 0,
        metricsFor: ((String) -> BlockMetrics?)? = null,
        quoteStyle: QuoteStyle? = null,
        markerStyle: MarkerStyle? = null,
    ): SpannableStringBuilder = SpannableEncoder.toSpannable(
        lines, imageProvider, containerWidthPx, metricsFor, quoteStyle, markerStyle,
    )

    /** `Spannable` → 行模型；实现在 [SpannableDecoder.fromSpannable]。 */
    fun fromSpannable(text: Spanned): List<EditorLine> = SpannableDecoder.fromSpannable(text)

    /** 文本末端那个空段落是不是补位行；实现在 [SpannableDecoder.isPhantomLine]。 */
    fun isPhantomLine(text: Spanned): Boolean = SpannableDecoder.isPhantomLine(text)

    /** 有没有被新插入换行漏过去的段落 span；见 [SpannableDecoder.hasLeakedParagraphSpan]。 */
    fun hasLeakedParagraphSpan(text: Spanned): Boolean =
        SpannableDecoder.hasLeakedParagraphSpan(text)


    /** 末尾空行要不要补终止换行（见 [PhantomLineSpan]）：只有**空的引用行**需要。 */
    fun needsPhantomTerminator(lines: List<EditorLine>): Boolean {
        // 单行（空文档）不补：那时文本本身是空的，提示语要靠"文本为空"才显示得出来，
        // 而且那一种情况下零长度 span 落在位置 0、平台是认的（R32 已经验证）。
        if (lines.size < 2) return false
        val tail = lines.last()
        return EditorModel.isBlankLine(tail) && tail.blockStyle == ContentPartStyle.quote
    }

    /** 标记前缀的字符数（`• ` / `☐ ` / `☑ ` 都是"图形 + 一个空格"）。 */
    const val MARKER_LENGTH = 2

    /**
     * **引用段**在文本里的区间 `[start, end)`（含终止换行）—— 编辑器画引用底时按它逐行来。
     *
     * 认的是 [BlockStyleSpan]（段落语义的唯一来源），不认几何：底由谁画、画多大都不影响这里。
     */
    fun quoteRanges(text: Spanned): List<Pair<Int, Int>> =
        text.getSpans(0, text.length, BlockStyleSpan::class.java)
            .filter { it.style == ContentPartStyle.quote }
            .map { text.getSpanStart(it) to text.getSpanEnd(it) }
            .filter { it.second > it.first }
            .sortedBy { it.first }

    /**
     * 这一行**画出来**用的段落样式。
     *
     * 列表 / 待办的标记行一律按正文画（与 iOS `EditorBlockStyle(partStyle:)` 一致：
     * 它们有自己的持久化名字，但绘制时用正文属性）—— 字号因此也跟正文走，
     * 光标高度自然跟着变（用户规则 6）。
     */
    fun effectiveBlockStyle(line: EditorLine): String =
        if (line.marker != null) ContentPartStyle.body else line.blockStyle

    /**
     * 文档**末尾那一行**（光标可能停在上面的空行）该用的视图级排版：字号来自它的段落样式。
     *
     * ## 为什么需要它（用户规则 4 / 6）
     *
     * 段落级 span 只能挂在字符上。文档末尾如果是空行，它**一个字符都没有**
     * （它的终止换行属于上一段的边界），因此挂不上任何 `MetricAffectingSpan` /
     * `AlignmentSpan`。而平台的 `Editor.updateCursorPosition`
     * （`android-37.0/android/widget/Editor.java:2449-2464`）算光标高度与水平位置用的就是
     * `layout.getLineTop/Bottom(line)` 与 `layout.getPrimaryHorizontal(offset)` ——
     * 那一行没有 span，就只能落到**视图级**的 `textSize` 与 `gravity` 上
     * （`TextView.getLayoutAlignment()` 把 gravity 翻成 `Layout.Alignment`）。
     *
     * 返回 null 表示末尾那一行**有 span 撑着**（有内容，或者空的引用行已经补过终止换行 ——
     * 见 [PhantomLineSpan]），视图级设置不影响任何一行。
     */
    fun tailDefaults(
        lines: List<EditorLine>,
        metricsFor: ((String) -> BlockMetrics?)?,
    ): BlockMetrics? {
        val tail = lines.lastOrNull() ?: return null
        if (!EditorModel.isBlankLine(tail)) return null
        if (needsPhantomTerminator(lines)) return null
        return metricsFor?.invoke(effectiveBlockStyle(tail))
    }

    /**
     * 末尾空行是**引用**时的外观 —— 只有"空文档上点了引用"那一种（模型只有一行）需要它：
     * 那一行在平台文本里一个字符都没有，段落 span 挂不上，只能由 `DiaryEditText` 自己把这块底
     * 画出来（与段落底共用 [QuoteStyle.drawBox]）。
     *
     * 模型有两行以上时空的引用行会补一个终止换行（[PhantomLineSpan]），底由段落 span 的那条路径
     * 一起画 —— 这里返回 null，免得画两遍。
     */
    fun tailQuoteStyle(lines: List<EditorLine>, quoteStyle: QuoteStyle?): QuoteStyle? {
        val tail = lines.lastOrNull() ?: return null
        if (!EditorModel.isBlankLine(tail)) return null
        if (needsPhantomTerminator(lines)) return null
        return if (tail.blockStyle == ContentPartStyle.quote) quoteStyle else null
    }

    /** 末尾空行是不是居中的（视图级 `gravity` 的来源，与 [tailDefaults] 配对）。 */
    fun tailCentered(lines: List<EditorLine>): Boolean {
        val tail = lines.lastOrNull() ?: return false
        if (needsPhantomTerminator(lines)) return false
        return EditorModel.isBlankLine(tail) && tail.align == EditorModel.ALIGN_CENTER
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
