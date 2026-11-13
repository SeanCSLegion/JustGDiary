package com.cov.justdiary.app

import android.content.Intent
import com.cov.justdiary.BuildConfig

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

    /** 放慢后的时长（iOS 为 3.0s）。 */
    const val SLOW_MORPH_DURATION_MS: Int = 3_000

    const val EXTRA_SLOW_MORPH: String = "slow-morph"
    const val EXTRA_MORPH_LOG: String = "morph-log"
    const val ENV_SLOW_MORPH: String = "SLOW_MORPH"

    data class Value(val slowMorph: Boolean, val morphLog: Boolean)

    /** 纯判定：`extra` 优先，其次环境变量（`SLOW_MORPH=1`）。 */
    fun resolve(
        slowMorphExtra: Boolean,
        morphLogExtra: Boolean,
        env: Map<String, String> = emptyMap(),
    ): Value = Value(
        slowMorph = slowMorphExtra || env[ENV_SLOW_MORPH] == "1",
        morphLog = morphLogExtra,
    )

    /** 从启动 `Intent` 解析（`MainActivity` 在 `onCreate` / `onNewIntent` 调）。 */
    fun consume(intent: Intent?) {
        // P2-9：release 里不接受任何 Intent / 环境变量开关，避免第三方用 extra 打开高频日志。
        if (!BuildConfig.DEBUG) {
            slowMorph = false
            morphLog = false
            return
        }
        val value = resolve(
            slowMorphExtra = intent?.getBooleanExtra(EXTRA_SLOW_MORPH, false) == true,
            morphLogExtra = intent?.getBooleanExtra(EXTRA_MORPH_LOG, false) == true,
            env = System.getenv() ?: emptyMap(),
        )
        slowMorph = value.slowMorph
        morphLog = value.morphLog
    }
}
