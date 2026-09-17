package com.cov.justdiary.service

import java.text.BreakIterator
import java.util.Locale

/**
 * 全文搜索的 CJK 切分，**逐行移植**自 iOS `JustDiary/Services/FtsSegment.swift`。
 *
 * ## 为什么需要它
 *
 * `diary_fts` 用 FTS5 的 `unicode61` 分词器，而它把**一整串中文当成一个 token**，
 * 于是"深圳湾"只能整串匹配、"深圳"搜不到。iOS 的做法是在**相邻两个 CJK 字符之间插一个空格**，
 * 让每个字成为独立 token，查询侧再把词包成短语（`"深 圳"`），从而实现跨字子串匹配。
 *
 * 写入侧与查询侧**必须调用同一个 [segment]**；[desegment] 只用于展示（去掉注入的空格、
 * 同时保护 `<hl>` 高亮标签），不参与 MATCH。
 *
 * 区间与 iOS 完全一致（只看字符的第一个 code point）：
 * `0x4E00..0x9FFF`（CJK 统一表意）、`0x3400..0x4DBF`（扩展 A）、`0x3000..0x303F`（CJK 符号与标点）。
 *
 * 已知且**刻意保留**的覆盖面缺口（iOS 同样如此，改了会让两端的索引语义分叉）：
 * 日文假名、韩文谚文、CJK 兼容表意（U+F900–FAFF）、扩展 B 及以上（U+20000+）。
 */
object FtsSegment {

    private val CJK_RANGES = arrayOf(
        0x4E00..0x9FFF,
        0x3400..0x4DBF,
        0x3000..0x303F,
    )

    /** 该字符是否算 CJK。参数是"一个字符"（可能由多个码元组成的字素簇）。 */
    fun isCjk(cluster: String): Boolean {
        if (cluster.isEmpty()) return false
        val codePoint = cluster.codePointAt(0)
        return CJK_RANGES.any { codePoint in it }
    }

    /** 在相邻的两个 CJK 字符之间插一个空格；其余字符不动。 */
    fun segment(text: String): String {
        if (text.isEmpty()) return ""
        val clusters = clusters(text)
        val out = StringBuilder(text.length + clusters.size / 4)
        var previous: String? = null
        for (cluster in clusters) {
            if (previous != null && isCjk(previous) && isCjk(cluster)) out.append(' ')
            out.append(cluster)
            previous = cluster
        }
        return out.toString()
    }

    /**
     * [segment] 的逆操作，仅供展示：把"前一个是 CJK、后一个也是 CJK"的空格去掉，
     * 并原样跳过 `<...>` 之间的内容（保护 snippet 产生的 `<hl>` 标签）。
     */
    fun desegment(text: String): String {
        val clusters = clusters(text)
        val out = StringBuilder(clusters.size)
        var lastWasCjk = false
        var i = 0
        while (i < clusters.size) {
            val ch = clusters[i]

            if (ch == " ") {
                var j = i + 1
                var nextIsCjk = false
                while (j < clusters.size) {
                    val c = clusters[j]
                    if (c == "<") {
                        while (j < clusters.size && clusters[j] != ">") j += 1
                        j += 1
                        continue
                    }
                    if (c != " ") {
                        nextIsCjk = isCjk(c)
                        break
                    }
                    j += 1
                }
                if (lastWasCjk && nextIsCjk) {
                    i += 1
                    continue
                }
                out.append(ch)
                lastWasCjk = false
                i += 1
                continue
            }

            out.append(ch)
            if (ch == "<") {
                var end = i
                while (end < clusters.size && clusters[end] != ">") end += 1
                if (end < clusters.size) {
                    for (k in (i + 1)..end) out.append(clusters[k])
                    i = end
                }
            } else {
                lastWasCjk = isCjk(ch)
            }
            i += 1
        }
        return out.toString()
    }

    /**
     * 按**字素簇**切分，对应 Swift 对 `Character` 的遍历。
     *
     * 用 [BreakIterator] 而不是按 `Char` 遍历，是为了让"基字符 + 组合记号"算作一个字符 ——
     * 按 UTF-16 码元遍历会在"字 + 组合记号 + 字"这种序列上得出与 iOS 不同的结果。
     */
    private fun clusters(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val iterator = BreakIterator.getCharacterInstance(Locale.ROOT)
        iterator.setText(text)
        val result = ArrayList<String>(text.length)
        var start = iterator.first()
        var end = iterator.next()
        while (end != BreakIterator.DONE) {
            result.add(text.substring(start, end))
            start = end
            end = iterator.next()
        }
        return result
    }
}
