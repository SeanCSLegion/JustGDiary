package com.cov.justdiary.app

import android.content.Intent

/**
 * 调试开关。对应 iOS 的 `-slow-morph` / `SLOW_MORPH=1` 与 `-morph-log`（方案 §6.2）。
 *
 * iOS 侧这些开关包在 `#if DEBUG` 里；Android 侧的做法是：
 * - 放慢 morph：`adb shell am start -n com.cov.justdiary/.app.MainActivity --ez slow-morph true`
 *   或进程环境变量 `SLOW_MORPH=1`；
 * - 逐帧日志：`--ez morph-log true`，morph 结束时用 `JustDiary.app` 打一行帧数/平均帧时长。
 *
 * 判定逻辑抽成纯函数 [resolve]，因此"extra 还是环境变量起作用"这类细节可以在 JVM 上断言
 * （真机上的用途是排查动画卡顿，值不值得为它写测试？—— 值得，因为判断错了会静默失效）。
 */
object DebugFlags {

    /** 当前是否把 morph 放慢到 [SLOW_MORPH_DURATION_MS]（默认由 `MainActivity` 从 `Intent` 解析）。 */
    @Volatile
    var slowMorph: Boolean = false
        private set

    /** 当前是否记录 morph 的逐帧日志。 */
    @Volatile
    var morphLog: Boolean = false
        private set

    /**
     * **临时的帧率上限**（0 = 不限制）。
     *
     * 用户 2026-09-27："作为测试，现在先临时限制一下帧率到 30 或者 60"。
     * 用 `Choreographer` 的帧回调做门闸（draw 阶段比对时间戳，未到点就跳过这一帧的绘制），
     * 这样同一台机器上能直接对比"30 / 60 / 不限"三档的观感。
     *
     * 用法：`adb shell am start -n com.cov.justdiary/.app.MainActivity --ei frame-gate 60`
     */
    @Volatile
    var frameGateFps: Int = 0
        private set

    /** 放慢后的时长（iOS 为 3.0s）。 */
    const val SLOW_MORPH_DURATION_MS: Int = 3_000

    const val EXTRA_SLOW_MORPH: String = "slow-morph"
    const val EXTRA_MORPH_LOG: String = "morph-log"
    const val EXTRA_FRAME_GATE: String = "frame-gate"
    const val ENV_SLOW_MORPH: String = "SLOW_MORPH"

    data class Value(val slowMorph: Boolean, val morphLog: Boolean, val frameGateFps: Int)

    /** 纯判定：`extra` 优先，其次环境变量（`SLOW_MORPH=1`）。 */
    fun resolve(
        slowMorphExtra: Boolean,
        morphLogExtra: Boolean,
        env: Map<String, String> = emptyMap(),
        frameGateFpsExtra: Int = 0,
    ): Value = Value(
        slowMorph = slowMorphExtra || env[ENV_SLOW_MORPH] == "1",
        morphLog = morphLogExtra,
        frameGateFps = frameGateFpsExtra.coerceIn(0, 240),
    )

    /** 从启动 `Intent` 解析（`MainActivity` 在 `onCreate` / `onNewIntent` 调）。 */
    fun consume(intent: Intent?) {
        val value = resolve(
            slowMorphExtra = intent?.getBooleanExtra(EXTRA_SLOW_MORPH, false) == true,
            morphLogExtra = intent?.getBooleanExtra(EXTRA_MORPH_LOG, false) == true,
            env = System.getenv() ?: emptyMap(),
            frameGateFpsExtra = intent?.getIntExtra(EXTRA_FRAME_GATE, 0) ?: 0,
        )
        slowMorph = value.slowMorph
        morphLog = value.morphLog
        frameGateFps = value.frameGateFps
    }
}
