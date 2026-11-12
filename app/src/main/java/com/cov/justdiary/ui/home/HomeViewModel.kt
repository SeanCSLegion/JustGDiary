package com.cov.justdiary.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.app.AppForeground
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.service.Log
import com.cov.justdiary.ui.home.CalendarGrid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 首页状态。对应 iOS `ViewModels/HomeViewModel.swift`。
 *
 * 与 iOS 的差别只有一处：iOS 用 `NotificationCenter` 的 `.diaryVersionChanged` /
 * `.uiTickChanged` 通知来触发重载，Android 侧改成显式的 [refresh] / [refreshSettings]，
 * 由导航层在"从日记页返回"时调用（同一条链，只是不绕通知中心）。
 */
class HomeViewModel(
    private val container: AppContainer,
    private val now: () -> Long = System::currentTimeMillis,
    /** 测试注入点：返回 `null` = 这一天没有日记；生产路径不传，走 repository。 */
    private val injectedDayLoader: (suspend (String) -> List<EditBlock>?)? = null,
) : ViewModel() {

    data class State(
        val today: LocalDate = LocalDate.now(),
        val monthKey: Int = 0,
        val selectedKey: String = "",
        val year: Int = 0,
        /** 有日记的日期（`diary_flag`），范围是这个月所在年 ±1 年。 */
        val flags: Set<String> = emptySet(),
        /** 选中日的片段；`null` 表示这一天还没有日记。 */
        val dayBlocks: List<EditBlock>? = null,
        val weekStart: String = "monday",
        /** `auto_time`：当日面板是否显示片段的开始时间（设置变化后要跟着刷新）。 */
        val autoTime: Boolean = true,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    init {
        val settings = container.settings.load()
        val todayKey = todayKeyFor(settings.dayStartHour)
        val today = DateUtil.parseDayKey(todayKey) ?: LocalDate.now()
        _state.value = State(
            today = today,
            monthKey = CalendarGrid.monthKeyOf(today),
            selectedKey = todayKey,
            year = today.year,
            weekStart = settings.weekStart,
            autoTime = settings.autoTime,
        )
        viewModelScope.launch { reload() }
        // 回到前台时可能已经跨过换日时刻（用户切后台、改时区、正好过 4 点）。
        viewModelScope.launch {
            AppForeground.tick.collect { refreshToday() }
        }
    }

    /** 设置变化（周起始、auto_time 等）后重新读一次。 */
    fun refreshSettings() {
        val settings = container.settings.load()
        _state.value = _state.value.copy(
            weekStart = settings.weekStart,
            autoTime = settings.autoTime,
        )
        refreshToday()
        viewModelScope.launch { reload() }
    }

    /**
     * 重新加载标记与选中日内容。
     *
     * 与 iOS 一致：标记的年份范围取**"今年"和"当前页所在年"的最小值 ±1"**，
     * 这样翻月翻年时标记都是齐的，又不会去扫全库。
     */
    fun refresh() {
        refreshToday()
        viewModelScope.launch { reload() }
    }

    /**
     * P0-5：首页的“今天”必须与日记页一样遵循 `day_start_hour`。
     *
     * 旧实现用 `LocalDate.now()`，在 00:00–03:59（默认换日 4 点）会认为“今天”是日历今天，
     * 而日记页认为“今天”是前一天；从首页点“写日记”可能被 `canEditToday` 拒绝。
     *
     * 如果用户当前选中的正好是**旧的今天**，跟着切到新的今天；如果他在看别的日期，不打扰。
     */
    fun refreshToday() {
        val current = _state.value
        val settings = container.settings.load()
        val newTodayKey = todayKeyFor(settings.dayStartHour)
        val newToday = DateUtil.parseDayKey(newTodayKey) ?: return
        val oldTodayKey = DateUtil.dayKeyOf(current.today)
        if (newTodayKey == oldTodayKey && current.year == newToday.year) return
        val followToday = current.selectedKey == oldTodayKey
        val selectedKey = if (followToday) newTodayKey else current.selectedKey
        _state.value = current.copy(
            today = newToday,
            monthKey = CalendarGrid.monthKeyOf(
                DateUtil.parseDayKey(selectedKey) ?: newToday,
            ),
            selectedKey = selectedKey,
            year = newToday.year,
        )
    }

    private fun todayKeyFor(dayStartHour: Int): String =
        DateUtil.dayKeyForUtc(now(), dayStartHour)

    /** 当前显示的年（界面翻月/翻年时上报）：标记的读取区间跟着它走。 */
    fun setVisibleYear(year: Int) {
        if (year == _state.value.year) return
        _state.value = _state.value.copy(year = year)
        viewModelScope.launch { reload() }
    }

    /**
     * P0-4：选中日加载的代际号。
     *
     * 快速连点两天时，A 的查询可能在 B 之后才回来；`reloadDay` 在每次挂起返回后
     * 都要确认“我仍是当前请求”，否则会把 A 的片段写到 B 的状态上。
     */
    private var dayLoadGeneration: Int = 0

    /** 用户选中某一天（月历里点日期）。 */
    fun select(dayKey: String) {
        if (dayKey == _state.value.selectedKey) return
        val generation = ++dayLoadGeneration
        // 先清空，避免旧日期的片段在新标题下短暂可见。
        _state.value = _state.value.copy(selectedKey = dayKey, dayBlocks = null)
        viewModelScope.launch { reloadDay(generation) }
    }

    private suspend fun reload() {
        val generation = ++dayLoadGeneration
        val current = _state.value
        val thisYear = current.today.year
        val pageYear = current.year.takeIf { it > 0 } ?: thisYear
        val from = "%04d-01-01".format(minOf(thisYear, pageYear) - 1)
        val to = "%04d-12-31".format(maxOf(thisYear, pageYear) + 1)
        val flags = runCatching { container.repository.getDiaryFlagsRange(from, to) }
            .onFailure { Log.db.w("读取日记标记失败：${it.message}") }
            .getOrDefault(emptyList())
            .toSet()
        if (generation != dayLoadGeneration) return
        _state.value = _state.value.copy(flags = flags)
        reloadDay(generation)
    }

    private suspend fun reloadDay(generation: Int) {
        val key = _state.value.selectedKey
        if (injectedDayLoader != null) {
            val blocks = injectedDayLoader.invoke(key)
            if (generation != dayLoadGeneration) return
            if (_state.value.selectedKey != key) return
            _state.value = _state.value.copy(dayBlocks = blocks)
            return
        }
        val diary = runCatching { container.repository.getDiaryByDay(key) }
            .onFailure { Log.db.w("读取 $key 失败：${it.message}") }
            .getOrNull()
        if (generation != dayLoadGeneration) return
        val blocks = if (diary == null) {
            null
        } else {
            runCatching { container.repository.getBlocks(diary.id) }
                .onFailure { Log.db.w("读取片段失败：${it.message}") }
                .getOrDefault(emptyList())
        }
        if (generation != dayLoadGeneration) return
        if (_state.value.selectedKey != key) return
        _state.value = _state.value.copy(dayBlocks = blocks)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
