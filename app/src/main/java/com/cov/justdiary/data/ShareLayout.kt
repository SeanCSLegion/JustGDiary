package com.cov.justdiary.data

import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import kotlin.math.max

/**
 * 分享长图的**版式计算**。移植自 iOS `Services/ImageShareService.swift`（`ShareRenderer`）。
 *
 * 这一层只算"每块内容画在哪、多高"，不碰绘制 —— 因此可以在 JVM 上断言
 * （方案 §8.3：常量必须逐项对齐，否则导出样式会漂移）。
 *
 * ## 与 iOS 的差异（有意修正 3 个已知缺陷）
 *
 * | # | iOS 行为 | 这里 |
 * | --- | --- | --- |
 * | D5 | 地点行固定按 34 前进，但它是**可换行**文本 → 换行时会与下一块重叠 | 按实际换行行数 × 34 前进 |
 * | D6 | 大标题/小标题固定 52+12 / 46+10，**不换行** → 长标题溢出框外 | 按实际行数 × 52 + 12 / × 46 + 10 |
 * | D7 | 逐字符测宽用真实字体度量 | 通过 [CharWidthProvider] 注入：真机用 `Paint.measureText`，测试用等宽近似（见下） |
 *
 * ## 一处近似
 *
 * iOS 用 `boundingRect` 逐字符量宽。这一层把量宽抽象成 [CharWidthProvider]：
 * 真机实现走 `Paint`（精确），测试与预览走 [InkRatioWidthProvider]（CJK 0.87 / ASCII 0.75 /
 * 其它 0.98 —— 与 iOS 计算行高时用的墨迹比一致）。因此**分享图的最终换行位置在真机上与
 * iOS 基本一致，但不是在字节层面保证一致**；分享图是本地生成物，不属于跨端契约。
 */
object ShareLayout {

    // ------------------------------------------------------------------ 常量（逐字对齐 iOS）

    const val WIDTH: Double = 720.0
    const val BLOCK_LEFT: Double = 108.0
    const val SIDE: Double = 48.0
    const val TIMELINE_LINE_X: Double = 61.0
    const val TIMELINE_LINE_W: Double = 3.0
    const val DATE_Y: Double = 64.0
    const val FOOTER_H: Double = 34.0
    const val CONTENT_WIDTH: Double = WIDTH - BLOCK_LEFT - SIDE

    /** 日期（62 bold）之后推进 70。 */
    const val DATE_ADVANCE: Double = 70.0
    const val DATE_FONT: Double = 62.0

    /** 起始行（28）的行高与字体。 */
    const val START_LINE_FONT: Double = 28.0
    const val START_LINE_HEIGHT: Double = 40.0

    /** 地点行：24pt、行高 34（iOS 固定推进 34，这里按实际行数算）。 */
    const val LOCATION_FONT: Double = 24.0
    const val LOCATION_LINE_HEIGHT: Double = 34.0

    /** 每个片段内：时间行（26 bold）之后推进 36 + 20。 */
    const val TIME_FONT: Double = 26.0
    const val TIME_ADVANCE: Double = 36.0
    const val TIME_GAP: Double = 20.0

    /** 片段之间的间距。 */
    const val BLOCK_SPACING: Double = 36.0

    /** 日期与首个片段之间的发丝线之后的推进：`24 + 2`。 */
    const val HAIRLINE_ADVANCE: Double = 26.0

    /** 墨迹比：CJK 0.87 / ASCII 0.75 / 其它 0.98（iOS 计算行高用）。 */
    const val INK_CJK: Double = 0.87
    const val INK_ASCII: Double = 0.75
    const val INK_OTHER: Double = 0.98

    /** 引用块左右各留 22（文本框宽 = contentWidth - 22）。 */
    const val QUOTE_INSET: Double = 22.0

    /** 图片最小高度与缺省宽高比。 */
    const val IMAGE_MIN_HEIGHT: Double = 120.0
    const val IMAGE_FALLBACK_RATIO: Double = 0.75

    /** 逐字符量宽。 */
    fun interface CharWidthProvider {
        fun width(ch: Char, fontSize: Double): Double
    }

    /** 测试/预览用的墨迹比近似量宽。 */
    object InkRatioWidthProvider : CharWidthProvider {
        override fun width(ch: Char, fontSize: Double): Double = inkRatio(ch) * fontSize
    }

    // ------------------------------------------------------------------ 样式度量

    /** 某一段落样式的字号与行高（`ImageShareService.swift:182-200`）。 */
    data class StyleMetrics(val fontSize: Double, val lineHeight: Double)

    fun metricsFor(style: String): StyleMetrics = when (style) {
        ContentPartStyle.title -> StyleMetrics(36.0, 52.0)
        ContentPartStyle.heading -> StyleMetrics(32.0, 46.0)
        ContentPartStyle.quote -> StyleMetrics(30.0, 40.0)
        ContentPartStyle.list, ContentPartStyle.todo -> StyleMetrics(30.0, 55.0)
        ContentPartStyle.image -> StyleMetrics(30.0, 0.0)
        else -> StyleMetrics(31.0, 50.0)
    }

    // ------------------------------------------------------------------ 版式

    /** 一个片段的输入（分享只需要时间、地点与内容）。 */
    data class ShareBlock(val time: Long, val location: String, val parts: List<ContentPart>)

    /** 一个绘制矩形（左上角 + 宽高，坐标相对画布左上角）。 */
    data class LayoutRect(val x: Double, val y: Double, val width: Double, val height: Double)

    data class Layout(
        /** 整张图的高度。 */
        val height: Double,
        /** 每个片段的包围盒。 */
        val blockRects: List<LayoutRect>,
        /** 每个片段里每个段落的包围盒（`partRects[blockIndex][partIndex]`）。 */
        val partRects: List<List<LayoutRect>>,
        /** 日期文本的基线 y。 */
        val dateY: Double,
        /** 起始行的 y 与高度。 */
        val startLineY: Double,
        val startLineHeight: Double,
        /** 时间轴的起止 y。 */
        val timelineTop: Double,
        val timelineBottom: Double,
    )

    /**
     * 计算整张分享图的版式。
     *
     * @param charWidth 逐字符量宽；真机传 `Paint` 实现，测试传 [InkRatioWidthProvider]。
     */
    fun layout(
        dateText: String,
        startLine: String,
        blocks: List<ShareBlock>,
        charWidth: CharWidthProvider = InkRatioWidthProvider,
    ): Layout {
        var y = DATE_Y
        y += DATE_ADVANCE
        val startLineY = y
        // iOS 的 `startLineHeight` 是**行数 × 行高**（不带墨迹），与 `wrappedHeight` 不同：
        // 起始行只是"写了几行就占几行"，不像引用块那样需要额外的墨迹与内边距。
        val startLineHeight = max(1, wrappedLines(startLine, START_LINE_FONT, CONTENT_WIDTH, charWidth).size) *
            START_LINE_HEIGHT
        y += startLineHeight
        y += HAIRLINE_ADVANCE

        val timelineTop = y
        val blockRects = mutableListOf<LayoutRect>()
        val partRects = mutableListOf<List<LayoutRect>>()

        for (block in blocks) {
            val blockTop = y
            y += TIME_ADVANCE
            y += TIME_GAP

            // D5：地点行按**实际换行行数**前进，而不是固定 34
            val locationLines = wrappedLines(
                block.location,
                fontSize = LOCATION_FONT,
                maxWidth = CONTENT_WIDTH,
                charWidth = charWidth,
            )
            y += max(1, locationLines.size) * LOCATION_LINE_HEIGHT

            val rects = block.parts.map { part ->
                val rect = partRect(part, y, charWidth)
                y += rect.height
                rect
            }
            partRects.add(rects)
            y += BLOCK_SPACING
            blockRects.add(LayoutRect(0.0, blockTop, WIDTH, y - blockTop))
        }

        val timelineBottom = y - BLOCK_SPACING
        y += FOOTER_H

        return Layout(
            height = y,
            blockRects = blockRects,
            partRects = partRects,
            dateY = DATE_Y,
            startLineY = startLineY,
            startLineHeight = startLineHeight,
            timelineTop = timelineTop,
            timelineBottom = max(timelineTop, timelineBottom),
        )
    }

    /** 单个段落的包围盒（`ImageShareService.swift:93-116`）。 */
    fun partRect(part: ContentPart, y: Double, charWidth: CharWidthProvider = InkRatioWidthProvider): LayoutRect {
        val metrics = metricsFor(part.style)
        val text = ContentFlatten.flattenPart(part)
        return when (part.style) {
            ContentPartStyle.title -> {
                // D6：按实际行数算高度，长标题不再溢出
                val lines = wrappedLines(text, metrics.fontSize, CONTENT_WIDTH, charWidth)
                LayoutRect(0.0, y, WIDTH, max(1, lines.size) * metrics.lineHeight + 12.0)
            }

            ContentPartStyle.heading -> {
                val lines = wrappedLines(text, metrics.fontSize, CONTENT_WIDTH, charWidth)
                LayoutRect(0.0, y, WIDTH, max(1, lines.size) * metrics.lineHeight + 10.0)
            }

            ContentPartStyle.quote -> {
                val height = wrappedHeight(
                    text = text,
                    fontSize = metrics.fontSize,
                    maxWidth = CONTENT_WIDTH - QUOTE_INSET,
                    lineHeight = metrics.lineHeight,
                    charWidth = charWidth,
                    padding = 40.0,
                )
                LayoutRect(0.0, y, WIDTH, height)
            }

            ContentPartStyle.list, ContentPartStyle.todo -> {
                val items = part.items.orEmpty()
                LayoutRect(0.0, y, WIDTH, items.size * metrics.lineHeight)
            }

            ContentPartStyle.image -> LayoutRect(0.0, y, WIDTH, imageHeight(part))

            else -> {
                val lines = wrappedLines(text, metrics.fontSize, CONTENT_WIDTH, charWidth)
                LayoutRect(0.0, y, WIDTH, lines.size * metrics.lineHeight + 10.0)
            }
        }
    }

    /**
     * 图片高度：`max(120, contentWidth * ratio)`，比例取 `h/w`，
     * 缺失时按加载到的图片算，再不行用 0.75（`ImageShareService.swift:135-147`）。
     */
    fun imageHeight(part: ContentPart, fallbackRatio: Double = IMAGE_FALLBACK_RATIO): Double {
        val w = part.w ?: 0.0
        val h = part.h ?: 0.0
        val ratio = if (w > 0 && h > 0) h / w else fallbackRatio
        return max(IMAGE_MIN_HEIGHT, CONTENT_WIDTH * ratio)
    }

    /**
     * 引用块/起始行的高度：`max(1, 行数 - 1) * 行高 + 墨迹 + padding`
     * （`ImageShareService.swift:118-133`）。墨迹取所有行里最大的"字符墨迹比 × 字号"。
     */
    fun wrappedHeight(
        text: String,
        fontSize: Double,
        maxWidth: Double,
        lineHeight: Double,
        charWidth: CharWidthProvider = InkRatioWidthProvider,
        padding: Double,
    ): Double {
        val lines = wrappedLines(text, fontSize, maxWidth, charWidth)
        var ink = 0.0
        for (line in lines) {
            var maxRatio = 0.0
            for (ch in line) maxRatio = max(maxRatio, inkRatio(ch))
            ink = max(ink, maxRatio * fontSize)
        }
        return max(1, lines.size - 1) * lineHeight + ink + padding
    }

    /** 贪心逐字符换行（`ImageShareService.swift:149-173`）。 */
    fun wrappedLines(
        text: String,
        fontSize: Double,
        maxWidth: Double,
        charWidth: CharWidthProvider = InkRatioWidthProvider,
        maxLines: Int = Int.MAX_VALUE,
    ): List<String> {
        val lines = mutableListOf<String>()
        val current = StringBuilder()
        var currentWidth = 0.0
        for (ch in text) {
            val w = charWidth.width(ch, fontSize)
            if (currentWidth + w > maxWidth && current.isNotEmpty()) {
                lines.add(current.toString())
                if (lines.size >= maxLines) return lines
                current.setLength(0)
                current.append(ch)
                currentWidth = w
            } else {
                current.append(ch)
                currentWidth += w
            }
        }
        if (current.isNotEmpty()) lines.add(current.toString())
        return lines
    }

    /** 字符的墨迹比（行高计算用；顺带作为测试用的量宽比例）。 */
    fun inkRatio(ch: Char): Double = when {
        isCjk(ch) -> INK_CJK
        ch.code < 128 -> INK_ASCII
        else -> INK_OTHER
    }

    private fun isCjk(ch: Char): Boolean {
        val cp = ch.code
        return cp in 0x4E00..0x9FFF || cp in 0x3400..0x4DBF || cp in 0x3000..0x303F
    }
}
