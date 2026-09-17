package com.cov.justdiary.data

import com.cov.justdiary.model.FootprintRow
import java.text.Collator
import java.util.Locale

/** 足迹树的一个节点：国家 → 省 → 市（缺级时自然塌缩）。 */
data class FootprintNode(
    /** `\u0001` 连接的路径，全局唯一且跨次加载稳定。 */
    val id: String,
    val name: String,
    /** 记录在该节点及其**之下**的片段数。 */
    val count: Int,
    val children: List<FootprintNode>,
) {
    val isLeaf: Boolean get() = children.isEmpty()
}

/** 年度趋势的一项：该年的记录天数与去重地点数。 */
data class FootprintYearStat(val year: String, val days: Int, val places: Int)

/** 足迹的 5 格统计。 */
data class FootprintStats(
    val provinces: Set<String> = emptySet(),
    val cities: Set<String> = emptySet(),
    val countries: Set<String> = emptySet(),
    val blocks: Int = 0,
    val days: Set<String> = emptySet(),
)

/** 名称排序策略（注入以便 JVM 测试；真机用 ICU 的自然序）。 */
fun interface PlaceOrder {
    fun compare(a: String, b: String): Int
}

/**
 * 足迹页的**纯数据聚合**。移植自 iOS `Services/FootprintDataService.swift`（137 行）。
 *
 * ## 关键设计
 *
 * "足迹"是**纯数据聚合**：不依赖坐标、也不需要任何打包的地理边界数据，
 * 因此对任何国家都可用（iOS 侧的注释明确写了这一点，替代了更早的地图实现）。
 *
 * ## 三级路径
 *
 * `path(of:)` 只取非空的 `country` / `region1` / `region2`；**`region3` 不进路径**
 * （它只体现在 `loc_text` 里）。直辖市这类没有省级的数据会自然塌缩（北京市 → 中国/北京市/北京市），
 * 而没有地区数据的国家只有一级节点。三者都空但 `locText` 非空时，用 `locText` 当一级节点。
 *
 * 全部是纯函数，可在 JVM 上断言。
 */
object FootprintAggregator {

    /** 节点 id 的分隔符（与 iOS 一致：`\u0001`）。 */
    const val SEPARATOR: Char = '\u0001'

    /**
     * 一条记录算"有地点"当且仅当它带任何地点信息。
     *
     * 这比早先的地图规则宽松（旧规则还要求可用坐标或同城/同省质心），
     * 因为足迹列表不再需要坐标。
     */
    fun hasPlace(row: FootprintRow): Boolean =
        row.country.isNotEmpty() || row.region1.isNotEmpty() || row.region2.isNotEmpty() ||
            row.region3.isNotEmpty() || row.locText.isNotEmpty()

    /** 非空的地区组件，例如 `["中国", "广东省", "深圳市"]`。 */
    fun path(row: FootprintRow): List<String> {
        val parts = mutableListOf<String>()
        if (row.country.isNotEmpty()) parts.add(row.country)
        if (row.region1.isNotEmpty()) parts.add(row.region1)
        if (row.region2.isNotEmpty()) parts.add(row.region2)
        if (parts.isEmpty() && row.locText.isNotEmpty()) parts.add(row.locText)
        return parts
    }

    /**
     * 5 格统计：省市 / 城市 / 国家 / 片段 / 天数。
     *
     * 省市与城市用 `\u0001` 拼 key 去重（因此"广东省/深圳市"与"福建省/深圳市"不会被合并）。
     */
    fun stats(rows: List<FootprintRow>): FootprintStats {
        val provinces = mutableSetOf<String>()
        val cities = mutableSetOf<String>()
        val countries = mutableSetOf<String>()
        val days = mutableSetOf<String>()
        var blocks = 0
        for (row in rows) {
            if (!hasPlace(row)) continue
            if (row.country.isNotEmpty()) countries.add(row.country)
            if (row.region1.isNotEmpty()) provinces.add(row.country + SEPARATOR + row.region1)
            if (row.region2.isNotEmpty()) {
                cities.add(row.country + SEPARATOR + row.region1 + SEPARATOR + row.region2)
            }
            blocks += 1
            if (row.dayKey.isNotEmpty()) days.add(row.dayKey)
        }
        return FootprintStats(provinces, cities, countries, blocks, days)
    }

    /**
     * 构建三级树。
     *
     * 节点的 `count` 是**经过该节点的行数**（自身 + 后代），因此父节点的计数不小于子节点之和；
     * 排序规则：先按 `count` 降序，再按名称的**区域感知自然序**升序。
     */
    fun buildTree(rows: List<FootprintRow>, order: PlaceOrder = DefaultPlaceOrder): List<FootprintNode> {
        val names = mutableMapOf<String, String>()
        val counts = mutableMapOf<String, Int>()
        val childKeys = mutableMapOf<String, MutableSet<String>>()
        val root = ""

        for (row in rows) {
            val accumulated = mutableListOf<String>()
            var parent = root
            for (part in path(row)) {
                accumulated.add(part)
                val key = accumulated.joinToString(separator = SEPARATOR.toString())
                names[key] = part
                childKeys.getOrPut(parent) { linkedSetOf() }.add(key)
                counts[key] = (counts[key] ?: 0) + 1
                parent = key
            }
        }

        fun orderKeys(keys: Collection<String>): List<String> = keys.sortedWith { a, b ->
            val ca = counts[a] ?: 0
            val cb = counts[b] ?: 0
            if (ca != cb) {
                cb - ca // count 降序
            } else {
                order.compare(names[a] ?: a, names[b] ?: b)
            }
        }

        fun make(key: String): FootprintNode = FootprintNode(
            id = key,
            name = names[key] ?: key,
            count = counts[key] ?: 0,
            children = orderKeys(childKeys[key] ?: emptySet()).map(::make),
        )

        return orderKeys(childKeys[root] ?: emptySet()).map(::make)
    }

    /** 每年的记录天数与去重地点数，按年份**升序**（趋势图从左到右）。 */
    fun buildYearly(rows: List<FootprintRow>): List<FootprintYearStat> {
        data class Acc(val days: MutableSet<String> = mutableSetOf(), val places: MutableSet<String> = mutableSetOf())

        val byYear = mutableMapOf<String, Acc>()
        for (row in rows) {
            if (!hasPlace(row)) continue
            if (row.dayKey.length < 4) continue
            val year = row.dayKey.substring(0, 4)
            val acc = byYear.getOrPut(year) { Acc() }
            if (row.dayKey.isNotEmpty()) acc.days.add(row.dayKey)
            val key = path(row).joinToString(separator = SEPARATOR.toString())
            if (key.isNotEmpty()) acc.places.add(key)
        }
        return byYear.keys.sorted().map { year ->
            val acc = byYear[year] ?: Acc()
            FootprintYearStat(year = year, days = acc.days.size, places = acc.places.size)
        }
    }

    /**
     * 默认名称排序：区域感知。
     *
     * ⚠️ **这是本文件里唯一与平台有关的一处**：iOS 用 `localizedStandardCompare`，
     * 它是区域感知 **+ 数字自然序**（"第2章" 排在 "第10章" 前面）。
     * 这里的 [DefaultPlaceOrder] 用 `java.text.Collator`（JVM 与 Android 都有）做区域感知比较，
     * 但**没有数字自然序**；真机上应换成 `android.icu.text.Collator` 并
     * `setNumericCollation(true)`（平台 API 24+）。地名场景下两者差异极小。
     */
    object DefaultPlaceOrder : PlaceOrder {
        override fun compare(a: String, b: String): Int =
            Collator.getInstance(Locale.getDefault()).compare(a, b)
    }

    /**
     * 数字自然序的排序（真机用 ICU 实现；这里给出一个不依赖平台的等价实现，
     * 因此"数字自然序"这条规则本身也可以在 JVM 上断言）。
     */
    object NaturalPlaceOrder : PlaceOrder {
        private val collator = Collator.getInstance(Locale.getDefault())

        override fun compare(a: String, b: String): Int {
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val ca = a[i]
                val cb = b[j]
                if (ca.isDigit() && cb.isDigit()) {
                    var ei = i
                    while (ei < a.length && a[ei].isDigit()) ei++
                    var ej = j
                    while (ej < b.length && b[ej].isDigit()) ej++
                    val na = a.substring(i, ei).trimStart('0')
                    val nb = b.substring(j, ej).trimStart('0')
                    if (na.length != nb.length) return na.length - nb.length
                    val cmp = na.compareTo(nb)
                    if (cmp != 0) return cmp
                    i = ei
                    j = ej
                } else {
                    val cmp = collator.compare(ca.toString(), cb.toString())
                    if (cmp != 0) return cmp
                    i++
                    j++
                }
            }
            return (a.length - i) - (b.length - j)
        }
    }
}
