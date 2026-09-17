package com.cov.justdiary.app

import android.content.Intent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 冷启动 / 深链带进来的"打开某一天的日记"。对应 iOS 的 `LaunchIntent`
 * （`justdiary://editor?dayKey=YYYY-MM-DD`）。
 *
 * iOS 用 `@Observable` 单例 + `RootView` 在 `task` / `didBecomeActive` 时消费；Android 侧
 * 由 `MainActivity` 解析 `Intent` 后写进来，`RootScreen` 观察并打开日记页，再 `clear()`。
 *
 * 用 `StateFlow` 而不是直接跳转，是因为**冷启动时 Compose 树还没建好**：
 * 目标和 iOS 一样是"等界面就绪再打开"，而不是抢在 `setContent` 之前。
 */
object LaunchIntent {

    private val _dayKey = MutableStateFlow<String?>(null)

    /** 待打开的 `day_key`；`null` 表示没有待处理的深链。 */
    val dayKey: StateFlow<String?> = _dayKey.asStateFlow()

    /**
     * 从 `Intent` 里取出深链目标。
     *
     * 同时接受 `justdiary://editor?dayKey=…` 与显式的
     * `justdiary://editor/YYYY-MM-DD`（后者便于手工测试）。
     */
    fun consume(intent: Intent?) {
        val data = intent?.data ?: return
        if (data.scheme != SCHEME || data.host != HOST) return
        val key = data.getQueryParameter(QUERY_DAY_KEY)
            ?: data.lastPathSegment?.takeIf { it.matches(DAY_KEY_PATTERN) }
        if (key.isNullOrEmpty()) return
        _dayKey.value = key
    }

    fun clear() {
        _dayKey.value = null
    }

    const val SCHEME = "justdiary"
    const val HOST = "editor"
    const val QUERY_DAY_KEY = "dayKey"

    private val DAY_KEY_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
}
