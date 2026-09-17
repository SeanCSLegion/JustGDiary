package com.cov.justdiary.ui.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.data.SearchFilterItem
import com.cov.justdiary.data.SearchFilters
import com.cov.justdiary.data.SearchUiState
import com.cov.justdiary.data.TimeRangeKind
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.LocFilter
import com.cov.justdiary.model.LocOption
import com.cov.justdiary.service.Log
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 搜索页状态。对应 iOS `ViewModels/SearchViewModel.swift`。
 *
 * 三个与 iOS 一致的要点：
 *
 * 1. **输入框有 250ms 防抖**（`onKeywordChanged`），而"提交成胶囊"是立即查；
 * 2. **竞态用自增 id 挡掉**：慢的那次查询回来时若 id 已经变了就丢弃结果
 *    （iOS 的 `currentSearchId`）。这里换成 `Job` 取消，语义相同；
 * 3. **没有条件时不查库**，直接给空结果，`total` 保持 -1（界面右上角因此不显示计数）。
 */
class SearchViewModel(private val container: AppContainer) : ViewModel() {

    data class State(
        val ui: SearchUiState = SearchUiState(),
        val filterItems: List<SearchFilterItem> = emptyList(),
        /** 地点筛选面板的候选（国家 + 一级地区）。 */
        val locOptions: List<LocOption> = emptyList(),
        val noLocCount: Int = 0,
        val locSearch: String = "",
        val locLoading: Boolean = false,
        /** 索引还没建好（首屏可能出现一次）。 */
        val indexing: Boolean = false,
    ) {
        val filteredLocOptions: List<LocOption>
            get() = if (locSearch.isEmpty()) {
                locOptions
            } else {
                val kw = locSearch.lowercase()
                locOptions.filter {
                    it.country.lowercase().contains(kw) || it.region1.lowercase().contains(kw)
                }
            }
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var searchJob: Job? = null

    /** 输入框变化 → 250ms 防抖后重查。 */
    fun onKeywordChange(value: String) {
        apply(_state.value.ui.copy(keyword = value))
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            runSearch(reset = true)
        }
    }

    /** 回车 / 点搜索 → 变成关键词胶囊。 */
    fun commitKeyword() {
        val term = _state.value.ui.keyword.trim()
        if (term.isEmpty()) return
        apply(SearchFilters.commitKeyword(_state.value.ui, term))
        runSearchNow(reset = true)
    }

    fun removeKeyword(word: String) {
        apply(SearchFilters.removeKeyword(_state.value.ui, word))
        runSearchNow(reset = true)
    }

    fun clearKeywordInput() {
        apply(_state.value.ui.copy(keyword = ""))
        runSearchNow(reset = true)
    }

    fun setTimeKind(kind: TimeRangeKind) {
        apply(_state.value.ui.copy(timeKind = kind, customRange = if (kind == TimeRangeKind.Custom) _state.value.ui.customRange else null))
        runSearchNow(reset = true)
    }

    fun applyCustomRange(from: String, to: String) {
        apply(_state.value.ui.copy(timeKind = TimeRangeKind.Custom, customRange = com.cov.justdiary.data.DayKeyRange(from, to)))
        runSearchNow(reset = true)
    }

    fun clearTimeFilter() {
        apply(_state.value.ui.copy(timeKind = TimeRangeKind.All, customRange = null))
        runSearchNow(reset = true)
    }

    fun setNoLocation(noLoc: Boolean) {
        apply(_state.value.ui.copy(locFilter = _state.value.ui.locFilter.copy(noLoc = noLoc, country = "", region1 = "")))
        runSearchNow(reset = true)
    }

    fun selectLocation(country: String, region1: String) {
        apply(_state.value.ui.copy(locFilter = LocFilter(country = country, region1 = region1, noLoc = false)))
        runSearchNow(reset = true)
    }

    fun resetLocationFilter() {
        apply(_state.value.ui.copy(locFilter = LocFilter()))
        runSearchNow(reset = true)
    }

    fun clearAll() {
        apply(SearchFilters.clearAll(_state.value.ui))
        runSearchNow(reset = true)
    }

    /** 点掉"生效条件"里的某一类（关键词 / 时间 / 地点）。 */
    fun clearFilter(kind: SearchFilterItem.Kind) {
        val ui = _state.value.ui
        apply(
            when (kind) {
                SearchFilterItem.Kind.Keyword -> ui.copy(keyword = "", keywords = emptyList())
                SearchFilterItem.Kind.Time -> ui.copy(timeKind = TimeRangeKind.All, customRange = null)
                SearchFilterItem.Kind.Location -> ui.copy(locFilter = LocFilter())
            },
        )
        runSearchNow(reset = true)
    }

    fun loadMore() {
        if (_state.value.ui.loading || !_state.value.ui.hasMore) return
        runSearchNow(reset = false)
    }

    fun setLocSearch(value: String) {
        _state.value = _state.value.copy(locSearch = value)
    }

    /** 打开地点筛选面板时拉候选（关键词为空 = 全部候选）。 */
    fun loadLocationOptions(keyword: String = "") {
        _state.value = _state.value.copy(locLoading = true)
        viewModelScope.launch {
            val range = currentRange()
            val result = runCatching {
                container.repository.getLocationOptions(keyword, range.from, range.to)
            }.onFailure { Log.db.w("读取地点候选失败：${it.message}") }.getOrNull()
            _state.value = _state.value.copy(
                locOptions = result?.first ?: emptyList(),
                noLocCount = result?.second ?: 0,
                locLoading = false,
            )
        }
    }

    /** 刷新（周起始/语言变了以后重算时间范围标签）。 */
    fun refresh() {
        apply(_state.value.ui)
        if (_state.value.ui.hasAnyCondition) runSearchNow(reset = true)
    }

    // ------------------------------------------------------------------ 内部

    private fun apply(ui: SearchUiState) {
        val weekStart = container.settings.load().weekStart
        val today = LocalDate.now()
        _state.value = _state.value.copy(
            ui = ui,
            filterItems = SearchFilters.activeFilterItems(ui, today, weekStart) { range ->
                com.cov.justdiary.service.DateFormats
                    .forContext(container.appContext, container.settings.load().appLanguage)
                    .dateRange(
                        DateUtil.parseDayKey(range.from) ?: today,
                        DateUtil.parseDayKey(range.to) ?: today,
                    )
            },
        )
    }

    private fun runSearchNow(reset: Boolean) {
        searchJob?.cancel()
        searchJob = viewModelScope.launch { runSearch(reset) }
    }

    private fun currentRange(): com.cov.justdiary.data.DayKeyRange =
        SearchFilters.dayKeyRange(
            kind = _state.value.ui.timeKind,
            today = LocalDate.now(),
            weekStart = container.settings.load().weekStart,
            customFrom = _state.value.ui.customRange?.from,
            customTo = _state.value.ui.customRange?.to,
        )

    private suspend fun runSearch(reset: Boolean) {
        val ui = _state.value.ui
        val query = SearchFilters.effectiveKeyword(ui)
        val hasFilters = ui.timeKind != TimeRangeKind.All ||
            ui.locFilter.country.isNotEmpty() ||
            ui.locFilter.noLoc

        if (query.isEmpty() && !hasFilters) {
            _state.value = _state.value.copy(ui = ui.copy(items = emptyList(), hasMore = false, total = -1, loading = false))
            return
        }

        _state.value = _state.value.copy(
            ui = ui.copy(loading = true),
            indexing = !container.repository.searchIndexReady(),
        )
        runCatching { container.repository.ensureSearchIndex() }
            .onFailure { Log.db.w("搜索索引准备失败：${it.message}") }

        val range = currentRange()
        val offset = if (reset) 0 else ui.offset
        val page = runCatching {
            container.repository.search(query, range.from, range.to, ui.locFilter, offset)
        }.onFailure { Log.db.w("搜索失败：${it.message}") }.getOrNull()

        if (page == null) {
            _state.value = _state.value.copy(ui = _state.value.ui.copy(loading = false), indexing = false)
            return
        }
        // 结果落到最新状态上（期间用户可能又改了关键词，`runSearchNow` 会取消这个协程）
        val next = if (reset) {
            SearchFilters.replacePage(_state.value.ui, page)
        } else {
            SearchFilters.appendPage(_state.value.ui, page)
        }
        _state.value = _state.value.copy(ui = next, indexing = false)
        apply(next)
    }

    companion object {
        /** 与 iOS 一致：输入防抖 250ms。 */
        const val DEBOUNCE_MS = 250L

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(container) }
        }
    }
}
