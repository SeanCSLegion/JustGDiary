package com.cov.justdiary.data

import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.LocFilter
import com.cov.justdiary.model.SearchPageResult
import com.cov.justdiary.model.SearchResultItem
import java.time.LocalDate

/** 搜索的时间范围档位（对应 iOS `TimeRangeKind`）。 */
enum class TimeRangeKind { All, ThisWeek, ThisMonth, ThisYear, Custom }

/** 生效条件摘要里的一个条目（对应 iOS `SearchFilterItem`：keyword / time / location 三类）。 */
data class SearchFilterItem(val kind: Kind, val label: String) {
    enum class Kind { Keyword, Time, Location }
}

/**
 * 搜索页的可变状态。
 *
 * @param keyword 输入框里的当前词（250ms 防抖后触发查询）
 * @param keywords 已提交的**关键词胶囊**；多词之间是"与"
 * @param timeKind / @param customRange 时间筛选
 * @param locFilter 地点筛选（国家 + 一级地区，或"未记录地点"）
 */
data class SearchUiState(
    val keyword: String = "",
    val keywords: List<String> = emptyList(),
    val timeKind: TimeRangeKind = TimeRangeKind.All,
    val customRange: DayKeyRange? = null,
    val locFilter: LocFilter = LocFilter(),
    val offset: Int = 0,
    val items: List<SearchResultItem> = emptyList(),
    val hasMore: Boolean = false,
    val total: Int = -1,
    val loading: Boolean = false,
) {
    /** 库还没准备好时 `total == -1`（与 iOS 一致）。 */
    val indexNotReady: Boolean get() = total < 0
    val isEmpty: Boolean get() = items.isEmpty()

    /**
     * 是否至少有一个条件（关键词或时间/地点筛选）。
     *
     * 没有条件时**不查库**（与 iOS 一致）：否则会退化成"列出全部日记"，
     * 而搜索页在无条件时应当显示空态提示而不是所有结果。
     */
    val hasAnyCondition: Boolean
        get() = keywords.any { it.isNotBlank() } ||
            keyword.isNotBlank() ||
            timeKind != TimeRangeKind.All ||
            locFilter.country.isNotEmpty() ||
            locFilter.noLoc
}

/**
 * 搜索条件的纯逻辑：时间范围的换算与"生效条件"的推导。
 *
 * ## 时间范围
 *
 * 与 iOS `SearchViewModel.swift:10-29` 一致：
 * - `all` 用 `0000-01-01 .. 9999-12-31` 撑满，避免在 SQL 里加条件分支；
 * - 本周用设置里的**周起始**（`week_start`）算周一/周日；
 * - 本月 / 今年都到**今天**为止（不是月末/年末）；
 * - 自定义范围会把两端排序后使用（用户可能先点后一个日期）。
 *
 * 全部是纯函数。
 */
object SearchFilters {

    val ALL_RANGE = DayKeyRange("0000-01-01", "9999-12-31")

    fun dayKeyRange(
        kind: TimeRangeKind,
        today: LocalDate,
        weekStart: String,
        customFrom: String? = null,
        customTo: String? = null,
    ): DayKeyRange = when (kind) {
        TimeRangeKind.All -> ALL_RANGE
        TimeRangeKind.ThisWeek -> DayKeyRange(
            from = DateUtil.dayKeyOf(DateUtil.weekFirst(today, weekStart)),
            to = DateUtil.dayKeyOf(today),
        )

        TimeRangeKind.ThisMonth -> DayKeyRange(
            from = DateUtil.dayKeyOf(DateUtil.monthFirst(today)),
            to = DateUtil.dayKeyOf(today),
        )

        TimeRangeKind.ThisYear -> DayKeyRange(
            from = DateUtil.dayKeyOf(LocalDate.of(today.year, 1, 1)),
            to = DateUtil.dayKeyOf(today),
        )

        TimeRangeKind.Custom -> {
            val a = customFrom ?: ALL_RANGE.from
            val b = customTo ?: ALL_RANGE.to
            if (a <= b) DayKeyRange(a, b) else DayKeyRange(b, a)
        }
    }

    /**
     * 实际送给 FTS 的关键词：已提交的胶囊 + 输入框里未提交的词，用空格连接（FTS 侧是"与"）。
     */
    fun effectiveKeyword(state: SearchUiState): String {
        val parts = state.keywords.map { it.trim() }.filter { it.isNotEmpty() }
        val pending = state.keyword.trim()
        return (parts + if (pending.isNotEmpty()) listOf(pending) else emptyList()).joinToString(" ")
    }

    /** 生效条件摘要（用于页面上的一行胶囊）。 */
    fun activeFilterItems(
        state: SearchUiState,
        today: LocalDate,
        weekStart: String,
        rangeLabel: (DayKeyRange) -> String,
    ): List<SearchFilterItem> {
        val items = mutableListOf<SearchFilterItem>()
        state.keywords.forEach { word ->
            if (word.isNotBlank()) items.add(SearchFilterItem(SearchFilterItem.Kind.Keyword, word))
        }
        if (state.timeKind != TimeRangeKind.All) {
            items.add(
                SearchFilterItem(
                    SearchFilterItem.Kind.Time,
                    rangeLabel(dayKeyRange(state.timeKind, today, weekStart, state.customRange?.from, state.customRange?.to)),
                ),
            )
        }
        if (state.locFilter.noLoc) {
            items.add(SearchFilterItem(SearchFilterItem.Kind.Location, NO_LOCATION_LABEL))
        } else if (state.locFilter.country.isNotEmpty()) {
            val label = listOf(state.locFilter.country, state.locFilter.region1)
                .filter { it.isNotEmpty() }
                .joinToString(" · ")
            items.add(SearchFilterItem(SearchFilterItem.Kind.Location, label))
        }
        return items
    }

    /** 提交一个关键词为胶囊（重复词不重复添加）。 */
    fun commitKeyword(state: SearchUiState, word: String = state.keyword): SearchUiState {
        val trimmed = word.trim()
        if (trimmed.isEmpty()) return state.copy(keyword = "")
        return state.copy(
            keyword = "",
            keywords = if (state.keywords.contains(trimmed)) state.keywords else state.keywords + trimmed,
            offset = 0,
        )
    }

    /** 点掉一个关键词胶囊。 */
    fun removeKeyword(state: SearchUiState, word: String): SearchUiState =
        state.copy(keywords = state.keywords.filter { it != word }, offset = 0)

    /** 清除全部条件（关键词、时间、地点一起清）。 */
    fun clearAll(state: SearchUiState): SearchUiState = SearchUiState()

    /** 累积一页结果（"加载更多"用）。 */
    fun appendPage(state: SearchUiState, page: SearchPageResult): SearchUiState = state.copy(
        items = state.items + page.items,
        hasMore = page.hasMore,
        total = page.total,
        offset = state.offset + page.items.size,
        loading = false,
    )

    /** 首屏结果（替换而不是追加）。 */
    fun replacePage(state: SearchUiState, page: SearchPageResult): SearchUiState = state.copy(
        items = page.items,
        hasMore = page.hasMore,
        total = page.total,
        offset = page.items.size,
        loading = false,
    )

    const val NO_LOCATION_LABEL = "未记录地点"
}
