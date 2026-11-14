package com.cov.justdiary.ui.diary

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.text.Spanned
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit

/**
 * 编辑器 `Spannable` 用的平台 span 类型与样式数据。
 *
 * 从 [SpannableBridge] 拆出来，只放"挂到平台文本上的东西"：语义段落 span、标记绘制、
 * 排版度量、图片载体与引用底几何。编码/解码规则分别在 [SpannableEncoder] /
 * [SpannableDecoder]，本文件不包含任何往返逻辑。
 */

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
internal const val ParagraphSpanFlags = Spanned.SPAN_INCLUSIVE_EXCLUSIVE

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
internal const val PhantomSpanFlags = Spanned.SPAN_POINT_POINT

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
    /**
     * 字距（**em**，与平台 `TextPaint.letterSpacing` 同一单位）。
     *
     * 阅读态用 Compose 的 `TextStyle`，它带 M3 角色的 `letterSpacing`（正文 0.5sp、
     * 引用 0.25sp），而编辑态早先只同步了字号与行高 —— 同一段文字在两边**换行位置不同**：
     * 「… wrap onto a second / line」在阅读态折在 `a` 之后，编辑态却能多塞一个
     * `second`（用户反馈"编辑模式排版和阅读模式不一致"）。这里把 sp 折算成 em 交给平台，
     * 两边的字距因此是同一个值（见 [blockMetricsOf]）。
     */
    val letterSpacingEm: Float = 0f,
    /** 段落字重（M3 `FontWeight` 的数值）；小标题的 500 只在这一层补。 */
    val fontWeight: Int = 400,
)

/**
 * 把 Compose 的 `TextStyle` 折成编辑器的排版数据（px + em）。
 *
 * **唯一换算点**：字号 / 行高 / 字距 / 字重都从这里过，编辑态与阅读态因此不会各算一套。
 * `TextUnit.Unspecified` 的字段按"没有额外设置"处理（行高退回 1.35×，字距为 0）。
 */
fun blockMetricsOf(
    style: TextStyle,
    density: Density,
    paragraphSpacingPx: Int,
): BlockMetrics {
    val sizePx = with(density) { style.fontSize.toPx() }.toInt()
    val lineHeight = if (style.lineHeight == TextUnit.Unspecified) {
        style.fontSize * 1.35f
    } else {
        style.lineHeight
    }
    val letterSpacingPx = if (style.letterSpacing == TextUnit.Unspecified) {
        0f
    } else {
        with(density) { style.letterSpacing.toPx() }
    }
    return BlockMetrics(
        textSizePx = sizePx,
        lineHeightPx = with(density) { lineHeight.toPx() }.toInt(),
        paragraphSpacingPx = paragraphSpacingPx,
        // 平台要的是 em（`TextPaint.setLetterSpacing`），Compose 给的是 sp 的绝对值
        letterSpacingEm = if (sizePx > 0) letterSpacingPx / sizePx else 0f,
        fontWeight = style.fontWeight?.weight ?: 400,
    )
}

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
    /** 字距（em）：与阅读态 `TextStyle.letterSpacing` 同源，见 [BlockMetrics.letterSpacingEm]。 */
    private val letterSpacingEm: Float = 0f,
    /** 段落字重：小标题在 M3 里是 500，编辑态必须一起补。 */
    private val fontWeight: Int = 400,
) : android.text.style.MetricAffectingSpan(),
    android.text.style.LineHeightSpan.WithDensity {

    override fun updateMeasureState(paint: android.text.TextPaint) {
        paint.textSize = textSizePx.toFloat()
        paint.letterSpacing = letterSpacingEm
        applyWeight(paint)
    }

    override fun updateDrawState(paint: android.text.TextPaint) {
        paint.textSize = textSizePx.toFloat()
        paint.letterSpacing = letterSpacingEm
        applyWeight(paint)
    }

    /**
     * 段落字重（只处理比正文重的角色，如小标题的 500）。
     *
     * **要保留行内样式**：同一个字符上可能还挂着 `StyleSpan(BOLD/ITALIC)`。平台的
     * `MetricAffectingSpan` 按 `getSpans` 的顺序逐个改同一支 `TextPaint`，而本 span
     * 在 [SpannableEncoder] 里先于行内 span 挂上，所以正常顺序是"先段落字重、再行内样式"。
     * 这里仍显式合并一次现有 typeface：即便顺序反过来（或平台实现调整），粗体与斜体
     * 也不会被段落字重抹掉。
     */
    private fun applyWeight(paint: android.text.TextPaint) {
        if (fontWeight <= 400) return
        val base = paint.typeface
        val bold = base?.isBold == true
        val target = if (bold) maxOf(fontWeight, 700) else fontWeight
        if (bold && target == 700) return
        paint.typeface = Typeface.create(base ?: Typeface.DEFAULT, target, base?.isItalic == true)
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
 * 图片段在**编辑器**里的外观：与阅读态、首页预览共用 [BlockGeometry] / `ReadLayout` 的取值。
 *
 * 阅读态是一张 Compose `Image`：铺满列宽、**12dp 圆角**、上下各 10dp、图与前后段落之间
 * 再隔 8dp。编辑态是 `ImageSpan`，平台只负责把 drawable 按原尺寸画进行里 ——
 * 圆角、上下留白与段距都得由这里的 drawable 自己带，否则同一张图在两种模式下
 * 一个圆角一个直角、一个贴着一句一个隔着空（用户反馈的"图片排版不一致"）。
 */
data class ImageStyle(
    /** 图片上方 / 下方的留白（px）。下方还会再叠加段距，见 [SpannableEncoder.toSpannable]。 */
    val verticalPaddingPx: Int,
    /** 圆角（px）。 */
    val cornerRadiusPx: Float,
    /** 位图还没解码出来时的占位色（与阅读态的占位块同色）。 */
    val placeholderColor: Int,
)

/**
 * 编辑态的内联图片：**圆角 + 上下留白**，几何由 [ImageStyle] 决定。
 *
 * bounds 是"整条图片行"的高度（图片 + 上留白 + 下留白），图片本身画在中间；
 * 于是 `ImageSpan` 量出的行高与阅读态那张 `Image` 占的高度是同一件事。
 */
internal class RoundedImageDrawable(
    private val bitmap: Bitmap?,
    private val style: ImageStyle,
    private val imageHeightPx: Int,
) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()

    /**
     * 位图的着色器与矩阵只随 bounds 变一次。
     *
     * 编辑区滚动时 [draw] 会被反复调用；每次 draw 都 `new BitmapShader` 会让长日记
     * （多张图）在滑动时持续分配 —— 图片是静态的，这里缓存到 bounds 变化为止。
     */
    private var shader: BitmapShader? = null

    override fun draw(canvas: Canvas) {
        val bounds = bounds
        if (bounds.isEmpty || rect.isEmpty) return
        val source = bitmap
        if (source != null) {
            val active = shader ?: return
            paint.shader = active
            paint.color = Color.WHITE
            canvas.drawRoundRect(rect, style.cornerRadiusPx, style.cornerRadiusPx, paint)
            paint.shader = null
        } else {
            paint.color = style.placeholderColor
            canvas.drawRoundRect(rect, style.cornerRadiusPx, style.cornerRadiusPx, paint)
        }
    }

    override fun setAlpha(alpha: Int) {
        if (paint.alpha != alpha) {
            paint.alpha = alpha
            invalidateSelf()
        }
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Drawable; required for older callers")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun getIntrinsicWidth(): Int = bounds.width()

    override fun getIntrinsicHeight(): Int = bounds.height()

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        val top = (bounds.top + style.verticalPaddingPx).toFloat()
        rect.set(bounds.left.toFloat(), top, bounds.right.toFloat(), top + imageHeightPx)
        val source = bitmap
        shader = if (source != null && !rect.isEmpty) {
            BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply {
                val matrix = Matrix()
                matrix.setRectToRect(
                    RectF(0f, 0f, source.width.toFloat(), source.height.toFloat()),
                    rect,
                    Matrix.ScaleToFit.FILL,
                )
                setLocalMatrix(matrix)
            }
        } else {
            null
        }
        invalidateSelf()
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
