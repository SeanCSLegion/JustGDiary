package com.cov.justdiary.data

import com.cov.justdiary.model.FootprintRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 足迹聚合测试。
 *
 * 覆盖：三级路径的塌缩规则、`\u0001` 去重统计、树的计数与排序、年度趋势，
 * 以及"未记录计数不受筛选影响"这条容易做错的规则。
 */
class FootprintAggregatorTest {

    private fun row(
        id: Long,
        dayKey: String,
        country: String = "",
        region1: String = "",
        region2: String = "",
        region3: String = "",
        locText: String = "",
    ) = FootprintRow(
        id = id,
        dayKey = dayKey,
        startTimeUtc = 0L,
        latitude = 0.0,
        longitude = 0.0,
        locText = locText,
        locQuality = "precise",
        locPrecision = "exact",
        country = country,
        countryCode = "CN",
        region1 = region1,
        region2 = region2,
        region3 = region3,
        summary = "",
    )

    // ------------------------------------------------------------------ 基础判定

    @Test
    fun `a row counts as located when it carries any place information`() {
        assertTrue(FootprintAggregator.hasPlace(row(1, "2026-09-15", country = "中国")))
        assertTrue(FootprintAggregator.hasPlace(row(1, "2026-09-15", region3 = "南山区")))
        assertTrue(FootprintAggregator.hasPlace(row(1, "2026-09-15", locText = "深圳湾")))
        // 只有坐标不算（足迹不需要坐标）
        assertFalse(FootprintAggregator.hasPlace(row(1, "2026-09-15")))
    }

    @Test
    fun `path keeps only the non empty levels and never includes region3`() {
        assertEquals(
            listOf("中国", "广东省", "深圳市"),
            FootprintAggregator.path(row(1, "2026-09-15", "中国", "广东省", "深圳市", "南山区")),
        )
        // 直辖市：没有省级数据时自然塌缩
        assertEquals(
            listOf("中国", "北京市", "北京市"),
            FootprintAggregator.path(row(1, "2026-09-15", country = "中国", region1 = "北京市", region2 = "北京市")),
        )
        // 只有国家
        assertEquals(listOf("日本"), FootprintAggregator.path(row(1, "2026-09-15", country = "日本")))
        // 三者都空但 locText 非空 → 用 locText 当一级
        assertEquals(listOf("深圳湾"), FootprintAggregator.path(row(1, "2026-09-15", locText = "深圳湾")))
        // region3 不进路径
        assertEquals(listOf("中国"), FootprintAggregator.path(row(1, "2026-09-15", country = "中国", region3 = "南山区")))
        assertTrue(FootprintAggregator.path(row(1, "2026-09-15")).isEmpty())
    }

    // ------------------------------------------------------------------ 5 格统计

    @Test
    fun `stats deduplicate with the separator and count only located blocks`() {
        val rows = listOf(
            row(1, "2026-09-15", "中国", "广东省", "深圳市", "南山区"),
            row(2, "2026-09-15", "中国", "广东省", "深圳市", "福田区"),
            row(3, "2026-09-16", "中国", "广东省", "广州市"),
            row(4, "2026-09-17", "日本", "東京都", "新宿区"),
            row(5, "2026-09-18"), // 无地点，不计入
        )
        val stats = FootprintAggregator.stats(rows)
        assertEquals(setOf("中国", "日本"), stats.countries)
        // 省：中国/广东省、日本/東京都
        assertEquals(2, stats.provinces.size)
        // 市：中国/广东省/深圳市、中国/广东省/广州市、日本/東京都/新宿区
        assertEquals(3, stats.cities.size)
        assertEquals(4, stats.blocks)
        assertEquals(3, stats.days.size)
    }

    @Test
    fun `the same city name under different provinces is not merged`() {
        val rows = listOf(
            row(1, "2026-09-15", "中国", "广东省", "深圳市"),
            row(2, "2026-09-15", "中国", "福建省", "深圳市"),
        )
        assertEquals(2, FootprintAggregator.stats(rows).cities.size)
    }

    // ------------------------------------------------------------------ 三级树

    @Test
    fun `tree counts every row that passes through a node`() {
        val rows = listOf(
            row(1, "2026-09-15", "中国", "广东省", "深圳市"),
            row(2, "2026-09-16", "中国", "广东省", "深圳市"),
            row(3, "2026-09-17", "中国", "广东省", "广州市"),
            row(4, "2026-09-18", "日本", "東京都", "新宿区"),
        )
        val tree = FootprintAggregator.buildTree(rows)

        // 根层按 count 降序：中国 3 > 日本 1
        assertEquals(listOf("中国", "日本"), tree.map { it.name })
        assertEquals(3, tree[0].count)
        assertEquals(1, tree[1].count)

        val china = tree[0]
        assertEquals("中国", china.id)
        assertEquals(1, china.children.size)
        val guangdong = china.children.single()
        assertEquals("中国\u0001广东省", guangdong.id)
        assertEquals(3, guangdong.count)
        // 市层按 count 降序：深圳市 2 > 广州市 1
        assertEquals(listOf("深圳市", "广州市"), guangdong.children.map { it.name })
        assertEquals(listOf(2, 1), guangdong.children.map { it.count })
        assertTrue(guangdong.children.all { it.isLeaf })
    }

    @Test
    fun `a location text only row becomes a top level node`() {
        val tree = FootprintAggregator.buildTree(listOf(row(1, "2026-09-15", locText = "深圳湾")))
        assertEquals(1, tree.size)
        assertEquals("深圳湾", tree.single().name)
        assertTrue(tree.single().isLeaf)
    }

    @Test
    fun `rows without place information never appear in the tree`() {
        val tree = FootprintAggregator.buildTree(listOf(row(1, "2026-09-15"), row(2, "2026-09-16", country = "中国")))
        assertEquals(1, tree.size)
        assertEquals("中国", tree.single().name)
        assertEquals(1, tree.single().count)
    }

    @Test
    fun `equal counts are ordered by the injected place order`() {
        val rows = listOf(
            row(1, "2026-09-15", country = "乙国"),
            row(2, "2026-09-16", country = "甲国"),
        )
        // 用"反向字典序"当排序策略，验证它确实被用上了
        val reversed = PlaceOrder { a, b -> b.compareTo(a) }
        val tree = FootprintAggregator.buildTree(rows, reversed)
        assertEquals(listOf("甲国", "乙国"), tree.map { it.name })
    }

    // ------------------------------------------------------------------ 年度趋势

    @Test
    fun `yearly stats are ascending with distinct days and places`() {
        val rows = listOf(
            row(1, "2025-03-15", "中国", "广东省", "深圳市"),
            row(2, "2025-03-15", "中国", "广东省", "深圳市"), // 同一天同一地点
            row(3, "2025-06-08", "中国", "浙江省", "杭州市"),
            row(4, "2026-01-05", "法国", "法兰西岛", "巴黎"),
            row(5, "2024-05-12"), // 无地点，不计
        )
        val yearly = FootprintAggregator.buildYearly(rows)
        assertEquals(listOf("2025", "2026"), yearly.map { it.year })
        assertEquals(2, yearly[0].days)
        assertEquals(2, yearly[0].places)
        assertEquals(1, yearly[1].days)
        assertEquals(1, yearly[1].places)
    }

    @Test
    fun `yearly skips keys that are too short to hold a year`() {
        val yearly = FootprintAggregator.buildYearly(listOf(row(1, "20", country = "中国")))
        assertTrue(yearly.isEmpty())
    }

    // ------------------------------------------------------------------ 自然序

    @Test
    fun `natural order puts ch2 before ch10`() {
        val names = listOf("第10章", "第2章", "第1章")
        val sorted = names.sortedWith { a, b -> FootprintAggregator.NaturalPlaceOrder.compare(a, b) }
        assertEquals(listOf("第1章", "第2章", "第10章"), sorted)
    }

    @Test
    fun `natural order falls back to collation for non numeric text`() {
        val names = listOf("b", "a", "c")
        val sorted = names.sortedWith { a, b -> FootprintAggregator.NaturalPlaceOrder.compare(a, b) }
        assertEquals(listOf("a", "b", "c"), sorted)
    }

    // ------------------------------------------------------------------ 视图状态

    @Test
    fun `year filter narrows the aggregation but not the unlocated count`() {
        val rows = listOf(
            row(1, "2025-03-15", "中国", "广东省", "深圳市"),
            row(2, "2026-01-05", "法国", "法兰西岛", "巴黎"),
            row(3, "2026-01-06"), // 无地点
        )
        val all = FootprintViewStateBuilder.build(rows)
        assertEquals(2, all.locatedCount)
        assertEquals(1, all.unlocatedCount)
        assertEquals(listOf("2026", "2025"), all.yearOptions)

        val only2025 = FootprintViewStateBuilder.build(rows, yearFilter = "2025")
        assertEquals(1, only2025.locatedCount)
        assertEquals("未记录的计数不受筛选影响", 1, only2025.unlocatedCount)
        assertEquals(setOf("中国"), only2025.stats.countries)
        // 年份候选也不受筛选影响，否则选中一年后其它年份会消失
        assertEquals(listOf("2026", "2025"), only2025.yearOptions)
    }

    @Test
    fun `time range filter is inclusive on both ends`() {
        val rows = listOf(
            row(1, "2025-12-31", country = "中国"),
            row(2, "2026-01-01", country = "日本"),
            row(3, "2026-06-30", country = "法国"),
            row(4, "2026-07-01", country = "美国"),
        )
        val state = FootprintViewStateBuilder.build(
            rows,
            timeRange = DayKeyRange("2026-01-01", "2026-06-30"),
        )
        assertEquals(2, state.locatedCount)
        assertEquals(setOf("日本", "法国"), state.stats.countries)
    }

    @Test
    fun `an empty result still reports the unlocated count and years`() {
        val rows = listOf(row(1, "2026-01-05", "法国"), row(2, "2026-01-06"))
        val state = FootprintViewStateBuilder.build(rows, yearFilter = "1999")
        assertEquals(0, state.locatedCount)
        assertTrue(state.nodes.isEmpty())
        assertTrue(state.yearly.isEmpty())
        assertTrue(state.stats.countries.isEmpty())
        assertEquals(1, state.unlocatedCount)
        assertEquals(listOf("2026"), state.yearOptions)
    }
}
