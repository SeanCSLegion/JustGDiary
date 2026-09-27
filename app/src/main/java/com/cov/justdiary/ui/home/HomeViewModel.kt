package com.cov.justdiary.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cov.justdiary.app.AppContainer
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
class HomeViewModel(private val container: AppContainer) : ViewModel() {

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
        val today = LocalDate.now()
        val monthKey = CalendarGrid.monthKeyOf(today)
        _state.value = State(
            today = today,
            monthKey = monthKey,
            selectedKey = DateUtil.dayKeyOf(today),
            year = today.year,
            weekStart = container.settings.load().weekStart,
            autoTime = container.settings.load().autoTime,
        )
        viewModelScope.launch { reload() }
    }

    /** 设置变化（周起始、auto_time 等）后重新读一次。 */
    fun refreshSettings() {
        _state.value = _state.value.copy(
            weekStart = container.settings.load().weekStart,
            autoTime = container.settings.load().autoTime,
        )
        viewModelScope.launch { reload() }
    }

    /**
     * 重新加载标记与选中日内容。
     *
     * 与 iOS 一致：标记的年份范围取**"今年"和"当前页所在年"的最小值 ±1"**，
     * 这样翻月翻年时标记都是齐的，又不会去扫全库。
     */
    fun refresh() {
        viewModelScope.launch { reload() }
    }

    /** 当前显示的年（界面翻月/翻年时上报）：标记的读取区间跟着它走。 */
    fun setVisibleYear(year: Int) {
        if (year == _state.value.year) return
        _state.value = _state.value.copy(year = year)
        viewModelScope.launch { reload() }
    }

    /** 用户选中某一天（月历里点日期）。 */
    fun select(dayKey: String) {
        if (dayKey == _state.value.selectedKey) return
        _state.value = _state.value.copy(selectedKey = dayKey)
        viewModelScope.launch { reloadDay() }
    }

    private suspend fun reload() {
        val current = _state.value
        val thisYear = LocalDate.now().year
        val pageYear = current.year.takeIf { it > 0 } ?: thisYear
        val from = "%04d-01-01".format(minOf(thisYear, pageYear) - 1)
        val to = "%04d-12-31".format(maxOf(thisYear, pageYear) + 1)
        val flags = runCatching { container.repository.getDiaryFlagsRange(from, to) }
            .onFailure { Log.db.w("读取日记标记失败：${it.message}") }
            .getOrDefault(emptyList())
            .toSet()
        _state.value = _state.value.copy(flags = flags)
        reloadDay()
    }

    private suspend fun reloadDay() {
        val key = _state.value.selectedKey
        val diary = runCatching { container.repository.getDiaryByDay(key) }
            .onFailure { Log.db.w("读取 $key 失败：${it.message}") }
            .getOrNull()
        val blocks = if (diary == null) {
            null
        } else {
            runCatching { container.repository.getBlocks(diary.id) }
                .onFailure { Log.db.w("读取片段失败：${it.message}") }
                .getOrDefault(emptyList())
        }
        _state.value = _state.value.copy(dayBlocks = blocks)
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { HomeViewModel(container) }
        }
    }
}
