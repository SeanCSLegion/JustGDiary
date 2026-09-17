package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.service.SearchUtil
import kotlin.math.max
import kotlin.math.min

/**
 * 阅读视图的渲染模型与在篇内搜索。纯逻辑，可在 JVM 上断言。
 *
 * 对应 iOS 的两处：
 * - `Views/Diary/ReadTextView.swift`（用 `PartsCodec.attributedString` 建富文本 +
 *   `SearchUtil.highlightSegments` 做高亮 + 附件区用于待办点选与图片点击）；
 * - `Views/Diary/MediaViews.swift:25` 与 `RichTextEngine.swift:677-680` 的图片显示尺寸规则。
 *
 * Android 侧不再用一个"大 TextView"，而是把每个段落渲染成一个 Compose 组件
 * （[PartRender] 的三种形态），因此这里的职责是**把 `content_json` 变成可直接渲染的模型**。
 */
object ReadLayout {

    /** 图片显示宽度：`min(可用宽 - 24, 343)`。 */
    const val IMAGE_SIDE_MARGIN = 24.0
    const val IMAGE_MAX_WIDTH = 343.0

    /** 插入编辑器时的宽度规则（`RichTextEngine.swift:677-680`）。 */
    const val INSERT_MAX_WIDTH = 343.0
    const val INSERT_SIDE_MARGIN = 24.0

    /** 图片显示高度的下限（`max(40, ratio × maxW)`）。 */
    const val IMAGE_MIN_HEIGHT = 40.0

    /** 缺少 `w`/`h` 时的兜底宽高比（高 / 宽）。 */
    const val IMAGE_FALLBACK_RATIO = 0.75

    /** 阅读/编辑正文列的最大宽度（方案 §6.1 引用的宽屏硬约束）。 */
    const val READING_MAX_WIDTH = 660.0
    const val EDITING_MAX_WIDTH = 620.0

    // ------------------------------------------------------------------ 图片尺寸

    /** 卡片内联图片的显示宽度。 */
    fun imageDisplayWidth(availableWidth: Double): Double =
        max(1.0, min(availableWidth - IMAGE_SIDE_MARGIN, IMAGE_MAX_WIDTH))

    /** 插入图片时的宽度（编辑器用）。 */
    fun imageInsertWidth(availableWidth: Double): Double =
        max(1.0, min(availableWidth - INSERT_SIDE_MARGIN, INSERT_MAX_WIDTH))

    /** 图片的显示宽高比（高 / 宽）。 */
    fun imageRatio(part: ContentPart): Double {
        val w = part.w ?: 0.0
        val h = part.h ?: 0.0
        return if (w > 0 && h > 0) h / w else IMAGE_FALLBACK_RATIO
    }

    /** 图片的显示高度：`max(40, ratio × 显示宽)`。 */
    fun imageDisplayHeight(part: ContentPart, displayWidth: Double): Double =
        max(IMAGE_MIN_HEIGHT, imageRatio(part) * displayWidth)

    /**
     * 渲染时的解码目标像素：`max(storedW, storedH) × 3`（`MediaViews.swift:25`）。
     * 返回 null 表示库里没记宽高，此时用预览档（1600）。
     */
    fun renderMaxPixel(part: ContentPart): Int? {
        val w = part.w ?: 0.0
        val h = part.h ?: 0.0
        if (w <= 0 && h <= 0) return null
        return max(1, (max(w, h) * 3).toInt())
    }

    // ------------------------------------------------------------------ 渲染模型

    /** 一个段落渲染成什么。 */
    sealed interface PartRender {
        /** 一个带样式的段落（大标题/小标题/正文/引用）。 */
        data class Paragraph(
            val style: String,
            val text: String,
            val spans: List<Span>,
            val alignCenter: Boolean,
        ) : PartRender

        /** 列表或待办：逐项渲染，待办带勾选状态。 */
        data class Items(
            val style: String,
            val items: List<String>,
            val done: List<Boolean>,
        ) : PartRender {
            val isTodo: Boolean get() = style == ContentPartStyle.todo
        }

        /** 内联图片。 */
        data class Image(
            val src: String,
            val width: Double?,
            val height: Double?,
        ) : PartRender
    }

    /** 一段同格式的文本（粗体/斜体/删除线/下划线）。 */
    data class Span(
        val text: String,
        val bold: Boolean,
        val italic: Boolean,
        val strike: Boolean,
        val underline: Boolean,
    )

    /**
     * 把 `content_json` 解析出来的段落映射成渲染模型。
     *
     * 未开启的行内样式在解码时已经是 `null`（见 `ContentFlatten`），这里统一归一为 `false`。
     */
    fun renderModel(parts: List<ContentPart>): List<PartRender> = parts.mapNotNull { part ->
        when (part.style) {
            ContentPartStyle.image -> part.src?.takeIf { it.isNotEmpty() }?.let {
                PartRender.Image(src = it, width = part.w, height = part.h)
            }

            ContentPartStyle.list, ContentPartStyle.todo -> {
                val items = part.items.orEmpty()
                if (items.isEmpty()) {
                    null
                } else {
                    PartRender.Items(
                        style = part.style,
                        items = items,
                        done = part.done.orEmpty(),
                    )
                }
            }

            else -> {
                val runs = part.runs
                val spans = if (!runs.isNullOrEmpty()) {
                    runs.map { run ->
                        Span(
                            text = run.text,
                            bold = run.bold == true,
                            italic = run.italic == true,
                            strike = run.strike == true,
                            underline = run.underline == true,
                        )
                    }
                } else {
                    part.text?.let { listOf(Span(it, false, false, false, false)) }.orEmpty()
                }
                if (spans.isEmpty()) {
                    null
                } else {
                    PartRender.Paragraph(
                        style = part.style,
                        text = spans.joinToString(separator = "") { it.text },
                        spans = spans,
                        alignCenter = part.align == "center",
                    )
                }
            }
        }
    }

    /** 段落的纯文本（供搜索与高亮）。 */
    fun plainText(part: ContentPart): String = ContentFlatten.flattenPart(part)

    /** 段落内的高亮分段（`ReadTextView.applyHighlight` 的等价物）。 */
    fun highlight(part: ContentPart, keyword: String): List<SearchUtil.Segment> {
        val text = plainText(part)
        if (keyword.isBlank() || text.isEmpty()) return listOf(SearchUtil.Segment(text, false))
        return SearchUtil.highlightSegments(text, keyword)
    }
}

/**
 * 在篇内搜索。对应 iOS `DiaryPageView` 的条目内搜索栏（上一个 / 下一个匹配）。
 *
 * 匹配以**段落为单位**记录，因为 Android 侧是逐段落渲染的：这样"跳到第 N 个匹配"
 * 可以直接定位到段落与段内偏移，不需要维护一个大文本的全局索引。
 */
object DiarySearch {

    /** 一个匹配：`partIndex` 是第几个段落，`[start, end)` 是段内字符区间。 */
    data class Match(val partIndex: Int, val start: Int, val end: Int)

    /**
     * 找出全部匹配，按（段落序、段内位置）排序。
     *
     * 同一个段落里重叠或相接的命中会合并（与 `SearchUtil.mergeRanges` 同一规则）。
     */
    fun findMatches(parts: List<ContentPart>, keyword: String): List<Match> {
        val terms = SearchUtil.terms(keyword).map { it.lowercase() }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()

        val matches = mutableListOf<Match>()
        parts.forEachIndexed { partIndex, part ->
            val text = ReadLayout.plainText(part)
            if (text.isEmpty()) return@forEachIndexed
            val lower = text.lowercase()
            val ranges = mutableListOf<IntRange>()
            for (term in terms) {
                var from = 0
                while (true) {
                    val at = lower.indexOf(term, startIndex = from)
                    if (at < 0) break
                    ranges.add(at until (at + term.length))
                    from = at + term.length
                    if (from >= lower.length) break
                }
            }
            if (ranges.isEmpty()) return@forEachIndexed
            for (range in merge(ranges)) {
                matches.add(Match(partIndex, range.first, range.last + 1))
            }
        }
        return matches
    }

    /** 下一个匹配（在末尾回到开头）。 */
    fun nextIndex(current: Int, count: Int): Int = when {
        count <= 0 -> -1
        current < 0 -> 0
        current + 1 >= count -> 0
        else -> current + 1
    }

    /** 上一个匹配（在开头回到末尾）。 */
    fun previousIndex(current: Int, count: Int): Int = when {
        count <= 0 -> -1
        current <= 0 -> count - 1
        else -> current - 1
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                merged[merged.size - 1] = last.first..maxOf(last.last, range.last)
            } else {
                merged.add(range)
            }
        }
        return merged
    }
}
