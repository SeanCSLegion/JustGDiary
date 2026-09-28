package com.cov.justdiary.app

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "回到前台"的信号（进程级，进程内自增的计数器）。
 *
 * 对应 iOS 的 `UIApplication.didBecomeActiveNotification`（`RootView.swift:134-137`、
 * `DiaryPageView` 的 `scenePhase == .active` 分支）。Android 侧**没有**生命周期通知的订阅机制
 * 给 Compose 用（`LocalLifecycleOwner` 只在 Activity 里），所以由
 * [com.cov.justdiary.app.MainActivity.onStart] 自增，页面观察它。
 *
 * ## 为什么日记页需要它
 *
 * 本页有两样东西是**算一次就一直存着**的：`todayKey`（决定"能不能再写一条"与保存时的跨日判定）
 * 和 `settings`（位置/时间显示）。用户把 app 切到后台、去系统设置改完时区，
 * 或者回到前台时**正好跨过了换日时刻**（`dayStartHour`），这两样就都过期了 ——
 * 不刷新的话，保存时的跨日确认会**少问一次**（内容会悄悄落到另一天）。
 */
object AppForeground {

    private val _tick = MutableStateFlow(0)

    /** 每次回到前台 +1。初值 0 表示"还没回来过"。 */
    val tick: StateFlow<Int> = _tick.asStateFlow()

    fun onStart() {
        _tick.value += 1
    }
}
