package com.cov.justdiary.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.Job

/**
 * 月流的纯几何：布局、偏移状态与 morph 源数据的度量探针。
 *
 * 从 [MonthFlow] 拆出；不依赖 Compose 组合，`MonthFlowLayoutTest` 在 JVM 上逐条断言。
 */

/**
 * 一条连续月历流的几何。对应 iOS `MonthFlowLayout`。
 *
 * 纯数值计算、不依赖 Compose：`MonthFlowLayoutTest` 在 JVM 上逐条断言
 * （行高一致 / 月份之间不留空隙 / 静止偏移 / 可见块 / 顶部月份）。
 */
class MonthFlowLayout private constructor(
    val weekStart: String,
    val rowH: Float,
    val blocks: List<Block>,
) {

    /** 一块 = 一个月（本月第一行的顶就是这一块的顶）。 */
    data class Block(
        /** 在 [blocks] 里的序号。 */
        val index: Int,
        /** `yyyyMM`。 */
        val key: Int,
        val monthFirst: LocalDate,
        /** 本块在内容坐标里的顶 —— 也是**本块的起点**、「静止」时滚动偏移该取的值。 */
        val top: Float,
        /** 要画的周行数（[CalendarGrid.displayedWeeks] 的行数，4…6）。 */
        val rowCount: Int,
        /** 本块最后一行的底。下一块的 `top` = 它 + [CalendarLayout.MONTH_GAP]。 */
        val bottom: Float,
    )

    /** 整条流的总高。 */
    val contentH: Float = blocks.lastOrNull()?.bottom ?: 0f

    /** 内容坐标 `y` 落在哪一块。二分，越界时夹到两端。 */
    fun blockIndexAt(y: Float): Int {
        if (blocks.isEmpty()) return 0
        var lo = 0
        var hi = blocks.size - 1
        var ans = 0
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            if (blocks[mid].top <= y) {
                ans = mid
                lo = mid + 1
            } else {
                hi = mid - 1
            }
        }
        return ans
    }

    /**
     * 视口里**占得最多**的那一块（顶部大标题用它）。
     *
     * iOS 的注释：不能用「视口顶部落在哪一块」—— 后者在下一月刚露一行时就换标题，
     * 快速滑动会乱跳；「占多数」在整屏里只会过半时切一次。
     */
    fun dominantBlockIndex(offset: Float, viewportH: Float): Int {
        if (blocks.isEmpty()) return 0
        val top = offset
        val bottom = offset + viewportH
        var best = blockIndexAt(offset)
        var bestShare = -1f
        var i = best
        while (i > 0 && blocks[i - 1].bottom > top) i--
        while (i < blocks.size && blocks[i].top < bottom) {
            val share = min(blocks[i].bottom, bottom) - max(blocks[i].top, top)
            if (share > bestShare) {
                bestShare = share
                best = i
            }
            i++
        }
        return best
    }

    /** 月份键 → 块序号（找不到返回 -1）。 */
    fun blockIndexForKey(key: Int): Int {
        var lo = 0
        var hi = blocks.size - 1
        while (lo <= hi) {
            val mid = (lo + hi) / 2
            val cmp = blocks[mid].key
            when {
                cmp == key -> return mid
                cmp < key -> lo = mid + 1
                else -> hi = mid - 1
            }
        }
        return -1
    }

    /** 某个月的「静止」偏移：该月第一行顶到视口顶部。 */
    fun restOffset(key: Int): Float? =
        blockIndexForKey(key).takeIf { it >= 0 }?.let { blocks[it].top }

    fun maxOffset(viewportH: Float): Float = max(0f, contentH - viewportH)

    /**
     * 与 `[offset - margin, offset + viewportH + margin]` 相交的块序号区间。
     *
     * 返回 [IntRange] 而不是 `List<Block>`：组合层要把它包进 `derivedStateOf`，
     * 区间**相等**时（滚动过程中的绝大多数帧）不触发重组，列表则每帧都是新实例。
     */
    fun visibleBlockRange(
        offset: Float,
        viewportH: Float,
        margin: Float,
        /**
         * 视口之外**额外**预挂载的块数（上下各这么多块）。
         *
         * 真机实测（`-morph-log` 逐帧日志，MEIZU 21 / 120Hz）：正常帧 8ms（120Hz 预算内），
         * 但**新的一个月首次挂载**那一帧要 90~365ms —— 每次滚到新月份都会顿一下。
         * 预挂载把这份代价提前到"用户还没滚到那里"的时候付。
         */
        preMount: Int = 0,
    ): IntRange {
        if (blocks.isEmpty()) return IntRange.EMPTY
        val anchor = blockIndexAt(max(0f, offset - margin))
        val first = (anchor - preMount).coerceAtLeast(0)
        val limit = offset + viewportH + margin
        var last = anchor
        while (last + 1 < blocks.size && blocks[last + 1].top < limit) last++
        last = (last + preMount).coerceAtMost(blocks.size - 1)
        return first..last
    }

    fun visibleBlocks(offset: Float, viewportH: Float, margin: Float): List<Block> =
        visibleBlockRange(offset, viewportH, margin).map { blocks[it] }

    /** 某一行在内容坐标里的顶。 */
    fun rowTop(block: Block, row: Int): Float = block.top + row * rowH

    companion object {
        /**
         * `weekStart # rowH` 的缓存。
         *
         * 行数只随 (weekStart, rowH) 变：旋转 / 密度变化时重建一次，滚动过程中直接复用
         * （每个月一次 O(1) 的行数计算 × 2412 个月，每帧重算也能跑，但没必要）。
         */
        private val cache = LinkedHashMap<String, MonthFlowLayout>()

        fun of(weekStart: String, rowH: Float): MonthFlowLayout {
            val h = rowH.coerceAtLeast(1f)
            val key = "$weekStart#${(h * 100f).roundToInt()}"
            cache[key]?.let { return it }
            val built = MonthFlowLayout(weekStart, h, buildBlocks(weekStart, h))
            // ⚠️ 别写成 `cache.clear()`：窗口 resize / 分屏拖动 / 字号动画期间行高逐帧变化，
        // 整张清空会让**上一帧的行高**也一起丢掉，下一帧连一次命中都没有。
        // 按插入顺序淘汰最老的一条即可。
        while (cache.size > 8) {
            val oldest = cache.keys.firstOrNull() ?: break
            cache.remove(oldest)
        }
            cache[key] = built
            return built
        }

        private fun buildBlocks(weekStart: String, rowH: Float): List<Block> {
            val out = ArrayList<Block>(CalendarGrid.MONTH_KEYS.size)
            var cursor = 0f
            CalendarGrid.MONTH_KEYS.forEachIndexed { i, key ->
                val first = CalendarGrid.monthFirstOfMonthKey(key)
                val rows = CalendarGrid.displayedWeekCount(first, weekStart)
                val bottom = cursor + rows * rowH
                out.add(Block(i, key, first, cursor, rows, bottom))
                // 月与月之间留一段**真实距离**（小标题就落在这段里）
                cursor = bottom + CalendarLayout.MONTH_GAP
            }
            return out
        }
    }
}

/**
 * 外部要求「跳到某个月」的一次请求（今天 / 年历点月 / 旋转复位）。
 *
 * 滚动位置由 [MonthFlow] 自己持有（惯性要交给 `Animatable` 逐帧插值），所以跳转要
 * 显式发起；`token` 让「连续两次跳到同一个月」也能被 `LaunchedEffect` 看到。
 */
data class MonthFlowJump(val key: Int, val animated: Boolean, val token: Int)

// --------------------------------------------------------------------------- morph 源

/**
 * 月→周 morph 的源几何：把按下那一刻**屏幕上真实画着的东西**冻结下来。
 *
 * morph 视图照这份数字重画，所以切换的那一帧与原画面逐像素一致；反方向（周→月）
 * 用同一份源、同一条进度曲线倒放 —— 这就是用户要的「从哪来到哪去」。
 */
data class MonthFlowMorphSource(
    val rows: List<Row>,
    val labels: List<Label>,
    /** [rows] 里被点中的那一行。 */
    val selectedIndex: Int,
    /** 冻结时**顶部大标题显示的那个月**（点中的日期可能属于下一个月的行）。 */
    val topMonthKey: Int,
    val rowH: Float,
    /** 视口在日历区里的顶（= 标题槽 + 星期栏）。 */
    val viewportTop: Float,
    /** 整块日历区的高度。 */
    val height: Float,
) {
    val selectedRow: Row? get() = rows.getOrNull(selectedIndex)

    /** 冻结时的一行日期。 */
    data class Row(
        val week: List<LocalDate>,
        /**
         * 冻结时这一行在**日历区坐标**里的顶（已含标题槽、星期栏与滚动偏移）。
         *
         * ⚠️ 这是**实测值**（`MonthFlowBlock` 的 `onGloballyPositioned` → 块顶 + 行号 × 行高），
         * 不是按公式反算的 —— 反算与真实渲染实测能差 ~200dp（见 `FlowGeometryProbe`）。
         */
        val top: Float,
        val anchorMonth: LocalDate,
        /** 本块第一行不画分隔线（与流里的判断一致）。 */
        val showDivider: Boolean,
    )

    /** 冻结时的一个月份小标题。 */
    data class Label(
        val monthFirst: LocalDate,
        /** 月份键（`CalendarGrid.monthKey`）—— morph 里判 `isCurrent` 用。 */
        val key: Int,
        /**
         * 冻结时小标题**顶边**在日历区坐标里的 y（紧贴日期数字上方）。
         *
         * ⚠️ 与行一样是**实测值**（`MonthFlowLabel` 的 `onGloballyPositioned`）。
         * 以前这里存的是"底边"、而且底边是按 `块顶 − (labelBandH + LABEL_TIGHT_GAP)`
         * 反算的 —— 那其实是**顶边**的式子（真实底边还要再加一行字高 ≈ 24dp），
         * 于是 morph 期间小标题比真实位置高整整一行字高（21dp），交班那一帧往下跳一下
         *（用户 2026-09-27："小标题的位置偏高了一点点"）。
         */
        val top: Float,
    )
}

/**
 * 点日期回调要用到的"当帧几何"。
 *
 * 刻意用**普通可变字段**：它只是为了让回调实例恒定（不进 `remember` 的 key），
 * 值在每帧组合时刷新，点下去时读到的永远是最新值。写成快照状态会让整块每帧重组。
 */
internal class TapGeometry {
    var viewportH: Float = 0f
    var heightDp: Float = 0f
    var titleHeight: Float = 0f
    var weekdayHeight: Float = 0f
    var labelBandH: Float = 0f
}

/**
 * 把连续流的滚动偏移"漏"给外部（年↔月 morph 层要用它算源矩形）。
 *
 * 刻意用**普通可变字段**而不是 Compose state：它在每一帧的放置阶段被写（见 `MonthFlow`
 * 里那个 `Modifier.offset { }`），写成 state 会让整屏每帧重组 —— 那正是"不跟手"的根因。
 */
class FlowOffsetProbe {
    var value: Float = 0f
}

/**
 * 连续月历流里各块 / 各小标题的**实测**几何（窗口坐标 px）。
 *
 * ## 为什么必须实测，不能反算
 *
 * `morphSource` 原先按 `block.top + i × rowH − offset + 标题槽 + 星期栏` 反算每一行的
 * 屏幕位置。这个式子在 iOS 上是对的（SwiftUI 的布局就是精确的数值），但在 Compose 上
 * 至少差在三处：
 *  1. `Modifier.offset { IntOffset(0, ((block.top − off) × density).roundToInt()) }`
 *     把每个块**取整到整像素**（误差 ≤ 0.5px × 密度 ≈ 1.4dp，一个月就是几 dp）；
 *  2. 行高被量化到 0.5dp 再建版面（`rowHKey`），而冻结源用的是**未量化**的值；
 *  3. 启动期会组合两次（视口高度从「还没量准」变成最终值），期间布局实例被换掉。
 *
 * 用户 2026-09-27 的原话是"跳上去的根"：morph 第一帧的行位置与屏幕上真实的行差得远
 * （实测能到 ~200dp），点下去那一下整屏日期会先跳一下再开始动。
 *
 * 所以冻结源里的行位置**一律取这里记下的实测值**（`onGloballyPositioned` 的
 * `positionInWindow()`），只在实测值还没写进来时才退回公式。
 *
 * 刻意用**普通可变字段**而不是 Compose state：它在每一帧的放置阶段被写，
 * 写成 state 会让整条流每帧重组。
 */
class FlowGeometryProbe {
    /** 流根节点（= 日历区左上角）在窗口里的 y（px）。行位置都以它为原点换算。 */
    var rootWindowY: Float = Float.NaN

    /** 每个块在窗口里的 y（px）。key = `MonthFlowLayout.Block.index`。 */
    val blockWindowY = HashMap<Int, Float>()

    /** 每个小标题在窗口里的上边 / 下边（px）。key = 月份键。 */
    val labelWindowTop = HashMap<Int, Float>()
    val labelWindowBottom = HashMap<Int, Float>()

    /** 某个块顶相对流根（= 日历区坐标 dp）；没实测到返回 null。 */
    fun blockTopDp(index: Int, density: Float): Float? {
        val y = blockWindowY[index] ?: return null
        if (!rootWindowY.isFinite() || density <= 0f) return null
        return (y - rootWindowY) / density
    }

    /** 某个小标题的**顶边**相对流根（= 日历区坐标 dp）；没实测到返回 null。 */
    fun labelTopDp(key: Int, density: Float): Float? {
        val y = labelWindowTop[key] ?: return null
        if (!rootWindowY.isFinite() || density <= 0f) return null
        return (y - rootWindowY) / density
    }
}

// --------------------------------------------------------------------------- 滚动状态

/**
 * 连续月历流的滚动状态。
 *
 * 同时承担四件事，所以值得单独一个类：
 *  1. 滚动位置是**跨形态保持**的（月→年→月、月→周→月 都不重置）：它挂在这条流上，
 *     而这条流**始终挂载**（morph 期间只是把不透明度压到 0）；
 *  2. 偏移是**唯一来源**，只在延迟 lambda（`Modifier.offset {}` / `graphicsLayer {}`）
 *     里被读 —— 拖动时不让任何组合失效，这是「跟手」的前提；
 *  3. `animJob` 让「手指追上正在滑行的惯性」成为可能（按下即取消）；
 *  4. [dragging] 驱动右缘的滚动指示条。
 *
 * ⚠️ 偏移本身是**普通快照状态**而不是 `Animatable`：手指拖动要写在
 * `AwaitPointerEventScope`（受限协程作用域）里，那里**不允许调用任意 suspend 函数**
 * （`Animatable.snapTo` 就是 suspend）。所以拖动只写状态，动画交给协程里的
 * `Animatable` 逐帧回写。
 */
class FlowScroll(initialOffsetDp: Float) {
    /** 屏幕上这一帧的滚动偏移（内容坐标 dp）。 */
    var offsetDp by mutableFloatStateOf(initialOffsetDp)

    /** 正在跑的惯性 / 跳月动画（拖动或点按时取消它）。 */
    var animJob: Job? = null

    /** 是否正在被手指拖着（滚动指示条用）。 */
    var dragging by mutableStateOf(false)
        internal set
}
