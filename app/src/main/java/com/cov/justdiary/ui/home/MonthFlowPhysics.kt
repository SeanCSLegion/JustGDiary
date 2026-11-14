package com.cov.justdiary.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.key
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.unit.dp
import com.cov.justdiary.app.DebugFlags
import com.cov.justdiary.service.Log
import kotlin.math.abs
import kotlin.math.max
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * 月流的手势与惯性：橡皮筋、投影落点、弹簧回弹。
 *
 * 从 [MonthFlow] 拆出；纯算术集中在 [MonthFlowPhysics]，便于 JVM 断言。
 */

// --------------------------------------------------------------------------- 手势

/**
 * 连续滚动的拖动 + 惯性（**无级、不吸附**）。
 *
 * 逐条对齐 iOS `MonthFlowView.dragGesture`：
 *  - 起手阈值 2dp（iOS 是 `DragGesture(minimumDistance: 2)`）—— 6dp 的阈值会让内容
 *    在手指动了 6dp 之后才开始跟，明显「不跟手」；
 *  - 拖动中 [MonthFlowPhysics.rubberClamped]：两端是橡皮筋，不是硬墙（硬夹会让拖到头和拖到一半手感一样）；
 *  - 松手：按**预测落点**滑到位，把甩出的速度当作弹簧初速；阻尼比 ≈ 1.14（略过阻尼）
 *    —— 松手后是一条平滑减速的滑行、到点即停，**没有回弹/过冲**。
 *
 * 惯性放在**另一个协程**里跑：手势循环要立刻能接住下一次按下（否则「手指追上滑行」
 * 会等动画跑完才响应）。
 *
 * ⚠️ 这个 lambda 的接收者是 `AwaitPointerEventScope`（**受限协程作用域**）：里面
 * 只能写状态，不能调用 `Animatable.snapTo` 这类 suspend 函数。
 */
internal suspend fun PointerInputScope.flowDrag(
    scroll: FlowScroll,
    layout: MonthFlowLayout,
    viewportH: Float,
    density: Float,
    scope: CoroutineScope,
    onSettle: (Int) -> Unit,
) {
    val slopPx = 2.dp.toPx()
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        // 手指追上正在滑行的惯性：立刻把动画停在**当前帧**（iOS 的 `freezeAnimation`）
        scroll.animJob?.cancel()
        scroll.dragging = true
        val origin = scroll.offsetDp
        var accumulatedPx = 0f
        var started = false
        val tracker = VelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var sampleCount = 1
        var firstTime = down.uptimeMillis
        var lastTime = down.uptimeMillis

        while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            sampleCount++
            lastTime = change.uptimeMillis
            if (!change.pressed) {
                accumulatedPx += change.positionChange().y
                break
            }
            val dy = change.positionChange().y
            accumulatedPx += dy
            if (!started) {
                // 还没起手：不消费事件，日期格的点击照常（与 iOS 的 2dp 阈值同一个意图）
                if (abs(accumulatedPx) < slopPx) continue
                started = true
            }
            change.consume()
            scroll.offsetDp = MonthFlowPhysics.rubberClamped(
                raw = origin - accumulatedPx / density,
                maxOff = layout.maxOffset(viewportH),
                viewportH = viewportH,
            )
        }

        scroll.dragging = false
        if (DebugFlags.morphLog) {
            Log.app.d("monthflow drag: 样本=%d 首个=%d 末个=%d 末速度=%.0fpx/s".format(
                sampleCount, firstTime, lastTime, tracker.calculateVelocity().y,
            ))
        }
        if (started) {
            val velocityDp = tracker.calculateVelocity().y / density
            val translationDp = accumulatedPx / density
            scope.launch {
                settle(scroll, layout, viewportH, origin, translationDp, velocityDp)
                val index = layout.dominantBlockIndex(scroll.offsetDp, viewportH)
                layout.blocks.getOrNull(index)?.let { onSettle(it.key) }
            }
        }
    }
}

/**
 * 松手后的滑行。
 *
 * `target` 用**预测落点**（iOS 的 `predictedEndTranslation` ≈ 位移 + 速度 × 0.5s），
 * 弹簧只负责「以这个速度平滑地滑到那个点」。位移很小（< 4dp）时不传初速，
 * 免得弹簧一上来就飞（iOS 的注释专门讲过这一条）。
 */
private suspend fun settle(
    scroll: FlowScroll,
    layout: MonthFlowLayout,
    viewportH: Float,
    origin: Float,
    translationDp: Float,
    velocityDpPerSec: Float,
) {
    val maxOff = layout.maxOffset(viewportH)
    val target = MonthFlowPhysics.projectedTarget(origin, translationDp, velocityDpPerSec, maxOff)
    val initial = MonthFlowPhysics.initialVelocity(origin, target, velocityDpPerSec)
    if (DebugFlags.morphLog) {
        // 投影值只给这条日志用 —— 放进开关里，关掉调试时不做这次乘法。
        val projected = origin - translationDp - velocityDpPerSec * MonthFlowPhysics.PROJECTION_SECONDS
        Log.app.d(
            "monthflow fling: v=%.0fdp/s 拖了%.0fdp 预测落点%.0fdp（夹取后%.0f，上限%.0f）".format(
                velocityDpPerSec, translationDp, projected, target, maxOff,
            ),
        )
    }
    animateOffset(
        scroll = scroll,
        target = target,
        spec = spring(
            dampingRatio = MonthFlowPhysics.SPRING_DAMPING_RATIO,
            stiffness = MonthFlowPhysics.SPRING_STIFFNESS,
            visibilityThreshold = 0.5f,
        ),
        initialVelocity = initial,
    )
}

/**
 * 用 `Animatable` 逐帧把偏移写回 [FlowScroll]。
 *
 * 之所以不把 `Animatable` 直接当状态存着：拖动要在受限协程作用域里写值，
 * 而 `Animatable.snapTo` 是 suspend 的。这里让动画单向地把值推给状态。
 */
internal suspend fun animateOffset(
    scroll: FlowScroll,
    target: Float,
    spec: androidx.compose.animation.core.AnimationSpec<Float>,
    initialVelocity: Float = 0f,
) {
    val anim = Animatable(scroll.offsetDp)
    anim.animateTo(
        targetValue = target,
        animationSpec = spec,
        initialVelocity = initialVelocity,
    ) {
        scroll.offsetDp = value
    }
    if (DebugFlags.morphLog) {
        Log.app.d("monthflow settle: 落在 %.1fdp（目标 %.1fdp）".format(scroll.offsetDp, target))
    }
}

/**
 * 连续月历流的**手感物理**。抽成纯函数是为了能在 JVM 上断言
 * （真机上"甩一下滑多远"很难量，但公式可以逐条钉住）。
 *
 * 三个常数都直接来自 iOS `MonthFlowView.dragGesture`：
 * `stiffness: 130` / `damping: 26`（阻尼比 ≈ 1.14，略过阻尼）/ 预测落点 ≈ 速度 × 0.5s。
 */
object MonthFlowPhysics {

    /** `predictedEndTranslation` 的投影时长：iOS 用 UIKit 的减速度模型，≈ 速度 × 0.5s。 */
    const val PROJECTION_SECONDS = 0.5f

    /** 弹簧刚度：与 iOS `interpolatingSpring(mass: 1, stiffness: 130, damping: 26)` 同一个数。 */
    const val SPRING_STIFFNESS = 130f

    /** 阻尼比 ≈ 26 / (2√130) ≈ 1.14 —— **略过阻尼**：平滑减速、到点即停，没有回弹。 */
    const val SPRING_DAMPING_RATIO = 1.14f

    /** 位移小于这个值就不传初速（免得弹簧一上来就飞，iOS 用的是 4pt）。 */
    const val MIN_VELOCITY_DELTA = 4f

    /**
     * 松手后的**预测落点**：`起点 − 位移 − 速度 × 0.5s`，再夹在 `[0, maxOff]`。
     *
     * 符号约定与 [FlowScroll.offsetDp] 一致：offset 越大 = 内容越往上走（看更晚的月份），
     * 手指上滑（`translation < 0`）→ offset 增大。
     */
    fun projectedTarget(
        origin: Float,
        translationDp: Float,
        velocityDpPerSec: Float,
        maxOff: Float,
    ): Float = (origin - translationDp - velocityDpPerSec * PROJECTION_SECONDS).coerceIn(0f, maxOff)

    /** 弹簧初速（内容坐标系，dp/s）：**位移很小就不给初速**。 */
    fun initialVelocity(origin: Float, target: Float, velocityDpPerSec: Float): Float =
        if (abs(target - origin) > MIN_VELOCITY_DELTA) -velocityDpPerSec else 0f

    /** 越界时的橡皮筋：`c = viewportH × 0.25`，与 iOS 的 `rubber(_:)` 同一个公式。 */
    fun rubberClamped(raw: Float, maxOff: Float, viewportH: Float): Float = when {
        raw < 0f -> -rubber(-raw, viewportH)
        raw > maxOff -> maxOff + rubber(raw - maxOff, viewportH)
        else -> raw
    }

    /** 橡皮筋的阻尼曲线：`c·x / (x + c)`（x 越大越拖不动，且永远不越过 c）。 */
    fun rubber(x: Float, viewportH: Float): Float {
        val c = max(1f, viewportH * 0.25f)
        return c * x / (x + c)
    }
}
