package com.cov.justdiary.data

import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.LocFilter
import com.cov.justdiary.model.SearchPageResult
import com.cov.justdiary.model.SearchResultItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 搜索条件与分页状态测试。
 *
 * 时间范围的四个档位、关键词胶囊的增删、以及"生效条件摘要"的推导都在这里钉住 ——
 * 这些是搜索页面上最容易与 iOS 出现行为差异的地方。
 */
class SearchFiltersTest {

    private val today = LocalDate.of(2026, 9, 15) // 周二

    private fun item(dayKey: String) = SearchResultItem(
        id = dayKey.hashCode().toLong(),
        dayKey = dayKey,
        summary = "摘要",
        snippet = "片段",
        updatedUtc = 0L,
    )

    // ------------------------------------------------------------------ 时间范围

    @Test
    fun `all range spans everything`() {
        val range = SearchFilters.dayKeyRange(TimeRangeKind.All, today, DateUtil.WEEK_START_MONDAY)
        assertEquals("0000-01-01", range.from)
        assertEquals("9999-12-31", range.to)
    }

    @Test
    fun `this week follows the configured week start and ends today`() {
        val monday = SearchFilters.dayKeyRange(TimeRangeKind.ThisWeek, today, DateUtil.WEEK_START_MONDAY)
        assertEquals("2026-09-14", monday.from)
        assertEquals("2026-09-15", monday.to)

        val sunday = SearchFilters.dayKeyRange(TimeRangeKind.ThisWeek, today, DateUtil.WEEK_START_SUNDAY)
        assertEquals("2026-09-13", sunday.from)
        assertEquals("2026-09-15", sunday.to)
    }

    @Test
    fun `this month and this year end today not at the period end`() {
        val month = SearchFilters.dayKeyRange(TimeRangeKind.ThisMonth, today, DateUtil.WEEK_START_MONDAY)
        assertEquals("2026-09-01", month.from)
        assertEquals("2026-09-15", month.to)

        val year = SearchFilters.dayKeyRange(TimeRangeKind.ThisYear, today, DateUtil.WEEK_START_MONDAY)
        assertEquals("2026-01-01", year.from)
        assertEquals("2026-09-15", year.to)
    }

    @Test
    fun `custom range is sorted so the order of picking does not matter`() {
        val forward = SearchFilters.dayKeyRange(
            TimeRangeKind.Custom, today, DateUtil.WEEK_START_MONDAY,
            customFrom = "2026-01-01", customTo = "2026-06-30",
        )
        val backward = SearchFilters.dayKeyRange(
            TimeRangeKind.Custom, today, DateUtil.WEEK_START_MONDAY,
            customFrom = "2026-06-30", customTo = "2026-01-01",
        )
        assertEquals(DayKeyRange("2026-01-01", "2026-06-30"), forward)
        assertEquals(forward, backward)
    }

    @Test
    fun `range containment is inclusive`() {
        val range = DayKeyRange("2026-01-01", "2026-06-30")
        assertTrue("2026-01-01" in range)
        assertTrue("2026-06-30" in range)
        assertFalse("2025-12-31" in range)
        assertFalse("2026-07-01" in range)
    }

    // ------------------------------------------------------------------ 关键词胶囊

    @Test
    fun `effective keyword joins committed chips and the pending input`() {
        val state = SearchUiState(keyword = "天气", keywords = listOf("深圳", "2026"))
        assertEquals("深圳 2026 天气", SearchFilters.effectiveKeyword(state))
    }

    @Test
    fun `committing a keyword moves it into chips and clears the input`() {
        val committed = SearchFilters.commitKeyword(SearchUiState(keyword = " 深圳 "))
        assertEquals("", committed.keyword)
        assertEquals(listOf("深圳"), committed.keywords)
        assertEquals("深圳", SearchFilters.effectiveKeyword(committed))
    }

    @Test
    fun `committing the same keyword twice does not duplicate it`() {
        val once = SearchFilters.commitKeyword(SearchUiState(keyword = "深圳"))
        val twice = SearchFilters.commitKeyword(once.copy(keyword = "深圳"))
        assertEquals(listOf("深圳"), twice.keywords)
    }

    @Test
    fun `committing an empty keyword is a no-op apart from trimming`() {
        val state = SearchUiState(keyword = "   ", keywords = listOf("深圳"))
        val committed = SearchFilters.commitKeyword(state)
        assertEquals(listOf("深圳"), committed.keywords)
        assertEquals("", committed.keyword)
    }

    @Test
    fun `removing a chip drops exactly that keyword`() {
        val state = SearchUiState(keywords = listOf("深圳", "天气", "2026"))
        val removed = SearchFilters.removeKeyword(state, "天气")
        assertEquals(listOf("深圳", "2026"), removed.keywords)
        assertEquals("深圳 2026", SearchFilters.effectiveKeyword(removed))
    }

    @Test
    fun `clear all resets every condition`() {
        val state = SearchUiState(
            keyword = "在输入",
            keywords = listOf("深圳"),
            timeKind = TimeRangeKind.ThisMonth,
            locFilter = LocFilter(country = "中国", region1 = "广东省"),
        )
        assertEquals(SearchUiState(), SearchFilters.clearAll(state))
    }

    // ------------------------------------------------------------------ 生效条件摘要

    @Test
    fun `active filter items cover keyword time and location`() {
        val state = SearchUiState(
            keywords = listOf("深圳"),
            timeKind = TimeRangeKind.ThisMonth,
            locFilter = LocFilter(country = "中国", region1 = "广东省"),
        )
        val items = SearchFilters.activeFilterItems(state, today, DateUtil.WEEK_START_MONDAY) { "9月" }
        assertEquals(
            listOf(
                SearchFilterItem(SearchFilterItem.Kind.Keyword, "深圳"),
                SearchFilterItem(SearchFilterItem.Kind.Time, "9月"),
                SearchFilterItem(SearchFilterItem.Kind.Location, "中国 · 广东省"),
            ),
            items,
        )
    }

    @Test
    fun `location filter shows only the country when no region is picked`() {
        val state = SearchUiState(locFilter = LocFilter(country = "日本"))
        val items = SearchFilters.activeFilterItems(state, today, DateUtil.WEEK_START_MONDAY) { "9月" }
        assertEquals(
            listOf(SearchFilterItem(SearchFilterItem.Kind.Location, "日本")),
            items,
        )
    }

    @Test
    fun `no location filter shows its own label`() {
        val state = SearchUiState(locFilter = LocFilter(noLoc = true))
        val items = SearchFilters.activeFilterItems(state, today, DateUtil.WEEK_START_MONDAY) { "9月" }
        assertEquals(
            listOf(SearchFilterItem(SearchFilterItem.Kind.Location, SearchFilters.NO_LOCATION_LABEL)),
            items,
        )
    }

    @Test
    fun `no conditions yields an empty summary`() {
        assertTrue(SearchFilters.activeFilterItems(SearchUiState(), today, DateUtil.WEEK_START_MONDAY) { "x" }.isEmpty())
    }

    // ------------------------------------------------------------------ 分页

    @Test
    fun `first page replaces and later pages append`() {
        val first = SearchFilters.replacePage(
            SearchUiState(loading = true),
            SearchPageResult(listOf(item("2026-09-15"), item("2026-09-14")), hasMore = true, total = 5),
        )
        assertEquals(2, first.items.size)
        assertTrue(first.hasMore)
        assertEquals(5, first.total)
        assertEquals(2, first.offset)
        assertFalse(first.loading)

        val second = SearchFilters.appendPage(
            first,
            SearchPageResult(listOf(item("2026-09-13")), hasMore = false, total = 5),
        )
        assertEquals(3, second.items.size)
        assertFalse(second.hasMore)
        assertEquals(3, second.offset)
    }

    @Test
    fun `a fresh first page discards previously loaded pages`() {
        val loaded = SearchUiState(items = listOf(item("2026-09-15")), offset = 1, hasMore = true, total = 9)
        val replaced = SearchFilters.replacePage(
            loaded,
            SearchPageResult(listOf(item("2026-01-01")), hasMore = false, total = 1),
        )
        assertEquals(1, replaced.items.size)
        assertEquals("2026-01-01", replaced.items.single().dayKey)
        assertEquals(1, replaced.offset)
    }

    @Test
    fun `total of minus one means the index is not ready`() {
        val loading = SearchUiState(total = -1)
        assertTrue(loading.indexNotReady)
        val ready = SearchUiState(total = 0)
        assertFalse(ready.indexNotReady)
        assertTrue(ready.isEmpty)
    }
}
