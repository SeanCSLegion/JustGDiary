package com.cov.justdiary.ui.home

import com.cov.justdiary.app.DebugFlags

/**
 * **临时的帧率上限门闸**（`--ei frame-gate 30|60`，0 = 不限）。
 *
 * 用户 2026-09-27："作为测试，现在先临时限制一下帧率到 30 或者 60"。
 *
 * 做法：在绘制路径上比对时间戳 —— 距上一帧不足 `1000/fps` 毫秒就**直接返回、这一帧不画**。
 * 内容是跟着滚动偏移推的，跳过一帧只是"晚一帧显示"，不会丢状态。
 *
 * ⚠️ 这是**测试开关**，默认 0（不限）；正式行为不受影响。
 */
internal object FrameGate {

    private var lastNs = 0L

    /** 允许绘制返回 true；被限帧挡下返回 false。 */
    fun allow(): Boolean {
        val fps = DebugFlags.frameGateFps
        if (fps <= 0) return true
        val now = System.nanoTime()
        val minGapNs = 1_000_000_000L / fps
        if (lastNs != 0L && now - lastNs < minGapNs) return false
        lastNs = now
        return true
    }
}
