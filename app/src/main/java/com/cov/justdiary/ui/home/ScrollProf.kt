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
 * 「间隔 / 日期格组合次数 / 文本测量耗时」记下来一起汇报。
 */
object ScrollProf {

    private const val WINDOW_MS = 2_500L
    private const val MAX = 600

    private val gaps = LongArray(MAX)
    private val cells = IntArray(MAX)
    private val texts = LongArray(MAX)
    private var n = 0

    private var running = false
    private var startedAt = 0L
    private var lastNs = 0L
    private var cellCount = 0
    private var textNs = 0L
    private var measureNs = 0L

    private val callback = Choreographer.FrameCallback { now ->
        onFrame(now)
    }

    /** 开始监控（重复调用会被忽略；[com.cov.justdiary.app.DebugFlags.morphLog] 关时直接返回）。 */
    fun start() {
        if (!com.cov.justdiary.app.DebugFlags.morphLog || running) return
        running = true
        n = 0
        blockMounts.clear()
        lastNs = 0
        cellCount = 0
        textNs = 0
        measureNs = 0
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
        if (lastNs != 0L && n < MAX) {
            gaps[n] = (now - lastNs) / 1_000_000
            cells[n] = cellCount
            texts[n] = measureNs / 1_000_000
            n++
        }
        composeNs = 0
        lastComposeMs = composeNs / 1_000_000
        composeNs = 0
        resetMeasure()
        lastNs = now
        cellCount = 0
        textNs = 0
        measureNs = 0
        if ((System.nanoTime() - startedAt) / 1_000_000 < WINDOW_MS) {
            post()
        } else {
            running = false
            report()
        }
    }

    /** 一次日期格组合。 */
    fun cell() {
        if (running) cellCount++
    }

    private var composeNs = 0L
    private var lastComposeMs = 0L

    /** 一次"这一块从组合到放置完成"的耗时（纳秒）。 */
    fun measure(ns: Long) {
        if (running && ns > 0) measureNs += ns
        if (ns > 20_000_000) blockMounts.add(ns / 1_000_000)
    }

    private val blockMounts = ArrayList<Long>()

    /** 一次块组合（不含布局）的耗时。 */
    fun composeDone(ns: Long) {
        if (running && ns > 0) composeNs += ns
    }

    /** 换帧时结算：把上一帧累积的布局耗时挪到上一帧名下。 */
    private fun resetMeasure() {
        measureNs = 0
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
            "滚动探针：>30ms 长帧 %d 个 → [间隔/日期格/布局ms] %s"
                .format(
                    longFrames.size,
                    longFrames.take(6).joinToString(" ") {
                        "${gaps[it]}/${cells[it]}/${texts[it]}"
                    },
                ),
        )
        val totalCells = (0 until n).sumOf { cells[it] }
        Log.app.i(
            "滚动探针：整段组合日期格 %d 次（平均 %.1f/帧，最长帧里 %d 次）"
                .format(totalCells, totalCells.toDouble() / n, cells.maxOrNull() ?: 0),
        )
        Log.app.i(
            "滚动探针：单块挂载 >20ms 的有 %d 次：%s".format(
                blockMounts.size, blockMounts.joinToString(",") { it.toString() + "ms" },
            ),
        )
        val worst = (0 until n).maxByOrNull { gaps[it] } ?: 0
        Log.app.i(
            "滚动探针：最长帧 #%d（%d ms）—— 布局/放置 %d ms，其中组合段 %d ms"
                .format(worst + 1, gaps[worst], texts[worst], lastComposeMs),
        )
    }
}
