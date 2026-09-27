package com.cov.justdiary.ui.home

import android.os.Build
import android.view.Choreographer
import com.cov.justdiary.service.Log

/**
 * **临时**滚动性能探针（`-morph-log` 打开时生效，用完即删）。
 *
 * ## 为什么要用 Choreographer 而不是 Compose 的 `withFrameNanos`
 *
 * 第一版挂在 `FlowScrollIndicator` 的 `LaunchedEffect(scroll.dragging)` 里 ——
 * 一次日志都没打出来：那个 composable 在拖动过程中会被重组/卸载，协程随之被取消。
 * 改成 `Choreographer.postFrameCallback` 自驱：由 [start] 启动，自己续帧、自己到点汇报，
 * 与组合树的生命周期无关。
 *
 * ## 它回答什么
 *
 * 真机 `dumpsys gfxinfo` 的帧分布（1.2s 慢拖，61 帧）是中位 6ms + 若干
 * 121/129/200ms 的孤立长帧。长帧里到底在干什么？探针把**每帧**的
 * 「帧间隔 / 我们自己画了几块共多少 ms / 文本测量未命中次数 / 组合了几块」记下来一起汇报。
 *
 * 2026-09-27 就是靠它定位到"跨月顿一下"的真因：一次挪挂载窗口把**整窗 9 块**都重组重录
 * （一帧 91~157ms），修掉循环形状（见 `MonthBlockWindow`）之后同样的手势变成 0 个长帧。
 */
object ScrollProf {

    private const val WINDOW_MS = 2_500L
    private const val MAX = 600

    private val gaps = LongArray(MAX)
    private var n = 0

    private var running = false
    private var startedAt = 0L
    private var lastNs = 0L

    private val callback = Choreographer.FrameCallback { now ->
        onFrame(now)
    }

    /** 开始监控（重复调用会被忽略；[com.cov.justdiary.app.DebugFlags.morphLog] 关时直接返回）。 */
    fun start() {
        if (!com.cov.justdiary.app.DebugFlags.morphLog || running) return
        running = true
        n = 0
        lastNs = 0
        startedAt = System.nanoTime()
        post()
    }

    private fun post() {
        if (Build.VERSION.SDK_INT >= 16) {
            Choreographer.getInstance().postFrameCallback(callback)
        }
    }

    private fun onFrame(now: Long) {
        if (!running) return
        val gapMs = if (lastNs != 0L) (now - lastNs) / 1_000_000 else 0L
        if (lastNs != 0L && n < MAX) {
            gaps[n] = (now - lastNs) / 1_000_000
            n++
        }
        // 临时探针：把"慢帧里我们自己画了多少"直接打出来（块绘制 = 我们 Canvas lambda 的耗时）
        if (gapMs >= 20 || drawCount >= 2 || composed > 1) {
            Log.app.i(
                "帧计时：间隔 %d ms；块绘制 %d 次共 %.1f ms（%s）；文本测量未命中 %d 次；组合 %d 次"
                    .format(
                        gapMs, drawCount, drawNs / 1e6,
                        drawDetail.joinToString(" "),
                        layoutsMisses - drawMissBase,
                        composed,
                    ),
            )
        }
        lastComposeMs = composeNs / 1_000_000
        composed = 0
        drawNs = 0
        drawCount = 0
        drawDetail.clear()
        drawMissBase = layoutsMisses
        composeNs = 0
        lastNs = now
        if ((System.nanoTime() - startedAt) / 1_000_000 < WINDOW_MS) {
            post()
        } else {
            running = false
            report()
        }
    }

    private var composeNs = 0L
    private var lastComposeMs = 0L

    // ---- 临时探针：块 Canvas 绘制耗时 ----
    private var drawNs = 0L
    private var drawCount = 0
    private val drawDetail = ArrayList<String>()
    private var layoutsMisses = 0
    private var drawMissBase = 0
    private var composed = 0

    /** 一次块组合（临时探针：数每帧重组了几块）。 */
    fun blockComposed(key: Int) {
        if (running) composed++
    }

    /** 一次块 Canvas 绘制（[MorphTextLayouts.layout] 未命中次数由调用方累计）。 */
    fun blockDraw(key: Int, ns: Long, misses: Int) {
        if (!running) return
        drawNs += ns
        drawCount++
        layoutsMisses += misses
        if (ns > 3_000_000) drawDetail.add("%d=%.1fms".format(key, ns / 1e6))
    }

    /** 一次块组合（不含布局）的耗时。 */
    fun composeDone(ns: Long) {
        if (running && ns > 0) composeNs += ns
    }

    fun report() {
        if (n == 0) {
            Log.app.i("滚动探针：这一段没有采到帧")
            return
        }
        val sorted = LongArray(n) { gaps[it] }.sortedArray()
        val q = { p: Double -> sorted[((n - 1) * p).toInt()] }
        Log.app.i(
            "滚动探针：%d 帧 / %d ms；中位 %d ms，p90 %d，p99 %d，最长 %d"
                .format(n, WINDOW_MS, q(0.5), q(0.9), q(0.99), sorted.last()),
        )
        val longFrames = (0 until n).filter { gaps[it] > 30 }
        Log.app.i(
            "滚动探针：>30ms 长帧 %d 个 → %s"
                .format(longFrames.size, longFrames.take(8).joinToString(" ") { "${gaps[it]}ms" }),
        )
        val worst = (0 until n).maxByOrNull { gaps[it] } ?: 0
        Log.app.i(
            "滚动探针：最长帧 #%d（%d ms）—— 组合段 %d ms"
                .format(worst + 1, gaps[worst], lastComposeMs),
        )
        Log.app.i("滚动探针：文本测量累计未命中 %d 次".format(layoutsMisses))
    }
}
