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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    private var searchJob: Job? = null
    private var locSearchJob: Job? = null

    /** 输入框变化 → 250ms 防抖后重查。 */
    /**
     * 输入框变化。
     *
     * **不触发查询** —— 只有回车（[commitKeyword]）之后那个词才生效
     * （用户 2026-09-26：「未回车的输入还是不要进行搜索吧」）。
     * 原来的 250ms 防抖是"边打边查"：既费电，又会让「没有找到匹配的日记」
     * 在打字过程中反复闪，看起来像结果不稳定。
     *
     * 清空输入框要**重查一次**：如果之前有已提交的关键词，清空输入本身不改变条件，
     * 但如果是"清除按钮 + 无关键词"，界面应当回到引导态。
     */
    fun onKeywordChange(value: String) {
        val previous = _state.value.ui.keyword
        apply(_state.value.ui.copy(keyword = value))
        // 只在"从有到无"时重查一次，让空条件回到引导态；其余交给回车
        if (previous.isNotEmpty() && value.isEmpty() && _state.value.ui.keywords.isEmpty()) {
            runSearchNow(reset = true)
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

    /**
     * 应用自定义时间范围。
     *
     * 两端先经过 [DayKeyInput.normalizeRange] 归一化 —— 界面已经校验过一遍，
     * 这里再兜一次：范围过滤是**字符串比较**，未归一化的 `2026-9-1` 会算出空区间。
     * 归一化失败（理论上不该发生）时什么都不做，宁可保持原状也不要写进一个空区间。
     */
    fun applyCustomRange(from: String, to: String) {
        val range = com.cov.justdiary.data.DayKeyInput.normalizeRange(from, to) ?: return
        apply(
            _state.value.ui.copy(
                timeKind = TimeRangeKind.Custom,
                customRange = com.cov.justdiary.data.DayKeyRange(range.first, range.second),
            ),
        )
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

    /**
     * 地点面板内的搜索词。
     *
     * ⚠️ 必须**真的去查库**。原来这里只把字符串存进状态，界面渲染的却是未过滤的
     * `locOptions`，于是"搜索地点…"输入框完全没反应。也不能在客户端过滤：
     * 客户端只有 Top20，榜外的地点永远搜不到
     * （README §6 缺陷 7）。这里走 `getLocationOptions(keyword, …)` 的 SQL `LIKE`。
     */
    fun setLocSearch(value: String) {
        _state.value = _state.value.copy(locSearch = value)
        locSearchJob?.cancel()
        locSearchJob = viewModelScope.launch {
            delay(DEBOUNCE_MS)
            loadLocationOptionsNow(value)
        }
    }

    /** 打开地点筛选面板时拉候选（关键词为空 = 全部候选）。 */
    fun loadLocationOptions(keyword: String = "") {
        locSearchJob?.cancel()
        locSearchJob = viewModelScope.launch {
            _state.value = _state.value.copy(locLoading = true)
            loadLocationOptionsNow(keyword)
        }
    }

    /**
     * 真正查库。调用方负责把它放进**同一个** `locSearchJob` 里 —— 旧实现里
     * `setLocSearch` 取消的是外层防抖协程，而 `loadLocationOptions` 又新起一个
     * `viewModelScope.launch`，旧查询根本没被取消，晚到的结果会覆盖新结果（P2-6b）。
     */
    private suspend fun loadLocationOptionsNow(keyword: String) {
        _state.value = _state.value.copy(locLoading = true)
        val range = currentRange()
        val result = runCatching {
            container.repository.getLocationOptions(keyword, range.from, range.to)
        }.onFailure { Log.db.w("读取地点候选失败：${it.message}") }.getOrNull()
        // 被取消（用户又敲了一个字）时**不要**写状态：否则会用旧结果覆盖新一轮
        currentCoroutineContext().ensureActive()
        _state.value = _state.value.copy(
            locOptions = result?.first ?: emptyList(),
            noLocCount = result?.second ?: 0,
            locLoading = false,
        )
    }

    /** 失败后重试：清掉错误、按当前条件重查。 */
    fun retry() {
        _state.value = _state.value.copy(ui = _state.value.ui.copy(error = null))
        runSearchNow(reset = true)
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
            filterItems = SearchFilters.activeFilterItems(
                state = ui,
                today = today,
                weekStart = weekStart,
                rangeLabel = { range ->
                    com.cov.justdiary.service.DateFormats
                        .forContext(container.appContext, container.settings.load().appLanguage)
                        .dateRange(
                            DateUtil.parseDayKey(range.from) ?: today,
                            DateUtil.parseDayKey(range.to) ?: today,
                        )
                },
                // 文案跟随应用内语言（`appContext.getString` 在 setApplicationLocales 之后按新语言解析）
                noLocLabel = container.appContext.getString(com.cov.justdiary.R.string.search_loc_no_loc),
            ),
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
            // 无条件不查库：给「零结果 + 索引已就绪」，界面据此显示**空态**而不是转圈。
            _state.value = _state.value.copy(
                ui = ui.copy(
                    items = emptyList(), hasMore = false, total = 0,
                    loading = false, indexing = false, error = null,
                ),
                indexing = false,
            )
            return
        }

        // 只有**真的**要重建索引时才显示"正在准备搜索索引…"
        val needsIndex = !container.repository.searchIndexReady()
        _state.value = _state.value.copy(
            ui = ui.copy(loading = true, indexing = needsIndex, error = null),
            indexing = needsIndex,
        )
        if (needsIndex) {
            runCatching { container.repository.ensureSearchIndex() }
                .onFailure { Log.db.w("搜索索引准备失败：${it.message}") }
        }

        val range = currentRange()
        val offset = if (reset) 0 else ui.offset
        val result = runCatching {
            container.repository.search(query, range.from, range.to, ui.locFilter, offset)
        }.onFailure { throwable ->
            // ⚠️ `runCatching` 会把 `CancellationException` 也吞掉 ——
            // 那是我方主动取消（用户又敲了一个字），**不是**查询失败，
            // 更不该写任何状态：新一轮已经开始了。
            if (throwable is CancellationException) throw throwable
            Log.db.w("搜索失败：${throwable.message}")
        }

        val page = result.getOrNull()
        if (page == null) {
            _state.value = _state.value.copy(
                ui = _state.value.ui.copy(
                    loading = false,
                    indexing = false,
                    error = result.exceptionOrNull()?.message ?: DEFAULT_ERROR,
                ),
                indexing = false,
            )
            return
        }
        // 结果落到最新状态上（期间用户可能又改了关键词，`runSearchNow` 会取消这个协程）
        val next = if (reset) {
            SearchFilters.replacePage(_state.value.ui, page)
        } else {
            SearchFilters.appendPage(_state.value.ui, page)
        }
        // 成功：清掉上一次的错误
        _state.value = _state.value.copy(ui = next.copy(indexing = false, error = null), indexing = false)
        apply(next)
    }

    companion object {
        /** 与 iOS 一致：输入防抖 250ms。 */
        const val DEBOUNCE_MS = 250L

        /** 查询失败时异常没有 message 的兜底文案（界面直接展示）。 */
        const val DEFAULT_ERROR = "搜索出错"

        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SearchViewModel(container) }
        }
    }
}
