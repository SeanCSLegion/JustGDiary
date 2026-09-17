package com.cov.justdiary.data

import com.cov.justdiary.model.FootprintRow

/** 年份筛选值：`all` 表示不筛（与 iOS 的 `"all"` 一致）。 */
const val YEAR_FILTER_ALL: String = "all"

/** `day_key` 的闭区间（字符串比较即时间序）。 */
data class DayKeyRange(val from: String, val to: String) {
    operator fun contains(dayKey: String): Boolean = dayKey >= from && dayKey <= to
}

/** 足迹页的完整视图状态 —— 由 [FootprintViewStateBuilder] 一次算出，页面只负责渲染。 */
data class FootprintViewState(
    val stats: FootprintStats,
    val nodes: List<FootprintNode>,
    val yearly: List<FootprintYearStat>,
    /** 当前筛选下的已定位片段数（对应 iOS 的"已标注 N 处"）。 */
    val locatedCount: Int,
    /** **不受筛选影响**的未记录天数（对应 iOS 的"未记录 M 篇"）。 */
    val unlocatedCount: Int,
    /** 年份胶囊的候选项（来自未筛选的已定位记录，倒序）。 */
    val yearOptions: List<String>,
)

/**
 * 足迹页的数据装配。移植自 iOS `ViewModels/FootprintViewModel.swift:66-95`。
 *
 * 一个容易做错的细节：**"未记录"的计数不受筛选影响** —— iOS 的注释明确说明了这一点
 * （筛选的是"已标注"的视图，而未记录是另一类事实）。年份候选同样来自未筛选的记录，
 * 否则选中某一年后其它年份就会从胶囊里消失。
 */
object FootprintViewStateBuilder {

    fun build(
        rows: List<FootprintRow>,
        yearFilter: String = YEAR_FILTER_ALL,
        timeRange: DayKeyRange? = null,
        order: PlaceOrder = FootprintAggregator.DefaultPlaceOrder,
    ): FootprintViewState {
        val located = rows.filter(FootprintAggregator::hasPlace)

        var filtered = located
        if (yearFilter != YEAR_FILTER_ALL) {
            filtered = filtered.filter { it.dayKey.startsWith(yearFilter) }
        }
        if (timeRange != null) {
            filtered = filtered.filter { it.dayKey in timeRange }
        }

        val yearOptions = located
            .mapNotNull { row -> row.dayKey.takeIf { it.length >= 4 }?.substring(0, 4) }
            .distinct()
            .sortedDescending()

        return FootprintViewState(
            stats = FootprintAggregator.stats(filtered),
            nodes = FootprintAggregator.buildTree(filtered, order),
            yearly = FootprintAggregator.buildYearly(filtered),
            locatedCount = filtered.size,
            unlocatedCount = rows.size - located.size,
            yearOptions = yearOptions,
        )
    }
}
