package com.cov.justdiary.ui.diary

import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.service.SearchUtil
import java.util.Locale
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

    /** 插入编辑器时的宽度规则（`RichTextEngine.swift:677-680`）。 */
    const val INSERT_MAX_WIDTH = 343.0
    const val INSERT_SIDE_MARGIN = 24.0

    /** 图片尺寸的两条下限（规范 §2.6：宽 ≥ 60dp、高 ≥ 40dp）。 */
    const val IMAGE_MIN_WIDTH = 60.0
    const val IMAGE_MIN_HEIGHT = 40.0

    /** 图片上下各留 10dp（iOS `EditorDesignSize.imageSpacing = 17 × 0.6`）。 */
    const val IMAGE_SPACING = 10.0

    /** 缺少 `w`/`h` 时的兜底宽高比（高 / 宽）。 */
    const val IMAGE_FALLBACK_RATIO = 0.75

    /**
     * 正文列宽上限（阅读 = 编辑）。
     *
     * 规范 §6 与 `docs/android-design/04-diary.md` §2.6：`min(660, 宽 − 32)` 居中，
     * **两种模式同一条列宽** —— 进出编辑卡时文字不能左右跳。
     * （iOS 历史上阅读 660 / 编辑 620，已废弃。）
     */
    const val CONTENT_MAX_WIDTH = 660.0

    /** 页面左右内边距（公式里的那 32）。 */
    const val PAGE_PADDING = 16.0

    // ------------------------------------------------------------------ 图片尺寸

    /**
     * 卡片内联图片的显示宽度 = **正文列宽**（规范 §2.6）。
     *
     * 与 iOS 的区别：iOS 的阅读图还有 `343` 的硬上限（那是按旧版列宽定的），
     * 规范 v3 把它去掉了 —— 图宽跟着列宽走，只有 60dp 的下限。
     */
    fun imageDisplayWidth(availableWidth: Double): Double =
        max(IMAGE_MIN_WIDTH, availableWidth)

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
 * ## 一套算法（规范 §2.7）
 *
 * iOS 的命中**计数**与**高亮**是两套算法（ViewModels 按 part 扫描计数，
 * 每个 chunk 各自扫描做高亮），于是跨 chunk 的关键词"只计数不高亮"。
 * Android 侧刻意只留**这一个**函数：计数（[DiaryPageViewModel]）与高亮（阅读页）
 * 都从它的结果出发，两者不可能对不上。
 *
 * 匹配以**文本单元**为单位记录：
 * - 普通段落（含标题 / 引用）→ 整个段落是一段文本，`itemIndex = -1`；
 * - 列表 / 待办 → **逐项**成段（`itemIndex` = 项下标）。
 *
 * 后者很重要：`flattenPart` 把 items 用空串拼起来，若按整段搜索，
 * 一个命中会跨过两项的边界、而渲染时又落在两个 `Text` 上 —— 那是 iOS 那类
 * "计数对了、高亮错了"的来源。
 */
object DiarySearch {

    /** 一个匹配：`partIndex` 是第几个段落；[itemIndex] 为 -1 表示整段，否则是列表项下标。 */
    data class Match(val partIndex: Int, val itemIndex: Int, val start: Int, val end: Int) {
        /** 这个匹配的文本单元是不是列表 / 待办的一项。 */
        val isItem: Boolean get() = itemIndex >= 0
    }

    /** 一个段落里参与搜索的文本单元：`itemIndex` → 文本。普通段落只有 `-1 → 整段`。 */
    fun units(part: ContentPart): List<Pair<Int, String>> {
        val items = part.items
        if (!items.isNullOrEmpty() &&
            (part.style == ContentPartStyle.list || part.style == ContentPartStyle.todo)
        ) {
            return items.mapIndexed { index, text -> index to text }
        }
        val text = ReadLayout.plainText(part)
        return if (text.isEmpty()) emptyList() else listOf(-1 to text)
    }

    /**
     * 找出全部匹配，按（段落序、项序、段内位置）排序。
     *
     * 同一单元里重叠或相接的命中会合并（与 `SearchUtil.mergeRanges` 同一规则）。
     */
    fun findMatches(parts: List<ContentPart>, keyword: String): List<Match> {
        val terms = SearchUtil.terms(keyword).map { it.lowercase(Locale.ROOT) }.filter { it.isNotEmpty() }
        if (terms.isEmpty()) return emptyList()

        val matches = mutableListOf<Match>()
        parts.forEachIndexed { partIndex, part ->
            for ((itemIndex, text) in units(part)) {
                for (range in rangesIn(text, terms)) {
                    matches.add(Match(partIndex, itemIndex, range.first, range.last + 1))
                }
            }
        }
        return matches
    }

    /** 一处高亮：区间 + 它是不是"当前正在看的那一个"（`当前序号` 指向的命中）。 */
    data class Highlight(val range: IntRange, val current: Boolean)

    /**
     * 某个文本单元要着色的区间（绝对下标，左闭右开）。
     *
     * 渲染侧的**唯一**高亮入口：传进来的 [matches] 就是计数用的那一份
     * （`DiarySearch.findMatches` 的结果），因此"数到几个"与"画出几个"永远一致。
     *
     * [current] 只对**完全相同的那个命中**为真（段落、项、起止都要对上）：
     * 于是"上一个 / 下一个"跳过去以后，用户能一眼看出看的是第几个
     * （iOS 侧没有这个区分，所有命中一个样式 —— 这是有意补上的）。
     */
    fun highlights(
        matches: List<Match>,
        partIndex: Int,
        itemIndex: Int,
        current: Match? = null,
    ): List<Highlight> = matches
        .filter { it.partIndex == partIndex && it.itemIndex == itemIndex }
        .map { Highlight(it.start until it.end, current != null && it == current) }

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

    /** 一个单元里的全部命中区间（已合并）。 */
    private fun rangesIn(text: String, terms: List<String>): List<IntRange> {
        if (text.isEmpty()) return emptyList()
        val lower = text.lowercase(Locale.ROOT)
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
        return if (ranges.isEmpty()) emptyList() else merge(ranges)
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

/**
 * 阅读页 `LazyColumn` 的下标换算。纯函数，因为它错一位的后果很隐蔽：
 * 命中跳转会**安静地滚到相邻的那张卡**（真机实测：少算了 Hero 那一项，
 * 「下一个匹配」总是落在目标卡的上一张，看起来"跳了但没跳对"）。
 */
object DiaryScroll {

    /**
     * 卡片之前的固定项数：只有 Hero 一项。
     *
     * （搜索栏**不进列表** —— 它是悬浮在内容之上的，见 `DiarySearchBarOverlay`。）
     */
    const val LEADING_ITEMS: Int = 1

    /** 第 [blockIndex] 张卡片在列表里的绝对下标。 */
    fun itemIndexForBlock(blockIndex: Int): Int = (blockIndex + LEADING_ITEMS).coerceAtLeast(0)
}
