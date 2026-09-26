package com.cov.justdiary.ui.footprint

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.data.DayKeyRange
import com.cov.justdiary.data.FootprintNode
import com.cov.justdiary.data.FootprintViewState
import com.cov.justdiary.data.FootprintViewStateBuilder
import com.cov.justdiary.data.SearchFilters
import com.cov.justdiary.data.DayKeyInput
import com.cov.justdiary.data.TimeRangeKind
import com.cov.justdiary.data.YEAR_FILTER_ALL
import com.cov.justdiary.model.FootprintRow
import com.cov.justdiary.service.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 足迹页状态。对应 iOS `ViewModels/FootprintViewModel.swift`。
 *
 * 与 iOS 一致的三件事：
 * 1. `allFootprintRows()` 只读一次并缓存 —— 筛选在**内存里**做，不再回库；
 * 2. `unlocated`（没有地点信息的片段数）**不受筛选影响**；
 * 3. 筛选变化后要把已经不存在于树里的展开项剪掉，避免状态无限增长（`pruneExpanded`）。
 */
class FootprintViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val rows: List<FootprintRow> = emptyList(),
        val yearFilter: String = YEAR_FILTER_ALL,
        val timeKind: TimeRangeKind = TimeRangeKind.All,
        val customFrom: String? = null,
        val customTo: String? = null,
        val viewState: FootprintViewState = FootprintViewStateBuilder.build(emptyList()),
        /** 用户展开的节点 id（多选，可同时展开多个分支）。 */
        val expanded: Set<String> = emptySet(),
        val loaded: Boolean = false,
    ) {
        val hasPlaces: Boolean get() = viewState.nodes.isNotEmpty()
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        viewModelScope.launch { load() }
    }

    /** 首次加载；数据只在这时读一次（与 iOS 的 `loaded` 守卫一致）。 */
    fun load() {
        if (_state.value.loaded) {
            reload()
            return
        }
        viewModelScope.launch {
            val rows = runCatching { container.repository.allFootprintRows() }
                .onFailure { Log.db.w("读取足迹数据失败：${it.message}") }
                .getOrDefault(emptyList())
            _state.value = rebuild(_state.value.copy(rows = rows, loaded = true))
        }
    }

    /** 数据可能变了（导入备份 / 编辑过日记）时重新读库。 */
    fun reload() {
        viewModelScope.launch {
            val rows = runCatching { container.repository.allFootprintRows() }
                .onFailure { Log.db.w("读取足迹数据失败：${it.message}") }
                .getOrDefault(_state.value.rows)
            _state.value = rebuild(_state.value.copy(rows = rows))
        }
    }

    fun setYearFilter(year: String) {
        _state.value = rebuild(_state.value.copy(yearFilter = year))
    }

    /** 时间范围四档。选非自定义档时清掉自定义区间（与 iOS 的 `applyTimeKind` 一致）。 */
    fun setTimeKind(kind: TimeRangeKind) {
        _state.value = rebuild(
            _state.value.copy(
                timeKind = kind,
                customFrom = if (kind == TimeRangeKind.Custom) _state.value.customFrom else null,
                customTo = if (kind == TimeRangeKind.Custom) _state.value.customTo else null,
            ),
        )
    }

    fun clearTimeFilter() {
        _state.value = rebuild(
            _state.value.copy(timeKind = TimeRangeKind.All, customFrom = null, customTo = null),
        )
    }

    /** 应用自定义时间范围（两端先归一化，理由同 `SearchViewModel.applyCustomRange`）。 */
    fun applyCustomRange(from: String, to: String) {
        val range = DayKeyInput.normalizeRange(from, to) ?: return
        _state.value = rebuild(
            _state.value.copy(
                timeKind = TimeRangeKind.Custom,
                customFrom = range.first,
                customTo = range.second,
            ),
        )
    }

    /** 展开/收起一个树节点（界面按 id 回调）。 */
    fun toggleExpandNode(id: String) {
        val expanded = _state.value.expanded
        _state.value = _state.value.copy(
            expanded = if (id in expanded) expanded - id else expanded + id,
        )
    }

    /** 展开/收起一个树节点；叶子节点不做处理（与 iOS 一致）。 */
    fun toggle(node: FootprintNode) {
        if (node.isLeaf) return
        toggleExpandNode(node.id)
    }

    private fun rebuild(state: State): State {
        val range: DayKeyRange? = if (state.timeKind == TimeRangeKind.All) {
            null
        } else {
            SearchFilters.dayKeyRange(
                kind = state.timeKind,
                today = LocalDate.now(),
                weekStart = container.settings.load().weekStart,
                customFrom = state.customFrom,
                customTo = state.customTo,
            )
        }
        val viewState = FootprintViewStateBuilder.build(
            rows = state.rows,
            yearFilter = state.yearFilter,
            timeRange = range,
            order = placeOrder,
        )
        return state.copy(viewState = viewState, expanded = pruneExpanded(state.expanded, viewState.nodes))
    }

    /** 剪掉树里已经不存在的展开项，否则反复切筛选会让 `expanded` 无限增长。 */
    private fun pruneExpanded(expanded: Set<String>, nodes: List<FootprintNode>): Set<String> {
        if (expanded.isEmpty()) return expanded
        val alive = mutableSetOf<String>()
        fun walk(list: List<FootprintNode>) {
            for (node in list) {
                alive.add(node.id)
                walk(node.children)
            }
        }
        walk(nodes)
        return expanded intersect alive
    }

    /**
     * 地点名的排序策略。
     *
     * iOS 用 `localizedStandardCompare`（区域感知 **+ 数字自然序**）。Android 的对应物是
     * `android.icu.text.Collator.setNumericCollation(true)`（平台 API 24+），但它**在 JVM 单测里
     * 不可用**，因此：
     *
     * - 纯逻辑层保留可注入的 [com.cov.justdiary.data.PlaceOrder]，单测用
     *   `FootprintAggregator.NaturalPlaceOrder` 断言"数字自然序"这条规则本身；
     * - 真机路径在这里接 ICU 版本（`第2章` 排在 `第10章` 之前）。
     */
    private val placeOrder: com.cov.justdiary.data.PlaceOrder by lazy {
        runCatching {
            val collator = android.icu.text.Collator.getInstance(java.util.Locale.getDefault())
            // 数字自然序：`第2章` 排在 `第10章` 之前（对应 iOS 的 localizedStandardCompare）
            if (collator is android.icu.text.RuleBasedCollator) collator.setNumericCollation(true)
            com.cov.justdiary.data.PlaceOrder { a, b -> collator.compare(a, b) }
        }.getOrDefault(com.cov.justdiary.data.FootprintAggregator.DefaultPlaceOrder)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { FootprintViewModel(container) }
        }
    }
}
