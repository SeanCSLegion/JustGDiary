package com.cov.justdiary.data

import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import java.time.LocalDate
import kotlin.math.ceil
import kotlin.math.max

/**
 * 分享长图的**版式计算**。逐项移植自 iOS `Services/ImageShareService.swift` 的
 * `ShareRenderer`（2026-09 重做版）。
 *
 * 这一层只算"画什么、画在哪、多高"，不碰任何 Android 绘图 API —— 因此可以在 JVM 上断言
 * （方案 §8.3：常量必须逐项对齐，否则导出样式会漂移）。
 *
 * ## 这一版与上一版的区别（对齐 iOS 的"重做"）
 *
 * 旧版是「三个大色球 + 满屏渐变 + 一条悬空的蓝色时间轴」，与 iOS 已经弃用的那一版对应。
 * iOS 现在是：
 * - **每条记录一张卡片**（与应用内"每条记录一张卡"的语言一致，文字永远压在浅底上）；
 * - **克制的背景**：两段竖向渐变 + 两枚极淡的品牌晕染，不抢内容；
 * - **清楚的头部**：品牌行 → 大日期 + 星期 → 起始时间 → 品牌色短线；
 * - **正经的页脚**：细线 +「一页时光 · JustADiary」+ 当天 `day_key`。
 *
 * ## 与 iOS 的实现差异（都是平台事实，不影响版面）
 *
 * | 项 | iOS | 这里 |
 * | --- | --- | --- |
 * | 文字量算 | `NSString.boundingRect` + Core Text 行盒 | [FontMetrics] 抽象：真机用 `Paint`，测试用 [InkRatioFontMetrics] |
 * | 时间轴 / 气泡的裁剪 | `CGContext.clip` | `Canvas.clipRect` / `clipPath`（同一个含义） |
 * | 白色对勾 | `UIColor.white` | `Color.WHITE`（不受深色主题影响，与 iOS 同） |
 *
 * 量算抽象带来的唯一差别是**换行位置**可能在个别字符串上与 iOS 差一两个字
 * （分享图是本地生成物，不属于跨端契约）。
 */
object ShareLayout {

    // ------------------------------------------------------------------ 版面常量（M）
    //
    // 逐项对齐 `ImageShareService.swift:28-52`。

    const val WIDTH: Double = 720.0
    const val MARGIN: Double = 56.0
    const val CONTENT: Double = WIDTH - MARGIN * 2
    const val TOP: Double = 56.0
    const val BRAND_H: Double = 34.0
    const val AFTER_BRAND: Double = 24.0
    const val DATE_H: Double = 74.0
    const val AFTER_DATE: Double = 12.0
    const val META_H: Double = 34.0
    const val AFTER_META: Double = 26.0
    const val RULE_WIDTH: Double = 64.0
    const val RULE_HEIGHT: Double = 5.0
    const val AFTER_RULE: Double = 40.0
    const val ENTRY_HEAD_H: Double = 38.0
    const val AFTER_ENTRY_HEAD: Double = 14.0
    const val CARD_PAD_H: Double = 30.0
    const val CARD_PAD_V: Double = 28.0
    const val CARD_RADIUS: Double = 26.0
    const val BETWEEN_ENTRIES: Double = 30.0
    const val PART_GAP: Double = 20.0
    const val LOOSE_GAP: Double = 26.0
    const val BEFORE_FOOTER: Double = 46.0
    const val FOOTER_H: Double = 30.0
    const val BOTTOM: Double = 48.0

    /** 品牌行里那颗品牌色圆点。 */
    const val BRAND_DOT_RADIUS: Double = 7.0

    /** 列表项圆点半径、待办复选框边长与圆角。 */
    const val ITEM_DOT_RADIUS: Double = 5.5
    const val CHECKBOX_SIZE: Double = 30.0
    const val CHECKBOX_RADIUS: Double = 8.0

    /** 列表 / 待办的标记列宽（文字从这里之后开始）。 */
    const val LIST_MARKER_W: Double = 30.0
    const val TODO_MARKER_W: Double = 48.0

    /** 待办项之间的间距（项内已经加了，返回值要扣掉最后一次）。 */
    const val ITEM_GAP: Double = 16.0

    /** 引用块的左右内缩与上下内边距、左侧色条。 */
    const val QUOTE_PAD_V: Double = 22.0
    const val QUOTE_TEXT_INSET: Double = 26.0
    const val QUOTE_TEXT_RIGHT: Double = 46.0
    const val QUOTE_RADIUS: Double = 16.0
    const val QUOTE_BAR_W: Double = 5.0
    const val QUOTE_BAR_INSET: Double = 10.0

    /** 图片：圆角、最小高度、缺省宽高比。 */
    const val IMAGE_RADIUS: Double = 20.0
    const val IMAGE_MIN_HEIGHT: Double = 120.0
    const val IMAGE_FALLBACK_RATIO: Double = 0.75

    /** 背景两枚晕染的位置与半径（`drawBackground`）。 */
    const val GLOW_A_X_RATIO: Double = 0.94
    const val GLOW_A_Y: Double = -80.0
    const val GLOW_A_RADIUS: Double = 430.0
    const val GLOW_A_ALPHA: Double = 0.10
    const val GLOW_B_X_RATIO: Double = 0.02
    const val GLOW_B_Y_RATIO: Double = 0.44
    const val GLOW_B_RADIUS: Double = 380.0
    const val GLOW_B_ALPHA: Double = 0.06

    // ------------------------------------------------------------------ 字号（F）与行高（LH）

    const val BRAND_FONT: Double = 26.0
    const val YEAR_FONT: Double = 22.0
    const val DATE_FONT: Double = 58.0
    const val WEEKDAY_FONT: Double = 28.0
    const val META_FONT: Double = 25.0
    const val TIME_FONT: Double = 27.0
    const val LOC_FONT: Double = 23.0
    const val TITLE_FONT: Double = 38.0
    const val HEADING_FONT: Double = 32.0
    const val BODY_FONT: Double = 29.0
    const val QUOTE_FONT: Double = 27.0
    const val ITEM_FONT: Double = 29.0
    const val FOOTER_FONT: Double = 22.0

    const val TITLE_LH: Double = 54.0
    const val HEADING_LH: Double = 46.0
    const val BODY_LH: Double = 48.0
    const val QUOTE_LH: Double = 44.0
    const val ITEM_LH: Double = 46.0
    const val FOOTER_LH: Double = 30.0

    /**
     * 一段文本占的高度：`max(lineHeight, 行数 × lineHeight)`。
     *
     * iOS 是 `max(lineHeight, ceil(boundingRect.height))` —— `boundingRect` 配
     * `usesFontLeading` 返回的是**行盒总高**，N 行就是 N × lineHeight，因此这里按行数算。
     * 这样做同时把 iOS（Core Text）与 Android（`Paint.FontMetrics`）两套度量体系的差异
     * 挡在版面之外：同一个字符串在两边得到的是同一个高度。
     */
    fun textHeight(text: String, font: Double, width: Double, lineHeight: Double, metrics: FontMetrics): Double {
        if (text.isEmpty() || width <= 0) return lineHeight
        val lines = wrappedLines(text, font, width, metrics).size
        return max(lineHeight, ceil(lines * lineHeight))
    }

    /** 单行文本宽（时间行后面接地点时用）。 */
    fun textWidth(text: String, font: Double, metrics: FontMetrics): Double = metrics.width(text, font)

    // ------------------------------------------------------------------ 量算抽象

    /** 字体度量：宽度 / 高度 / 基线偏移。真机实现走 `Paint`。 */
    interface FontMetrics {
        /** 文本宽度（单行）。 */
        fun width(text: String, fontSize: Double): Double

        /** 字体下降部（`descent`，通常是正值；用来让星期与大日期**基线对齐**）。 */
        fun descent(fontSize: Double): Double

        /** 字体行盒高（`Paint.FontMetrics` 的 `descent - ascent`）。 */
        fun lineHeightOf(fontSize: Double): Double
    }

    /**
     * 测试与无 `Paint` 场景下的墨迹比近似：CJK 0.87 / ASCII 0.75 / 其它 0.98。
     *
     * `descent` 取 0.24×字号（Roboto / SF Pro 的实际值都在这个量级），
     * 只影响星期与大日期的基线对齐，不影响任何高度。
     */
    object InkRatioFontMetrics : FontMetrics {
        override fun width(text: String, fontSize: Double): Double {
            var sum = 0.0
            for (ch in text) sum += inkRatio(ch) * fontSize
            return sum
        }

        override fun descent(fontSize: Double): Double = fontSize * 0.24

        /** 行盒高 ≈ 1.17×字号（Roboto 的实际值在 1.15~1.2 之间）。 */
        override fun lineHeightOf(fontSize: Double): Double = fontSize * 1.17
    }

    // ------------------------------------------------------------------ 绘制指令

    /** 一条绘制指令。排版阶段定好颜色，绘制阶段只执行（两边不会各量一遍文字）。 */
    sealed interface Op {
        data class FillRounded(
            val rect: Rect,
            val radius: Double,
            val color: Long,
            /** `true` 时先按该 alpha 混合到背景（iOS 的 `withAlphaComponent`）。 */
            val alpha: Double = 1.0,
        ) : Op

        data class Card(
            val rect: Rect,
            val radius: Double,
            val fill: Long,
            val border: Long,
            val shadow: Long,
        ) : Op

        data class Text(
            val text: String,
            val rect: Rect,
            val font: Double,
            val lineHeight: Double,
            val color: Long,
            val align: Align = Align.Left,
            /** 加粗（iOS 用 `weight: .semibold/.bold` 的系统字体）。 */
            val bold: Boolean = false,
            /** `true` 时先按 [alpha] 混合到背景（iOS 的 `withAlphaComponent`）。 */
            val alpha: Double = 1.0,
        ) : Op

        /** 圆角实心条（品牌短线 / 引用左侧色条）。 */
        data class Bar(
            val rect: Rect,
            val radius: Double,
            val color: Long,
            val alpha: Double = 1.0,
        ) : Op

        data class Dot(val cx: Double, val cy: Double, val radius: Double, val color: Long) : Op

        data class Image(
            val src: String,
            val rect: Rect,
            val radius: Double,
            val fallbackColor: Long,
            val fallbackAlpha: Double,
        ) : Op

        data class Checkbox(
            val rect: Rect,
            val checked: Boolean,
            val fill: Long,
            val border: Long,
            val mark: Long,
            val borderAlpha: Double,
        ) : Op
    }

    enum class Align { Left, Right }

    data class Rect(val x: Double, val y: Double, val width: Double, val height: Double) {
        val right: Double get() = x + width
        val bottom: Double get() = y + height
    }

    /** 排版结果：整张图的高度 + 一串绘制指令。 */
    data class Page(val height: Double, val ops: List<Op>)

    // ------------------------------------------------------------------ 配色

    /**
     * 分享图的调色板（`ImageShareService.swift:79-125`）。
     *
     * 与 `Theme.seed`（#2563EB）同源：浅色底用标准品牌色，深色底提亮一档。
     */
    data class Palette(
        val text: Long,
        val sub: Long,
        val faint: Long,
        val accent: Long,
        val bgTop: Long,
        val bgBottom: Long,
        val glowStrong: Long,
        val glowSoft: Long,
        val card: Long,
        val cardBorder: Long,
        val cardBorderAlpha: Double,
        val cardShadow: Long,
        val cardShadowAlpha: Double,
        val quoteBg: Long,
        val quoteBgAlpha: Double,
        val faintLineAlpha: Double,
    ) {
        companion object {
            fun light(): Palette = Palette(
                text = 0xFF15181DL,
                sub = 0xFF5B6472L,
                faint = 0xFF98A1B0L,
                accent = 0xFF2563EBL,
                bgTop = 0xFFF8F9FCL,
                bgBottom = 0xFFEDF0F7L,
                glowStrong = 0xFF2563EBL,
                glowSoft = 0xFF7C5CFFL,
                card = 0xFFFFFFFFL,
                cardBorder = 0xFF15181DL,
                cardBorderAlpha = 0.06,
                cardShadow = 0xFF15181DL,
                cardShadowAlpha = 0.08,
                quoteBg = 0xFF2563EBL,
                quoteBgAlpha = 0.07,
                faintLineAlpha = 0.35,
            )

            fun dark(): Palette = Palette(
                text = 0xFFECEDF1L,
                sub = 0xFFA7AEBBL,
                faint = 0xFF6E7683L,
                accent = 0xFF7C97FFL,
                bgTop = 0xFF0C0F14L,
                bgBottom = 0xFF131722L,
                glowStrong = 0xFF3B6BFFL,
                glowSoft = 0xFF8A5CFFL,
                card = 0xFF1A1F29L,
                cardBorder = 0xFFFFFFFFL,
                cardBorderAlpha = 0.07,
                cardShadow = 0xFF000000L,
                cardShadowAlpha = 0.45,
                quoteBg = 0xFF7C97FFL,
                quoteBgAlpha = 0.14,
                faintLineAlpha = 0.35,
            )
        }
    }

    // ------------------------------------------------------------------ 输入

    /** 一个片段的输入（分享只需要时间、地点与内容）。 */
    data class ShareBlock(val time: Long, val location: String, val parts: List<ContentPart>)

    /**
     * 头部与页脚要用的、**已经本地化**的文案。
     *
     * 时间相关的文案由调用方用 `DateFormats` 备好（跟随 12/24 小时制与 AM/PM 语言），
     * 这一层不依赖 Android 资源 —— 因此可以在 JVM 上精确断言。
     */
    data class ShareTexts(
        /** 品牌名（`EntryAbility_label`：一页时光）。 */
        val brand: String,
        /** 年份（`date_year`：2026年）。 */
        val year: String,
        /** 大日期（`date_day_only`：9月8日）。 */
        val date: String,
        /** 星期（`weekdayName`：周二）。 */
        val weekday: String,
        /**
         * 头部第二行（`read_start_time`：`8:40 开始写`）。
         *
         * **只有时间，没有地点** —— 地点就在下面第一条记录的时间行里，重复一遍反而更乱。
         * `auto_time` 关闭时为 `null`，那一行整块不画（图明显变矮）。
         */
        val startLine: String?,
        /** 页脚里的 `一页时光 · JustADiary`。 */
        val footerBrand: String,
        /** 页脚右侧的 `day_key`（2026-09-08）。 */
        val dayKey: String,
    )

    /**
     * 计算整页的绘制指令。
     *
     * @param showTime `auto_time` 关闭时隐藏头部起始行与每条记录的时间 —— **只影响显示**，
     *   `ShareBlock.time` 仍照常传入（iOS `render(showTime:)` 同一语义）。
     * @param imageSize 图片段落的真实像素尺寸（`src` → 宽高）；拿不到时返回 null，
     *   那时按缺省比例 0.75 算。
     */
    fun makePage(
        texts: ShareTexts,
        blocks: List<ShareBlock>,
        showTime: Boolean,
        palette: Palette,
        metrics: FontMetrics = InkRatioFontMetrics,
        imageSize: (String) -> Pair<Int, Int>? = { null },
        /** 每条记录时间行的文案（`timeOf`，跟随 12/24 小时制）；`showTime` 为假时不看它。 */
        timeLabel: (Long) -> String = { "" },
    ): Page {
        val ops = mutableListOf<Op>()
        var y = TOP

        // 1) 品牌行：左边品牌点 + 名字，右边年份。
        ops += Op.Dot(
            cx = MARGIN + 7,
            cy = y + 17,
            radius = BRAND_DOT_RADIUS,
            color = palette.accent,
        )
        ops += Op.Text(
            text = texts.brand,
            rect = Rect(MARGIN + 26, y, CONTENT - 140, BRAND_H),
            font = BRAND_FONT,
            lineHeight = BRAND_H,
            color = palette.text,
            bold = true,
        )
        ops += Op.Text(
            text = texts.year,
            rect = Rect(MARGIN, y + 5, CONTENT, 26.0),
            font = YEAR_FONT,
            lineHeight = 26.0,
            color = palette.faint,
            align = Align.Right,
        )
        y += BRAND_H + AFTER_BRAND

        // 2) 大日期 + 星期（两者基线对齐）。
        ops += Op.Text(
            text = texts.date,
            rect = Rect(MARGIN, y, CONTENT, DATE_H),
            font = DATE_FONT,
            lineHeight = DATE_H,
            color = palette.text,
            bold = true,
        )
        val dateWidth = textWidth(texts.date, DATE_FONT, metrics)
        // 行盒高 LH 时，第一行基线在盒顶下方 (LH + descender)；用它把星期压到同一条基线上。
        val weekdayLh = ceil(metrics.lineHeightOf(WEEKDAY_FONT))
        val dateBaseline = y + DATE_H + metrics.descent(DATE_FONT)
        ops += Op.Text(
            text = texts.weekday,
            rect = Rect(
                x = MARGIN + dateWidth + 16,
                y = dateBaseline - (weekdayLh + metrics.descent(WEEKDAY_FONT)),
                width = max(40.0, CONTENT - dateWidth - 16),
                height = weekdayLh,
            ),
            font = WEEKDAY_FONT,
            lineHeight = weekdayLh,
            color = palette.sub,
        )
        y += DATE_H + AFTER_DATE

        // 3) 起始时间（只有时间，没有地点 —— 地点在下面第一条记录的时间行里）。
        val meta = texts.startLine?.takeIf { showTime && blocks.any { block -> block.time > 0 } }
        if (meta != null) {
            val metaH = textHeight(meta, META_FONT, CONTENT, META_H, metrics)
            ops += Op.Text(
                text = meta,
                rect = Rect(MARGIN, y, CONTENT, metaH),
                font = META_FONT,
                lineHeight = META_H,
                color = palette.sub,
            )
            y += metaH + AFTER_META
        } else {
            y += AFTER_META
        }

        // 4) 品牌色短线。
        ops += Op.FillRounded(
            rect = Rect(MARGIN, y, RULE_WIDTH, RULE_HEIGHT),
            radius = RULE_HEIGHT / 2,
            color = palette.accent,
        )
        y += RULE_HEIGHT + AFTER_RULE

        // 5) 每条记录：时间 / 地点行 + 卡片。
        var wroteEntry = false
        for (block in blocks) {
            val timeText = if (showTime && block.time > 0) block.time else null
            val locText = block.location.trim()
            val hasContent = block.parts.isNotEmpty()
            if (!hasContent && timeText == null && locText.isEmpty()) continue

            if (wroteEntry) y += BETWEEN_ENTRIES
            wroteEntry = true

            if (timeText != null || locText.isNotEmpty()) {
                var headX = MARGIN
                if (timeText != null) {
                    val label = timeLabel(timeText)
                    ops += Op.Text(
                        text = label,
                        rect = Rect(headX, y, 160.0, ENTRY_HEAD_H),
                        font = TIME_FONT,
                        lineHeight = ENTRY_HEAD_H,
                        color = palette.accent,
                        bold = true,
                    )
                    headX += textWidth(label, TIME_FONT, metrics) + 14
                }
                if (locText.isNotEmpty() && headX < MARGIN + CONTENT - 60) {
                    ops += Op.Text(
                        text = locText,
                        rect = Rect(headX, y + 6, MARGIN + CONTENT - headX, 30.0),
                        font = LOC_FONT,
                        lineHeight = 30.0,
                        color = palette.sub,
                    )
                }
                y += ENTRY_HEAD_H + AFTER_ENTRY_HEAD
            }

            // 空片段（只有时间、没有内容）不画空卡片。
            if (!hasContent) continue
            val cardTop = y
            // 卡片本体要画在**这段内容之前**（iOS 是把卡片指令 insert 到内容指令之前）：
            // 先记下插入点，内容画完再回头插进去 —— 否则卡片会盖住自己的文字。
            val cardIndex = ops.size
            var contentY = cardTop + CARD_PAD_V
            val innerWidth = CONTENT - CARD_PAD_H * 2
            block.parts.forEachIndexed { index, part ->
                if (index > 0) contentY += gapBefore(part)
                contentY += appendPart(
                    part = part,
                    x = MARGIN + CARD_PAD_H,
                    y = contentY,
                    width = innerWidth,
                    ops = ops,
                    palette = palette,
                    metrics = metrics,
                    imageSize = imageSize,
                )
            }
            val cardHeight = contentY - cardTop + CARD_PAD_V
            ops.add(
                cardIndex,
                Op.Card(
                    rect = Rect(MARGIN, cardTop, CONTENT, cardHeight),
                    radius = CARD_RADIUS,
                    fill = palette.card,
                    border = palette.cardBorder,
                    shadow = palette.cardShadow,
                ),
            )
            y = cardTop + cardHeight
        }

        // 6) 页脚：细线 + 品牌 + 当天日期。
        y += BEFORE_FOOTER
        ops += Op.FillRounded(
            rect = Rect(MARGIN, y, CONTENT, 1.0),
            radius = 0.5,
            color = palette.faint,
            // iOS 是 `faint.withAlphaComponent(0.35)`
            alpha = palette.faintLineAlpha,
        )
        y += 22
        ops += Op.Text(
            text = texts.footerBrand,
            rect = Rect(MARGIN, y, CONTENT * 0.6, FOOTER_H),
            font = FOOTER_FONT,
            lineHeight = FOOTER_LH,
            color = palette.faint,
            bold = true,
        )
        ops += Op.Text(
            text = texts.dayKey,
            rect = Rect(MARGIN + CONTENT * 0.4, y, CONTENT * 0.6, FOOTER_H),
            font = FOOTER_FONT,
            lineHeight = FOOTER_LH,
            color = palette.faint,
            align = Align.Right,
            bold = true,
        )
        y += FOOTER_H + BOTTOM

        return Page(height = y, ops = ops)
    }

    /** 段与段之间的间距：标题 / 小标题离上面远一点，离下面的正文近一点。 */
    fun gapBefore(part: ContentPart): Double = when (part.style) {
        ContentPartStyle.title, ContentPartStyle.heading -> LOOSE_GAP
        else -> PART_GAP
    }

    /**
     * 画一段内容，返回它占的高度（`appendPart`）。
     *
     * `x` 是**卡片内的左边界**（已经加过 `CARD_PAD_H`），`width` 是卡片内宽。
     */
    fun appendPart(
        part: ContentPart,
        x: Double,
        y: Double,
        width: Double,
        ops: MutableList<Op>,
        palette: Palette,
        metrics: FontMetrics,
        imageSize: (String) -> Pair<Int, Int>? = { null },
    ): Double {
        val text = ContentFlatten.flattenPart(part)
        return when (part.style) {
            ContentPartStyle.title -> {
                val h = textHeight(text, TITLE_FONT, width, TITLE_LH, metrics)
                ops += Op.Text(
                    text = text,
                    rect = Rect(x, y, width, h),
                    font = TITLE_FONT,
                    lineHeight = TITLE_LH,
                    color = palette.text,
                    bold = true,
                )
                h
            }

            ContentPartStyle.heading -> {
                val h = textHeight(text, HEADING_FONT, width, HEADING_LH, metrics)
                ops += Op.Text(
                    text = text,
                    rect = Rect(x, y, width, h),
                    font = HEADING_FONT,
                    lineHeight = HEADING_LH,
                    color = palette.text,
                    bold = true,
                )
                h
            }

            ContentPartStyle.quote -> {
                val textX = x + QUOTE_TEXT_INSET
                val textW = width - QUOTE_TEXT_RIGHT
                val textH = textHeight(text, QUOTE_FONT, textW, QUOTE_LH, metrics)
                val boxH = textH + QUOTE_PAD_V * 2
                ops += Op.FillRounded(
                    rect = Rect(x, y, width, boxH),
                    radius = QUOTE_RADIUS,
                    color = palette.quoteBg,
                    alpha = palette.quoteBgAlpha,
                )
                ops += Op.Bar(
                    rect = Rect(x, y + QUOTE_BAR_INSET, QUOTE_BAR_W, boxH - QUOTE_BAR_INSET * 2),
                    radius = QUOTE_BAR_W / 2,
                    color = palette.accent,
                )
                ops += Op.Text(
                    text = text,
                    rect = Rect(textX, y + QUOTE_PAD_V, textW, textH),
                    font = QUOTE_FONT,
                    lineHeight = QUOTE_LH,
                    color = palette.text,
                )
                boxH
            }

            ContentPartStyle.list -> appendItems(
                items = part.items.orEmpty(),
                done = null,
                x = x,
                y = y,
                width = width,
                ops = ops,
                palette = palette,
                metrics = metrics,
            )

            ContentPartStyle.todo -> {
                val items = part.items.orEmpty()
                val done = part.done ?: List(items.size) { false }
                appendItems(
                    items = items,
                    done = done,
                    x = x,
                    y = y,
                    width = width,
                    ops = ops,
                    palette = palette,
                    metrics = metrics,
                )
            }

            ContentPartStyle.image -> {
                val boxH = imageHeight(part, width, imageSize)
                val src = part.src
                if (src != null) {
                    ops += Op.Image(
                        src = src,
                        rect = Rect(x, y, width, boxH),
                        radius = IMAGE_RADIUS,
                        fallbackColor = palette.quoteBg,
                        fallbackAlpha = palette.quoteBgAlpha,
                    )
                }
                boxH
            }

            else -> {
                val h = textHeight(text, BODY_FONT, width, BODY_LH, metrics)
                ops += Op.Text(
                    text = text,
                    rect = Rect(x, y, width, h),
                    font = BODY_FONT,
                    lineHeight = BODY_LH,
                    color = palette.text,
                )
                h
            }
        }
    }

    /**
     * 列表 / 待办的逐项绘制。
     *
     * 列表用品牌色圆点（标记列 30），待办用 30×30 复选框（标记列 48）；
     * 已完成项的**文字用 `sub` 色**（视觉上"划掉"），未完成用正文色。
     */
    private fun appendItems(
        items: List<String>,
        done: List<Boolean>?,
        x: Double,
        y: Double,
        width: Double,
        ops: MutableList<Op>,
        palette: Palette,
        metrics: FontMetrics,
    ): Double {
        var cursor = y
        val markerW = if (done == null) LIST_MARKER_W else TODO_MARKER_W
        val textW = width - markerW
        items.forEachIndexed { i, item ->
            val isDone = done != null && i in done.indices && done[i]
            val h = textHeight(item, ITEM_FONT, textW, ITEM_LH, metrics)
            if (done == null) {
                ops += Op.Dot(
                    cx = x + 9,
                    cy = cursor + ITEM_LH / 2 - 3,
                    radius = ITEM_DOT_RADIUS,
                    color = palette.accent,
                )
            } else {
                ops += Op.Checkbox(
                    rect = Rect(x, cursor + 8, CHECKBOX_SIZE, CHECKBOX_SIZE),
                    checked = isDone,
                    fill = palette.accent,
                    border = palette.faint,
                    mark = 0xFFFFFFFFL,
                    borderAlpha = palette.faintLineAlpha,
                )
            }
            ops += Op.Text(
                text = item,
                rect = Rect(x + markerW, cursor, textW, h),
                font = ITEM_FONT,
                lineHeight = ITEM_LH,
                color = if (isDone) palette.sub else palette.text,
            )
            cursor += h + ITEM_GAP
        }
        return max(0.0, cursor - y - ITEM_GAP)
    }

    /**
     * 图片高度：`max(120, width × ratio)`。
     *
     * 比例优先取段落里存的 `h/w`，缺失时问 [imageSize]（读文件真实尺寸），再缺省 0.75。
     */
    fun imageHeight(part: ContentPart, width: Double, imageSize: (String) -> Pair<Int, Int>? = { null }): Double {
        val w = part.w ?: 0.0
        val h = part.h ?: 0.0
        val ratio = when {
            w > 0 && h > 0 -> h / w
            else -> {
                val size = part.src?.let(imageSize)
                if (size != null && size.first > 0) size.second.toDouble() / size.first else IMAGE_FALLBACK_RATIO
            }
        }
        return max(IMAGE_MIN_HEIGHT, width * ratio)
    }

    /** 贪心逐字符换行（与阅读 / 编辑的排版同一策略：CJK 逐字、ASCII 也逐字符）。 */
    fun wrappedLines(
        text: String,
        fontSize: Double,
        maxWidth: Double,
        metrics: FontMetrics = InkRatioFontMetrics,
    ): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var currentWidth = 0.0
        for (ch in text) {
            val w = metrics.width(ch.toString(), fontSize)
            if (currentWidth + w > maxWidth && current.isNotEmpty()) {
                lines += current.toString()
                current.setLength(0)
                current.append(ch)
                currentWidth = w
            } else {
                current.append(ch)
                currentWidth += w
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

    /** 字符的墨迹比（[InkRatioFontMetrics] 用）。 */
    fun inkRatio(ch: Char): Double = when {
        isCjk(ch) -> 0.87
        ch.code < 128 -> 0.75
        else -> 0.98
    }

    private fun isCjk(ch: Char): Boolean {
        val cp = ch.code
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x3000..0x303F
    }

    /** 页面高度的一个保守下限：只有页眉与页脚时的高度（iOS 测试要求 200~420）。 */
    fun headerFooterOnlyHeight(): Double = TOP + BRAND_H + AFTER_BRAND + DATE_H + AFTER_DATE +
        AFTER_META + RULE_HEIGHT + AFTER_RULE + BEFORE_FOOTER + 1 + 22 + FOOTER_H + BOTTOM
}
