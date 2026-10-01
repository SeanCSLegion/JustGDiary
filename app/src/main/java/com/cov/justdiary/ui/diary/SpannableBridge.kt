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

    /**
     * **段落级 span 的统一标志：起点包含、终点不包含**。
     *
     * 为什么不能两头都 INCLUSIVE（真机缺陷，用户 2026-09-28："居中的作用范围不对劲……
     * 取消的时候没有真的取消，不知道是不是和换行有关系"）：
     *
     * 段落 span 的右端**含本行的终止换行**，也就是说它的终点正好落在**下一行的行首**。
     * 两头 INCLUSIVE 时，用户在下一行行首敲的第一个字符会被**上一段的 span 吞进去** ——
     * 屏幕上那一行就带着上一段的居中/字号，而模型里它明明是正文（解析按"span 起点 == 行首"
     * 判定，所以模型没错）。于是出现"看着还居中、其实已经不是居中了、取消也取消不掉"的怪象。
     *
     * 起点包含则是必须的：在行首插入文字（比如把光标放到行首打字）应当留在这一段里。
     */
    private const val ParagraphSpanFlags = Spanned.SPAN_INCLUSIVE_EXCLUSIVE

    /** 承载块样式的自定义段落 span（对应 iOS 的 `.diaryBlockStyle`）。 */
    class BlockStyleSpan(val style: String) : android.text.style.ParagraphStyle

    /**
     * **末尾空行的补位换行**（phantom）的标记 —— 一个挂在文本最末端的零长度段落 span。
     *
     * ## 它是干什么的
     *
     * 段落样式（字号 / 行高 / 引用底 / 行首内缩）只能挂在**字符**上，而"文档末尾那一行"
     * 如果是空的，它在平台文本里就一个字符都没有（它的终止换行属于上一段）——
     * `Layout.getParagraphSpans` 对 `start == end && start > 0` 的空段落**直接返回空数组**
     * （`text/Layout.java:3489`，源码注释只说"整个缓冲区都空时样式才会作用于新输入的文字"），
     * 于是那一行挂不上任何东西：光标停在列的最左边（用户 2026-10-01 报的"引用时光标位置不对"），
     * 底也画不出来。
     *
     * 解法：末尾那一行是**空的引用行**时，给它也补一个终止换行 —— 这样它就从"文档末尾的空段落"
     * 变成了"一段正常的、自带换行的空段落"，段落 span（行首内缩 / 字号 / 引用底线）全部照常生效
     * （`getLineStart(line) != getLineEnd(line)`）。
     *
     * 代价是平台文本比模型多出一个**空段落**（多出来的那个 `\n` 之后什么都没有），
     * 由 [PhantomLineSpan] 标记它、[fromSpannable] 解析时丢掉它，模型因此不多也不少一行。
     * 标记用 `SPAN_POINT_POINT`：在最末尾插入文字时它跟着往后走，始终留在文本最后
     * （[fromSpannable] 还会额外要求它正好落在 `text.length`，位置不对就不当补位行）。
     */
    class PhantomLineSpan : android.text.style.ParagraphStyle

    /** [PhantomLineSpan] 的挂法：零长度、跟着末尾插入往后走。 */
    private const val PhantomSpanFlags = Spanned.SPAN_POINT_POINT

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
     * 列表圆点 / 待办勾选框在**编辑态**的几何与配色。
     *
     * 由界面层从 [BlockGeometry] 与主题解析成 `px`（与 [QuoteStyle] 同一条约定），
     * 阅读态与首页预览用同一批 dp 常量 —— 三处因此是同一套数。
     */
    data class MarkerStyle(
        /** 标记槽宽，也是这一行（含换行后的每一行）的正文起点。 */
        val indentPx: Int,
        /** 图形中心距列左边的距离（槽内居中）。 */
        val centerXPx: Float,
        /** 圆点直径。 */
        val bulletDiameterPx: Float,
        val checkboxSizePx: Float,
        val checkboxRadiusPx: Float,
        val checkboxBorderPx: Float,
        /** 圆点 / 未选勾选框的颜色：`onSurfaceVariant`。 */
        val outlineColor: Int,
        /** 勾选后的底色：`primary`。 */
        val fillColor: Int,
        /** 勾选后的对勾色：`onPrimary`。 */
        val checkColor: Int,
        /** 待办完成后的文字色：`onSurfaceVariant`。 */
        val doneTextColor: Int,
    )

    /**
     * 列表圆点 / 待办勾选框在编辑态的画法。
     *
     * 标记在可编辑文本里仍然是**两个真实字符**，只是被这枚 `ReplacementSpan` 换成
     * "一张宽度等于缩进的画布"（iOS 那边是 `MarkerGlyph` 生成同宽度的图片附件）：
     *
     * - 宽度固定 = [MarkerStyle.indentPx]，于是正文起点与阅读态一致；换行后的第二行
     *   由同一段落上的 `LeadingMarginSpan` 对齐到同一个起点 —— 这就是悬挂缩进，
     *   修掉"换行后第二行顶到圆点底下"；
     * - 图形在**首行行盒里垂直居中**：`(top + bottom) / 2`。阅读态与预览也用同一条
     *   规则（`(行高 − 图形高) / 2`），三处因此对得上；iOS 用的是"基线之上 0.32em"，
     *   那是只有 TextKit 才表达得准的规则，这里不照抄；
     * - **字符数不变**（仍是 2 个），因此光标 / 选区的全部算术与落库格式一行都不用改。
     */
    class MarkerSpan(
        private val kind: EditorLine.MarkerKind,
        private val done: Boolean,
        private val style: MarkerStyle,
    ) : android.text.style.ReplacementSpan() {

        private val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
        private val rect = android.graphics.RectF()
        private val checkPath = android.graphics.Path()

        override fun getSize(
            paint: android.graphics.Paint,
            text: CharSequence,
            start: Int,
            end: Int,
            fm: android.graphics.Paint.FontMetricsInt?,
        ): Int {
            // 行高由这一段的 `BlockMetricsSpan` 定，这里只把字体度量原样交回去
            // （不动 fm = 与一个普通字符占的高度一致，不会把行撑高）。
            if (fm != null) {
                val metrics = paint.fontMetricsInt
                fm.ascent = metrics.ascent
                fm.descent = metrics.descent
                fm.top = metrics.top
                fm.bottom = metrics.bottom
            }
            return style.indentPx
        }

        override fun draw(
            canvas: android.graphics.Canvas,
            text: CharSequence,
            start: Int,
            end: Int,
            x: Float,
            top: Int,
            y: Int,
            bottom: Int,
            paint: android.graphics.Paint,
        ) {
            val centerX = x + style.centerXPx
            val centerY = (top + bottom) / 2f
            when (kind) {
                EditorLine.MarkerKind.Bullet -> {
                    this.paint.reset()
                    this.paint.isAntiAlias = true
                    this.paint.color = style.outlineColor
                    canvas.drawCircle(centerX, centerY, style.bulletDiameterPx / 2f, this.paint)
                }

                EditorLine.MarkerKind.Todo -> drawCheckbox(canvas, centerX, centerY)
            }
        }

        /** 勾选框：18dp 圆角方框；未完成 = 2dp 描边（描边**画在框内**，与 M3 一致）。 */
        private fun drawCheckbox(canvas: android.graphics.Canvas, centerX: Float, centerY: Float) {
            val half = style.checkboxSizePx / 2f
            rect.set(centerX - half, centerY - half, centerX + half, centerY + half)
            paint.reset()
            paint.isAntiAlias = true
            if (done) {
                paint.color = style.fillColor
                canvas.drawRoundRect(rect, style.checkboxRadiusPx, style.checkboxRadiusPx, paint)
                drawCheck(canvas, centerX, centerY)
                return
            }
            val inset = style.checkboxBorderPx / 2f
            rect.inset(inset, inset)
            paint.color = style.outlineColor
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = style.checkboxBorderPx
            canvas.drawRoundRect(rect, style.checkboxRadiusPx, style.checkboxRadiusPx, paint)
        }

        /** 对勾：三段折线，占勾选框内侧约 60%（M3 的对勾图标就是这个比例）。 */
        private fun drawCheck(canvas: android.graphics.Canvas, centerX: Float, centerY: Float) {
            val r = style.checkboxSizePx / 2f * 0.62f
            paint.style = android.graphics.Paint.Style.STROKE
            paint.strokeWidth = style.checkboxSizePx * 0.11f
            paint.strokeCap = android.graphics.Paint.Cap.ROUND
            paint.strokeJoin = android.graphics.Paint.Join.ROUND
            paint.color = style.checkColor
            checkPath.reset()
            checkPath.moveTo(centerX - r * 0.95f, centerY + r * 0.05f)
            checkPath.lineTo(centerX - r * 0.25f, centerY + r * 0.75f)
            checkPath.lineTo(centerX + r, centerY - r * 0.7f)
            canvas.drawPath(checkPath, paint)
        }
    }

    /**
     * 待办**完成**态的绘制样式：删除线 + 变灰（阅读态与预览同此）。
     *
     * 刻意**不**用平台的 `StrikethroughSpan`：`fromSpannable` 会把它解析成
     * `run.strike = true` 写进 `content_json` —— 勾一下待办会把整行变成"手动删除线"
     * （取消勾选也退不回来）。这是一个只改绘制的 [android.text.style.CharacterStyle]，
     * 解析器认不出它，因此不会污染模型。
     */
    class TodoDoneSpan(private val color: Int) : android.text.style.CharacterStyle() {
        override fun updateDrawState(tp: android.text.TextPaint) {
            tp.isStrikeThruText = true
            tp.color = color
        }
    }

    /**
     * 一个段落样式在**编辑器里的排版**（字号 / 行高 / 段后距），单位 px。
     *
     * 由界面层从 M3 排版角色解析（`EditorTypography.textStyleFor` + 密度），
     * 这样编辑器与阅读态用的是**同一套**字号与行高 —— 规范 §2 的第一条：
     * 同一段文字在两种模式下逐像素一致。
     */
    data class BlockMetrics(
        val textSizePx: Int,
        val lineHeightPx: Int,
        val paragraphSpacingPx: Int,
    )

    /**
     * 段落排版 span：字号（`MetricAffectingSpan`）+ 行高 + 段后距（`LineHeightSpan`）。
     *
     * ## 为什么不是直接给块样式一个字号
     *
     * `BlockStyleSpan` 只承载**语义样式名**（跨端契约里存的就是它，不存字号）；
     * 真正画出来的字号由这一层按平台规范给。分开的好处：系统字号一变，
     * 只需要重算这一层，模型与落库内容一个字节都不动。
     *
     * 段后距只加在**段落的最后一行**（`text[end]` 是换行或已到文末）——
     * 一个 `EditorLine` 就是一段，软换行出来的行不会多出间距。
     */
    class BlockMetricsSpan(
        private val textSizePx: Int,
        private val lineHeightPx: Int,
        private val paragraphSpacingPx: Int,
    ) : android.text.style.MetricAffectingSpan(),
        android.text.style.LineHeightSpan.WithDensity {

        override fun updateMeasureState(paint: android.text.TextPaint) {
            paint.textSize = textSizePx.toFloat()
        }

        override fun updateDrawState(paint: android.text.TextPaint) {
            paint.textSize = textSizePx.toFloat()
        }

        /** `LineHeightSpan` 的入口（无密度版）。 */
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            lineHeight: Int,
            fm: android.graphics.Paint.FontMetricsInt,
        ) = applyHeight(text, end, fm)

        /** `WithDensity` 的入口（新版平台走这一条）。字号已经按 px 给好，这里不再乘密度。 */
        override fun chooseHeight(
            text: CharSequence,
            start: Int,
            end: Int,
            spanstartv: Int,
            lineHeight: Int,
            fm: android.graphics.Paint.FontMetricsInt,
            paint: android.text.TextPaint,
        ) = applyHeight(text, end, fm)

        /** 字号对应的**原始字体度量**（下面算绝对值时用，见 [applyHeight] 的说明）。 */
        private val baseMetrics = android.text.TextPaint()
            .apply { textSize = textSizePx.toFloat() }
            .fontMetricsInt

        /**
         * 把这一行的 ascent/descent **算成绝对值**写回去。
         *
         * ⚠️ 必须算绝对值、不能在传进来的 `fm` 上累加：平台对同一行的
         * `chooseHeight` 会调用**不止一次**（排版量一遍、落位再一遍），而 `fm` 是就地改的 ——
         * 累加的实现会让段后距**翻倍**（实测行框比预期高 2×24px，编辑器里段落间距变成 16dp，
         * 引用底也跟着多出一大截，看起来像"引用把下一行也包进来了"）。
         * 真机缺陷记录：用户 2026-09-28 "引用的作用范围现在也不对"。
         */
        private fun applyHeight(
            text: CharSequence,
            end: Int,
            fm: android.graphics.Paint.FontMetricsInt,
        ) {
            val natural = baseMetrics.descent - baseMetrics.ascent
            if (natural <= 0) return
            // 行高：按比例拉开 ascent/descent（保持基线位置的比例），得到 M3 角色的行高。
            // 与 Compose 默认的 `LineHeightStyle`（`Alignment.Proportional` + `Mode.Fixed`）
            // 是同一条规则，因此编辑态与阅读态的基线位置一致（见 BlockGeometry 的说明）。
            val scale = if (lineHeightPx > natural) lineHeightPx.toFloat() / natural else 1f
            // 行盒高度必须**正好**是角色的行高：ascent 取整之后把剩下的都给 descent
            // （各自独立取整会让行盒少 1px —— 光标高度与阅读态的行盒都会差这一点）。
            val ascent = Math.round(baseMetrics.ascent * scale)
            var descent = lineHeightPx + ascent
            // 段距：只加在**段落之间**那一行。文末最后一段不加 —— 阅读态的一段到底就是到底，
            // 多出来的那段距离只会让末尾光标凭空高 8dp（R31 §10 记的差异）。
            //
            // ⚠️ `chooseHeight` 给的 `end` 是**含终止换行**的行尾（实测 "甲\n乙" 的第一行
            // end=2、len=3），所以判据要看 `end - 1` 那个字符 —— 早先写成 `text[end]`，
            // 那已经是下一行的首字，于是**段距从来没有生效过**（编辑器里两段之间一直
            // 只有行高、没有间距，与阅读态的 8dp 对不上）。
            val endsParagraph = end >= text.length || (end > 0 && text[end - 1] == '\n')
            val isLastParagraph = end >= text.length
            if (endsParagraph && !isLastParagraph && paragraphSpacingPx > 0) descent += paragraphSpacingPx
            fm.ascent = ascent
            fm.descent = descent
            fm.top = ascent
            fm.bottom = descent
        }
    }

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
        val phantomTail = needsPhantomTerminator(lines)

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
            val markerEnd = (start + MARKER_LENGTH).coerceAtMost(end)
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
                val style = effectiveBlockStyle(line)
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
                var cursor = start + if (line.marker != null && markerStyle != null) MARKER_LENGTH else 0
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
                effectiveBlockStyle(tail) == ContentPartStyle.quote
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
     * 引用段在**编辑器**里的样子。
     *
     * 与阅读态、首页预览逐项同源（[BlockGeometry]）：`surfaceContainerHigh` 底 + 4dp 圆角
     * + 上下各 6dp 内边距 + 左侧 4dp 竖条 + 文字内缩 16dp —— 三处必须看起来是同一件事。
     *
     * ⚠️ 平台限制记一笔：`Spannable` 只有**左边**的 `LeadingMarginSpan`，没有右边距 span，
     * 所以文字只缩进左侧；一行正好排满时文字会贴到引用底的右边缘。阅读态与预览右边还有
     * 10dp（`BlockGeometry.QuoteTrailingPadding`）—— 这是三处唯一对不齐的地方。
     */
    data class QuoteStyle(
        val backgroundColor: Int,
        val cornerRadiusPx: Float,
        /** 引用正文距列左边界的距离（竖条在里面）。 */
        val textInsetPx: Int,
        val paddingVerticalPx: Int,
        /** 左侧竖条：距列左、宽度、上下各让出、圆角、颜色。宽 0 表示不画竖条。 */
        val barLeadingPx: Float = 0f,
        val barWidthPx: Float = 0f,
        val barInsetPx: Float = 0f,
        val barRadiusPx: Float = 0f,
        val barColor: Int = 0,
        /**
         * 段后距（px）—— 必须与 [BlockMetricsSpan] 用的是**同一个值**。
         *
         * 段后距是烘进 `LineHeightSpan` 的 descent 里的，于是 `drawBackground` 拿到的
         * `bottom` **含**这段距离：不减掉它，引用底会比文字往下多出一大截，
         * 看起来像"引用把下一行也包进来了"（用户 2026-09-28 实测："引用的作用范围现在也不对"）。
         */
        val paragraphSpacingPx: Int = 0,
    ) {
        /**
         * 在画布上画一块引用：**整列宽的圆角底 + 左侧竖条**。
         *
         * **两处共用同一套几何**：`DiaryEditText.onDraw`（所有引用段，按行调用）与
         * `DiaryEditText.onDraw`（末尾那个空行没有字符、挂不上任何 span，只能由视图自己补）。
         *
         * `first` / `last` 表示"这一行是不是整段的第一行 / 最后一行"：底色与竖条都只在
         * 整段的最外沿加内边距与圆角、中间行直接接上 —— 多行拼起来才是一个连续的块。
         *
         * `bakedSpacingPx` = 末行的 `bottom` 里**实际含**的段距（[BlockMetricsSpan] 把它
         * 烘进了 descent）。不减掉它，底会比文字往下多出一大截（用户 2026-09-28 实测：
         * "引用的作用范围现在也不对"）；而**文末最后一段**与**末尾空行**并没有那段距离，
         * 减了反而会把底削短 —— 所以由调用方按"后面还有没有内容"传进来。
         */
        fun drawBox(
            canvas: android.graphics.Canvas,
            paint: android.graphics.Paint,
            path: android.graphics.Path,
            radii: FloatArray,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            first: Boolean,
            last: Boolean,
            bakedSpacingPx: Int = 0,
        ) {
            val boxTop = if (first) top - paddingVerticalPx else top
            val boxBottom = if (last) bottom + paddingVerticalPx - bakedSpacingPx else bottom
            drawRoundRect(canvas, paint, path, radii, left, boxTop, right, boxBottom, cornerRadiusPx, first, last)

            if (barWidthPx <= 0f) return
            // 竖条：只占块内的一小条，上下各让 barInset（免得顶到圆角外面）；
            // 圆角同样只给整段的两端，中间行接上。
            val barTop = if (first) boxTop + barInsetPx else top
            val barBottom = if (last) boxBottom - barInsetPx else bottom
            if (barBottom <= barTop) return
            paint.color = barColor
            drawRoundRect(
                canvas, paint, path, radii,
                left + barLeadingPx, barTop, left + barLeadingPx + barWidthPx, barBottom,
                barRadiusPx, first, last,
            )
        }

        /** 画一个圆角矩形，圆角只给指定的两端（`radii` 是复用的暂存数组）。 */
        private fun drawRoundRect(
            canvas: android.graphics.Canvas,
            paint: android.graphics.Paint,
            path: android.graphics.Path,
            radii: FloatArray,
            left: Float,
            top: Float,
            right: Float,
            bottom: Float,
            radius: Float,
            roundTop: Boolean,
            roundBottom: Boolean,
        ) {
            java.util.Arrays.fill(radii, 0f)
            if (roundTop) {
                radii[0] = radius
                radii[1] = radius
                radii[2] = radius
                radii[3] = radius
            }
            if (roundBottom) {
                radii[4] = radius
                radii[5] = radius
                radii[6] = radius
                radii[7] = radius
            }
            path.reset()
            path.addRoundRect(left, top, right, bottom, radii, android.graphics.Path.Direction.CW)
            canvas.drawPath(path, paint)
        }
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
