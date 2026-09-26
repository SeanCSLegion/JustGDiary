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
    /** 年份胶囊的候选项（倒序；**每一个点下去都一定有内容**）。 */
    val yearOptions: List<String>,
)

/**
 * 足迹页的数据装配。移植自 iOS `ViewModels/FootprintViewModel.swift:66-95`。
 *
 * ## 年份候选项的两条规则（2026-09-26 按用户反馈修正）
 *
 * 1. **只列"实际有内容"的年份** —— 候选来自
 *    `已定位 ∧ 当前时间筛选（不含年份筛选本身）` 的记录。这样每个年份胶囊点下去
 *    都必然有结果，不会出现一排"点了什么都没有"的空年份。
 * 2. **`自定义` 是保底项**，由界面无条件渲染（哪怕一个年份都没有）。
 *
 * iOS 那边候选来自**完全未筛选**的记录：先选自定义区间、再点某个年份时，
 * 两个条件一交可能就是空的 —— 这正是要避免的那种"其实是空的年份按钮"。
 *
 * 另一条仍然保留的规则：**"未记录"的计数不受任何筛选影响**
 * （筛选的是"已标注"的视图，而未记录是另一类事实）。
 */
object FootprintViewStateBuilder {

    fun build(
        rows: List<FootprintRow>,
        yearFilter: String = YEAR_FILTER_ALL,
        timeRange: DayKeyRange? = null,
        order: PlaceOrder = FootprintAggregator.DefaultPlaceOrder,
    ): FootprintViewState {
        val located = rows.filter(FootprintAggregator::hasPlace)

        // 时间筛选先于年份筛选：年份候选项要看得到它，否则会列出空年份
        val inTimeRange = if (timeRange == null) located else located.filter { it.dayKey in timeRange }

        val filtered = if (yearFilter == YEAR_FILTER_ALL) {
            inTimeRange
        } else {
            inTimeRange.filter { it.dayKey.startsWith(yearFilter) }
        }

        val yearOptions = inTimeRange
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
