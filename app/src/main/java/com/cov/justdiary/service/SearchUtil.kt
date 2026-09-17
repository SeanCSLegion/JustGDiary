package com.cov.justdiary.service

import java.util.Locale

/**
 * 搜索相关的纯函数，移植自 iOS `JustDiary/Services/SearchUtil.swift`。
 *
 * 三件事：LIKE 转义、关键词高亮分段、无 FTS 时的兜底摘要（±18 字窗口 + 正则包 `<hl>`）。
 */
object SearchUtil {

    /** 转义 LIKE 的通配符，配合 `ESCAPE '\'` 使用。 */
    fun escapeLikeTerm(term: String): String {
        val out = StringBuilder(term.length + 4)
        for (ch in term) {
            if (ch == '\\' || ch == '%' || ch == '_') out.append('\\')
            out.append(ch)
        }
        return out.toString()
    }

    /** 高亮分段：`hit = true` 的段需要按命中样式渲染。 */
    data class Segment(val text: String, val hit: Boolean)

    /** 按关键词切分文本（先按 `<hl>` 优先，见 [highlightSnippet]）。 */
    fun highlightSegments(text: String, keyword: String): List<Segment> {
        val terms = terms(keyword)
        if (terms.isEmpty()) return listOf(Segment(text, false))

        val lower = text.lowercase(Locale.ROOT)
        val ranges = mutableListOf<IntRange>()
        for (term in terms) {
            val target = term.lowercase(Locale.ROOT)
            if (target.isEmpty()) continue
            var from = 0
            while (true) {
                val at = lower.indexOf(target, startIndex = from)
                if (at < 0) break
                ranges.add(at until (at + target.length))
                from = at + target.length
                if (from >= lower.length) break
            }
        }
        if (ranges.isEmpty()) return listOf(Segment(text, false))

        val merged = mergeRanges(ranges)
        val segments = mutableListOf<Segment>()
        var cursor = 0
        for (range in merged) {
            if (range.first > cursor) {
                segments.add(Segment(text.substring(cursor, range.first), false))
            }
            // 用原串切片，避免 lower/原串长度不一致（少数语言的大小写折叠会改变长度）
            val end = (range.last + 1).coerceAtMost(text.length)
            val start = range.first.coerceAtMost(text.length)
            if (start < end) segments.add(Segment(text.substring(start, end), true))
            cursor = end
        }
        if (cursor < text.length) {
            segments.add(Segment(text.substring(cursor), false))
        }
        return segments
    }

    /** 解析 snippet 里的 `<hl>…</hl>` 标记。 */
    fun highlightSnippet(snippet: String): List<Segment> {
        val segments = mutableListOf<Segment>()
        var remaining = snippet
        while (true) {
            val open = remaining.indexOf("<hl>")
            if (open < 0) {
                if (remaining.isNotEmpty()) segments.add(Segment(remaining, false))
                break
            }
            if (open > 0) segments.add(Segment(remaining.substring(0, open), false))
            val afterOpen = remaining.substring(open + 4)
            val close = afterOpen.indexOf("</hl>")
            if (close < 0) {
                segments.add(Segment(afterOpen, true))
                break
            }
            val hitText = afterOpen.substring(0, close)
            if (hitText.isNotEmpty()) segments.add(Segment(hitText, true))
            remaining = afterOpen.substring(close + 5)
        }
        return segments
    }

    /**
     * 没有 FTS snippet 时的兜底摘要：在 `diary.summary` 里找首个命中，
     * 前后各取 18 个字符，两侧补省略号，再用大小写不敏感的正则逐词包 `<hl>`。
     */
    fun buildFallbackSnippet(text: String, keyword: String): String {
        val terms = terms(keyword)
        if (terms.isEmpty() || text.isEmpty()) return ""

        val lower = text.lowercase(Locale.ROOT)
        var hitStart = -1
        var hitEnd = -1
        for (term in terms) {
            val at = lower.indexOf(term.lowercase(Locale.ROOT))
            if (at >= 0) {
                hitStart = at
                hitEnd = at + term.length
                break
            }
        }
        if (hitStart < 0) return ""

        val start = (hitStart - WINDOW).coerceAtLeast(0)
        val end = (hitEnd + WINDOW).coerceAtMost(text.length)
        var result = buildString {
            if (start > 0) append(ELLIPSIS)
            append(text, start, end)
            if (end < text.length) append(ELLIPSIS)
        }

        for (term in terms) {
            val pattern = runCatching {
                Regex(Regex.escape(term), RegexOption.IGNORE_CASE)
            }.getOrNull() ?: continue
            val matches = pattern.findAll(result).toList()
            if (matches.isEmpty()) continue
            val rebuilt = StringBuilder()
            var cursor = 0
            for (match in matches) {
                if (match.range.first > cursor) {
                    rebuilt.append(result, cursor, match.range.first)
                }
                rebuilt.append("<hl>").append(match.value).append("</hl>")
                cursor = match.range.last + 1
            }
            if (cursor < result.length) rebuilt.append(result, cursor, result.length)
            result = rebuilt.toString()
        }
        return result
    }

    fun terms(keyword: String): List<String> =
        keyword.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        val sorted = ranges.sortedBy { it.first }
        val merged = mutableListOf<IntRange>()
        for (range in sorted) {
            val last = merged.lastOrNull()
            if (last != null && range.first <= last.last + 1) {
                val newLast = maxOf(last.last, range.last)
                merged[merged.size - 1] = last.first..newLast
            } else {
                merged.add(range)
            }
        }
        return merged
    }

    /** 兜底摘要的取词窗口（iOS 用 ±18）。 */
    const val WINDOW: Int = 18
    const val ELLIPSIS: String = "…"
    const val HIGHLIGHT_OPEN: String = "<hl>"
    const val HIGHLIGHT_CLOSE: String = "</hl>"

    /** FTS snippet 的参数（iOS：`snippet(diary_fts, 1, '<hl>', '</hl>', '…', 24)`）。 */
    const val SNIPPET_TOKENS: Int = 24
}
