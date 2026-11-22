package com.cov.justdiary.app

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 冷启动 / 深链带进来的导航请求。对应 iOS 的 `LaunchIntent`
 * （`justdiary://editor?dayKey=YYYY-MM-DD`）。
 *
 * iOS 用 `@Observable` 单例 + `RootView` 在 `task` / `didBecomeActive` 时消费；Android 侧
 * 由 `MainActivity` 解析 `Intent` 后写进来，`RootScreen` 观察并消费，再 `clear()`。
 *
 * 三种入口共用一个对象：
 *  - `justdiary://editor?dayKey=…` → 打开指定日；
 *  - `justdiary://editor`（通知 / 快捷方式"写今天"）→ 打开**今天**（按 `dayStartHour` 现算，
 *    跨午夜也正确）；
 *  - `justdiary://search`（快捷方式"搜索日记"）→ 切到搜索标签。
 *
 * 用 `StateFlow` 而不是直接跳转，是因为**冷启动时 Compose 树还没建好**：
 * 目标和 iOS 一样是"等界面就绪再打开"，而不是抢在 `setContent` 之前。
 */
object LaunchIntent {

    private val _dayKey = MutableStateFlow<String?>(null)

    /** 待打开的 `day_key`；`null` 表示没有待处理的指定日深链。 */
    val dayKey: StateFlow<String?> = _dayKey.asStateFlow()

    private val _openToday = MutableStateFlow(0)

    /** 递增计数：每次"打开今天"请求 +1（计数比布尔更抗重复消费）。 */
    val openToday: StateFlow<Int> = _openToday.asStateFlow()

    private val _search = MutableStateFlow(0)

    /** 递增计数：每次"打开搜索"请求 +1。 */
    val search: StateFlow<Int> = _search.asStateFlow()

    private val _settings = MutableStateFlow(0)
    val settings: StateFlow<Int> = _settings.asStateFlow()

    /**
     * 从 `Intent` 里取出深链目标。
     *
     * 同时接受 `justdiary://editor?dayKey=…` 与显式的
     * `justdiary://editor/YYYY-MM-DD`（后者便于手工测试）。
     */
    fun consume(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_OPEN_SETTINGS, false) == true) _settings.value += 1
        val data = intent?.data ?: return
        if (data.scheme != SCHEME) return
        when (data.host) {
            HOST_EDITOR -> {
                val key = data.getQueryParameter(QUERY_DAY_KEY)
                    ?: data.lastPathSegment?.takeIf { it.matches(DAY_KEY_PATTERN) }
                if (!key.isNullOrEmpty() && key.matches(DAY_KEY_PATTERN)) {
                    _dayKey.value = key
                } else {
                    // 通知 / 快捷方式不带 dayKey：由 RootScreen 按当前设置算"今天"。
                    _openToday.value += 1
                }
            }
            HOST_SEARCH -> _search.value += 1
        }
    }

    fun clear() {
        _dayKey.value = null
    }

    fun clearOpenToday() {
        _openToday.value = 0
    }

    fun clearSearch() {
        _search.value = 0
    }

    fun clearSettings() { _settings.value = 0 }

    const val SCHEME = "justdiary"
    const val HOST_EDITOR = "editor"
    const val HOST_SEARCH = "search"
    const val QUERY_DAY_KEY = "dayKey"
    const val EXTRA_OPEN_SETTINGS = "open_settings"

    private val DAY_KEY_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
}
