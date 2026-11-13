package com.cov.justdiary.ui.home

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.cov.justdiary.R
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.drawText
import com.cov.justdiary.service.DateFormats
import java.util.Locale
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cov.justdiary.app.DebugFlags
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.service.Log
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.TypeSize
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ===========================================================================
// 连续月历流（月视图：竖屏整屏 / 平板分栏左栏）
//
// **逐条移植**自 iOS `Views/Home/MonthFlow.swift`（commit fbbfb83）。
//
// 改造前：一个月一页，上下滑**整页翻月**（`LazyColumn` + `rememberSnapFlingBehavior`
// + 「停稳后拨回项起点」的兜底吸附）。用户：「吸附效果其实效果很不好」。
// 改造后：顶部「大月份标题 + 星期栏」钉住不动，下面是**一条连续滚动的月份流**：
//
//     ┌ 九月 ◀── 大标题：钉住，跟着滚动实时切成「占住视口最多的那一个月」
//     │ 一 二 三 四 五 六 日 ◀── 星期栏：钉住
//     ├──────────────────────────────────────────────
//     │ 28 29 30                       ← 上个月的最后一行
//     │              10月              ← 小标题：在**分割线上方**、紧贴它
//     │  ──  ──  ──  ──                ← 按格画的分割线（只画有日期的那几格）
//     │                 1  2  3  4      ← 本月第一行（分割线在日期上面）
//     │  5  6  7  8  9 10 11
//     └──────────────────────────────────────────────
//
// 三条几何约定（全部由 [MonthFlowLayout] 精确算出来 —— morph 的起点必须与屏幕上
// 画的东西逐像素一致，所以不能依赖懒加载测量）：
//
// 1. **行高全局一致**：每个月 = 一行小标题带 + 若干周行，都是同一个 `rowH`。
// 2. **月份之间不留空隙**：两个月的行紧挨着；小标题画在**分割线上方**（贴着
//    [CalendarLayout.CalendarLayout.LABEL_TIGHT_GAP]），分割线画在本月第一行的顶。
//    所以从上到下的次序是「上个月的日期 → 小标题 → 分割线 → 本月日期」。
// 3. **静止位置 = 该月第一行的顶**：进入某个月（初始 / 今天 / 年历点月）时把该月
//    第一行顶到视口顶部，小标题刚好落在视口上沿之外（被钉住层盖住）。
//
// 与 iOS 的三处**刻意的差异**（都是平台要求，不是省事）：
//  - 每一个日期都是**真实的 Compose 组件**（TalkBack 逐格可读），不是 Canvas；
//  - 钉住层带 `surface` 底色（iOS 那边无底色，靠 `clipped()` 切齐）；
//  - 惯性由 `Animatable` 驱动（Android 没有 SwiftUI 那种「逐帧插值 view」），
//    弹簧刚度 / 阻尼比 / 投影时长三个数都照抄 iOS。
//
// ## 性能上的关键约定（踩过坑，别再改回去）
//
// 滚动偏移存在 [FlowScroll] 里，**滚动过程中这条流不参与重组**：
//  - 每一块的位置走 `Modifier.offset { }`（延迟到放置阶段读偏移）；
//  - 「画哪几块」用 `derivedStateOf` 收敛成 [IntRange]，跨块才变；
//  - 顶部大标题同样用 `derivedStateOf` 收敛成「哪一块」，换月才变；
//  - 年 morph 的缩放 / 透明度、morph 层的每一行位置，都在 `graphicsLayer` 的
//    延迟 lambda 里读进度。
// 反过来（在组合期读偏移或进度）会让整屏每帧重组 —— 那正是首轮「不跟手、卡顿」的根因。
// ===========================================================================

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
         * 真机实测（`ScrollProf`，MEIZU 21 / 120Hz）：正常帧 8ms（120Hz 预算内），
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

// --------------------------------------------------------------------------- 小标题

/**
 * 月份小标题：压在**当月 1 号那一列的正上方**（在这一列里居中，与日号同一个中心），
 * 并且紧贴 1 号那一行。
 *
 * 带高就是这一行字的高度（[CalendarLayout.flowLabelBandHeight]），上下不再留白：
 * 用户看过两版（整行高 → 35pt）都嫌高，明确要求「紧贴月份高度」。
 *
 * 它**借用**的是上一个月最后一行底部的留白（`CalendarLayout.cellContentTopPadding`
 * ≈ 35dp），所以不额外占高度；用户 2026-09-27 要求「小月份和上一个月的最后稍微隔开一点，
 * 以小月份的上方为限」—— 行高对齐 iOS 之后这段留白足够（21 + 3 ≤ 35），
 * 小标题的**顶边**之上还有约 11dp 的空档。`MonthFlowLayoutTest` 把这条钉住了。
 */
@Composable
fun MonthFlowLabel(
    cellW: Float,
    /** 小标题所在的列（0…6）。 */
    column: Int,
    text: String,
    modifier: Modifier = Modifier,
    isCurrent: Boolean = false,
    /** 横屏左栏的紧凑形态（小一号字）。 */
    compact: Boolean = false,
    /**
     * 实测自己的上下边（**窗口坐标 px**）。
     *
     * morph 的冻结源要的是"屏幕上真实画着的那个位置"，而这个 Text 的高度由
     * `LocalTextStyle` 的行高决定（`bodyLarge` 的 `lineHeight = 24sp`，比字号本身高
     * 3dp 左右）—— 照字号反算会差一截，所以直接量。
     */
    onBounds: ((top: Float, bottom: Float) -> Unit)? = null,
) {
    val fontScale = LocalDensity.current.fontScale
    Text(
        text = text,
        // ⚠️ **不要**给这一行字加固定高度（`height(bandH.dp)`）。
        //
        // Compose 在高度放不下**一整行**（连 `includeFontPadding` 那一圈也算）时会把
        // 整行字**一个都不画**：实测 21dp 带高下节点在、语义在、屏幕上却什么都没有；
        // 把高度放到 60dp 立刻正常。所以这里让它自己量高，只用 offset 把**顶边**
        // 摆到带子该在的位置（带高 21dp 只是设计值，字自己 ~22dp，差 1dp 看不出来）。
        modifier = modifier
            .offset(x = (column * cellW).dp)
            .width(cellW.dp)
            .then(
                if (onBounds == null) {
                    Modifier
                } else {
                    Modifier.onGloballyPositioned { coords ->
                        val y = coords.positionInWindow().y
                        onBounds(y, y + coords.size.height)
                    }
                },
            ),
        fontSize = DynamicTypeMetrics.calendarSp(
            CalendarLayout.flowLabelFontSize(compact).dp,
            fontScale,
        ),
        fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
        color = if (isCurrent) {
            MaterialTheme.colorScheme.primary
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        maxLines = 1,
        overflow = TextOverflow.Clip,
        softWrap = false,
        // **在这一列里居中** = 压在 1 号正上方（1 号的日号也是在这一列居中的）。
        // iOS 的 `frame(width: cellW, height: bandH, alignment: .bottom)` 默认就是居中。
        textAlign = TextAlign.Center,
    )
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
private class TapGeometry {
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

/**
 * "隐藏"用的不透明度。
 *
 * ⚠️ **不能写 `0f`**：`graphicsLayer` 的 alpha 归零后这一层会被整层跳过（不再录制显示
 * 列表），等 morph 结束把 alpha 翻回 1 时，重新录**要花几帧** —— 那几帧里日期区是
 * **空的**（大标题与星期栏还在，日期整片消失再冒出来）。
 * 用户报的"年视图切换到月视图还是在最后会闪一下"就是它；录屏逐帧能看到空白持续 2~4 帧。
 *
 * `0.004` 的对比度约等于 1/255（四舍五入后最多差 1 个灰阶），肉眼不可见，
 * 但这一层始终在画，翻回 1 时是即时的。
 */
private const val HIDDEN_ALPHA = 0.004f

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

// --------------------------------------------------------------------------- 主视图

/**
 * 连续月历流（月视图）。
 *
 * @param layout 几何（由 `rowH` 与 `weekStart` 缓存得到）。
 * @param viewportH 内容视口高度 = 日历区高度 − 标题槽 − 星期栏。
 * @param initialOffsetDp 初始滚动偏移（内容坐标 dp），只在**第一次**组合时生效。
 * @param resetKey 布局复位键（行高 / 周起始变了要回到当前月的静止位置）。
 * @param jump 外部跳月请求。
 * @param enabled morph 期间为 false：整条手势撤下（规范 §1 规则 2）。
 * @param visible 月态为 true。false 时不透明度压到 0，但**仍然挂载** ——
 *   滚动位置是它自己的状态，卸载就丢（用户要求「返回的时候月视图就留在哪」）。
 */
@Composable
fun MonthFlow(
    layout: MonthFlowLayout,
    viewportH: Float,
    widthDp: Float,
    heightDp: Float,
    titleHeight: Float,
    weekdayHeight: Float,
    weekStart: String,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    initialOffsetDp: Float,
    resetKey: Any?,
    resetMonthKey: Int,
    jump: MonthFlowJump?,
    enabled: Boolean,
    visible: Boolean,
    onDayTap: (LocalDate, MonthFlowMorphSource) -> Unit,
    onTopMonthChange: (Int) -> Unit,
    onSettle: (Int) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 当前滚动偏移的**写出点**：年↔月 morph 层要按"屏幕上那一块网格的真实位置"起手，
     * 而偏移只存在于这条流里。传一个普通对象（不是 state）—— 写它不触发重组。
     */
    offsetProbe: FlowOffsetProbe? = null,
    /** 横屏左栏的紧凑形态（小标题小一号）。 */
    compact: Boolean = false,
    titleText: (LocalDate) -> String,
    labelText: (LocalDate) -> String,
) {
    val density = LocalDensity.current.density
    val scope = rememberCoroutineScope()
    val scroll = remember { FlowScroll(initialOffsetDp) }
    val labelBandH = remember(compact) { CalendarLayout.flowLabelBandHeight(compact) }
    // 各块 / 各小标题的**实测**位置（morph 冻结源要用真实值，见 `FlowGeometryProbe`）
    val geometry = remember { FlowGeometryProbe() }
    // 点日期回调读的当帧几何（见 `TapGeometry`：不进 remember 的 key，避免 0.3dp 抖动整窗重建）
    val tapGeometry = remember { TapGeometry() }

    // 几何变了（旋转 / 密度 / 周起始）→ 回到当前月的静止位置（iOS 的 `flowOffset = nil`）。
    // ⚠️ `resetMonthKey` **不能**当 key：滚动过程中它会跟着顶部月份变，
    // 那样每跨一个月就会把流硬拨回该月起点。
    val latestResetMonth by rememberUpdatedState(resetMonthKey)
    //
    // ⚠️ **启动时会组合两次**：首帧视口高度与最终不同，`rowH` 会 101dp → 95dp。
    // `MonthFlowLayout` 的 `block.top` 是"前面所有块的行数 × rowH"累加出来的，rowH 一变
    // **同一个偏移就落到完全不同的月份上**（真机实测：偏移 814312 在 rowH=101 时是 9 月、
    // 在 rowH=95 时是 **7 月**，差 4 个多月）—— 于是屏幕上会看到"先闪出一个 7 月的日历、
    // 再跳到 9 月"（用户报的"启动先跳出来一个默认日历"）。
    //
    // 所以这里不能拿"数字偏移"当跨布局的锚（它在两个坐标系里不是同一个位置），
    // 要拿**月份**当锚：[intendedMonth] = "用户期望待着的那个月"（初始 = 进月态时那个月）。
    // 布局变 = 坐标系变了 ⇒ 用锚月份在新布局里重算偏移；锚月份本身跟着可见月份滚动更新
    //（复位之后用户看到的就是锚月份，于是下一次变化的锚永远是他当前在看的月）。
    var intendedMonth by remember { mutableStateOf(resetMonthKey) }
    LaunchedEffect(resetKey, layout) {
        scroll.animJob?.cancel()
        val target = layout.restOffset(intendedMonth) ?: return@LaunchedEffect
        val block = layout.blocks.getOrNull(layout.blockIndexAt(scroll.offsetDp))
        // 用户已经滑到锚月份之外的块里（正常滚动）⇒ 把锚挪到他实际在看的月，别把他拽回去
        if (block != null && block.key != intendedMonth && scroll.offsetDp - block.top <= layout.rowH) {
            intendedMonth = block.key
        }
        if (abs(scroll.offsetDp - target) > 0.5f) scroll.offsetDp = target
    }

    // 外部跳月（今天 / 年历点月）。token 让「连续两次跳到同一个月」也能被看到。
    LaunchedEffect(jump) {
        val request = jump ?: return@LaunchedEffect
        val target = layout.restOffset(request.key) ?: return@LaunchedEffect
        scroll.animJob?.cancel()
        if (!request.animated) {
            scroll.offsetDp = target
            return@LaunchedEffect
        }
        scroll.animJob = launch {
            animateOffset(scroll, target, tween(CalendarLayout.PAGE_SETTLE_DURATION_MS, easing = FastOutSlowInEasing))
        }
    }

    // ---------------- 挂载窗口（"跨月那一下"就从这里治） ----------------
    //
    // 以前：窗口 = 可见范围（含一个行高的余量）+ 预挂 1 块，**跟着滚动偏移逐块移动**。
    // 于是每跨一个月就要"卸一块 + 挂一块"，而挂一块的代价是**整块重录**（真机实测
    // 6~17ms/块，一旦赶上整窗重建就是 24~44ms 一帧）—— 主观就是"跨月顿一下、不跟手"。
    //
    // 现在：窗口是一份**自己持有的状态**，只有三种情况才动：
    //   ① 首次组合：按可见范围 ± [PREFETCH] 铺开；
    //   ② 用户滑出了窗口（可见范围不再被包含）：必须立刻挪窗口（正确性优先，宁可顿一下）；
    //   ③ **空闲时**（没手势、没惯性动画）每次向两侧各补一块，把"挂一块"的代价
    //      挪到用户没在看的时候付。
    // 手势期间窗口冻住 ⇒ 只要没滑出窗口，滑动过程中**一次都不会挂载/卸载**。
    val prefetch = 3
    val lastBlockIndex = layout.blocks.size - 1

    fun windowAround(first: Int, last: Int): IntRange =
        (first - prefetch).coerceAtLeast(0)..(last + prefetch).coerceAtMost(lastBlockIndex)

    var range by remember(layout, viewportH) {
        val need = layout.visibleBlockRange(scroll.offsetDp, viewportH, margin = 0f)
        mutableStateOf(windowAround(need.first, need.last))
    }

    // 维护窗口。⚠️ 这里刻意用**轮询**而不是 `snapshotFlow`：`scroll.animJob` 是普通字段
    //（不是快照状态，写它不该触发重组），所以"有没有惯性在跑"只有主动问才知道。
    //
    // 三条规则：
    //  ① 用户看的那一段贴到窗口边上了 → **必须**补（否则会露出空白）。一次最多补两块：
    //     滑得再快也跟得上，又不会一口气挂 8 块把一帧拉到上百毫秒。
    //  ② 空闲（没手势、没惯性）→ 每 [IDLE_PREFETCH_STEP_MS] 向两侧各补一块，把"新块首次
    //     录制"的代价挪到用户没在看的时候付；余量补到 ±[prefetch] 就停。
    //  ③ 空闲且窗口明显偏大 → 一次收一块，避免常驻块无限增长。
    //
    // ⚠️ 绝不能像第一版那样整体跳到 `need ± prefetch`：实测跳一次（8 块）会让这 8 块
    // 一起重组重录、一帧 141ms。一块一块挪时，其余块的参数相等（`key()` 的组被移动、
    // 状态保住），只有新挂进来的那块要付钱。
    LaunchedEffect(layout, viewportH) {
        while (true) {
            val need = layout.visibleBlockRange(scroll.offsetDp, viewportH, margin = 0f)
            val cur = range
            val idle = !scroll.dragging && scroll.animJob?.isActive != true
            var first = cur.first
            var last = cur.last
            if (need.first < cur.first) first = maxOf(need.first, cur.first - 2)
            if (need.last > cur.last) last = minOf(need.last, cur.last + 2)
            if (idle) {
                if (first > (need.first - prefetch) && first > 0) first--
                if (last < (need.last + prefetch) && last < lastBlockIndex) last++
                if (first < need.first - prefetch) first++
                if (last > need.last + prefetch) last--
            }
            val next = first.coerceAtLeast(0)..last.coerceAtMost(lastBlockIndex)
            if (next != cur) range = next
            delay(if (idle) IDLE_PREFETCH_STEP_MS else 16)
        }
    }

    // 顶部大标题：占视口最多的那一块（滑动中途就换字，且不会乱跳）
    val topIndex by remember(layout, viewportH) {
        derivedStateOf { layout.dominantBlockIndex(scroll.offsetDp, viewportH) }
    }
    val topBlock = layout.blocks.getOrNull(topIndex) ?: return
    // 顶部月份变化 → 告诉父层（monthKey / 年份 / 分栏的选中日都跟着它）
    LaunchedEffect(layout, viewportH) {
        androidx.compose.runtime.snapshotFlow { topIndex }
            .collect { i -> layout.blocks.getOrNull(i)?.let { onTopMonthChange(it.key) } }
    }

    val contentAlpha: () -> Float = remember(visible) { { if (visible) 1f else HIDDEN_ALPHA } }
    // ⚠️ 隐藏时**必须连日期格一起不画**，不能只把整层 alpha 压到 0.004。
    //
    // 用户 2026-09-27 报"月↔周切换时同一格有两个数字"。诊断（记录 (日期 → rowY)）显示
    // 同一帧里同一天出现了两个 rowY（198 / 0）：一个来自这一层、一个来自 morph 层。
    // 这一层当时 alpha 只有 0.004，肉眼看不见，但**它确实在画**；一旦取值不是 0
    //（比如动画的中间态、或合成器没把它完全丢掉），就会与 morph 层叠成重影。
    // 现在直接把"画不画"做成布尔，从根上保证不可见层不产生像素。
    val drawCells = visible
    // 钉住层的透明度 = 可见性。
    // ⚠️ 必须单独判：周态 / 年态下整条流的不透明度是 0（它必须常驻以保住滚动位置），
    // 但钉住层是**另一个** Box，会照旧画在顶上 —— 实测周态里那颗大标题穿在周条后面。
    val headerAlpha: () -> Float = remember(visible) { { if (visible) 1f else HIDDEN_ALPHA } }

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            .clipToBounds()
            // 记下自己的窗口 y：所有实测行位置都以它为原点换算成"日历区坐标"
            .onGloballyPositioned { geometry.rootWindowY = it.positionInWindow().y },
    ) {
        // ---------------- 内容层（可滚动） ----------------
        //
        // ⚠️ 内容层的坐标原点必须落在**钉住层之下**（`titleHeight + weekdayHeight`）：
        // 钉住层与它是同一个 Box 里的叠层（不是 iOS 那种 VStack），少了这个位移，
        // 每个月都会画到标题底下去 —— 静止时那 72+30 正好把本月第一行整个盖掉。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(viewportH.dp)
                .offset(y = (titleHeight + weekdayHeight).dp)
                .graphicsLayer {
                    // 裁在内容区里：日期不许画到钉住层上面。
                    // （年↔月的过渡由**独立的 morph 层**（`YearMonthMorph`）负责，
                    //   那条流在 morph 期间整体隐藏 —— 简单、也没有层叠顺序问题。）
                    clip = true
                    alpha = contentAlpha()
                }
                .pointerInput(layout, viewportH, enabled) {
                    if (!enabled) return@pointerInput
                    flowDrag(scroll, layout, viewportH, density, scope, onSettle)
                }
                .semantics { if (!visible) hideFromAccessibility() },
        ) {
            // 点日期：morph 源必须与**屏幕上这一帧**一致 —— 先把惯性停在当前帧，再按同一份
            // 数字建源（iOS 的 `morphSource` 开头就是 freeze）。
            //
            // ⚠️ 回调必须**每块一份、实例恒定**（`remember`），而且它的 key 里**不能放
            // 逐帧可能抖动的几何量**。
            //
            // 真机实测（`-morph-log`，MEIZU 21）：`viewportH` / `heightDp` 这类由测量得到的
            // 浮点，在启动第二次组合时会差 ~0.3dp（604.9 → 604.6）。只要它们进了 key，
            // 每一块的回调就全部重建 ⇒ `MonthFlowBlock` 参数全变 ⇒ 整窗重组 + 整窗重录：
            // 一次 10 块共 91ms，最长一帧 232ms —— 这正是"滑动时顿一下"的元凶。
            //
            // 所以几何量走**可变持有者**：实例恒定（不进 key），组合期把最新值写进去，
            // 点下去时读到的仍是最新值。`block` / `layout` 等真正决定"这一块是谁"的量才进 key。
            tapGeometry.viewportH = viewportH
            tapGeometry.heightDp = heightDp
            tapGeometry.titleHeight = titleHeight
            tapGeometry.weekdayHeight = weekdayHeight
            tapGeometry.labelBandH = labelBandH
            val latestOnDayTap by rememberUpdatedState(onDayTap)
            MonthBlockWindow(blocks = layout.blocks, range = range) { block ->
                val handleDayTap = remember(block, layout, weekStart, density, geometry, scroll) {
                        val handler: (LocalDate, Int) -> Unit = { date, row ->
                            scroll.animJob?.cancel()
                            latestOnDayTap(
                                date,
                                morphSource(
                                    layout = layout,
                                    offsetDp = scroll.offsetDp,
                                    tappedBlockIndex = block.index,
                                    tappedRow = row,
                                    titleHeight = tapGeometry.titleHeight,
                                    weekdayHeight = tapGeometry.weekdayHeight,
                                    viewportH = tapGeometry.viewportH,
                                    heightDp = tapGeometry.heightDp,
                                    labelBandH = tapGeometry.labelBandH,
                                    weekStart = weekStart,
                                    density = density,
                                    geometry = geometry,
                                ),
                            )
                        }
                        handler
                    }
                    MonthFlowBlock(
                        block = block,
                        drawCells = drawCells,
                        rowH = layout.rowH,
                        widthDp = widthDp,
                        weekStart = weekStart,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        showLunar = showLunar,
                        labelBandH = labelBandH,
                        labelText = labelText,
                        compact = compact,
                        geometry = geometry,
                        onDayTap = handleDayTap,
                        // ⚠️ 这个 `Modifier.offset { }` **必须 remember**（每块一份）：
                        // `OffsetPxElement.equals` 比的是 lambda 的**引用**，每帧新建 ⇒
                        // `MonthFlowBlock` 的 `modifier` 参数永远不相等 ⇒ 整块永远无法 skip。
                        // 后果实测（`ScrollProf`）：跨月时（`range` 一变）已挂载的**每一块**
                        // 都重组 → 块内 `Canvas` 的绘制 lambda 被换掉 → 该块 layer 失效重录；
                        // 一次跨月里 5 块重录共 43.6ms，正是"跨月顿一下"的主因。
                        modifier = blockModifier(
                            block = block,
                            scroll = scroll,
                            density = density,
                            offsetProbe = offsetProbe,
                        )
                    )
            }

            // 滚动指示条：贴在右缘（iOS 的 `scrollIndicator`）
            FlowScrollIndicator(scroll = scroll, layout = layout, viewportH = viewportH)
        }

        // ---------------- 钉住层（画在最后 = 最上面） ----------------
        MonthPinnedHeader(
            title = titleText(topBlock.monthFirst),
            widthDp = widthDp,
            titleHeight = titleHeight,
            weekdayHeight = weekdayHeight,
            weekStart = weekStart,
            modifier = Modifier.graphicsLayer { alpha = headerAlpha() },
        )
    }
}

/**
 * 按挂载窗口渲染各个月份块（连续月历流的"挂载窗口"循环）。
 *
 * ## ⚠️ 这个循环的形状是**性能契约**，别"顺手写严谨一点"
 *
 * 绝对**不要**写成 `blocks.getOrNull(i) ?: continue`（或任何在 `key(...)` 之前
 * 带 `continue` / 可能失败取值的写法）。循环体里多一个 `continue` 会让编译器生成的组
 * 结构变化，Compose 的 movable group 就**匹配不回**已有的组 —— 挪窗口（头部丢一块、
 * 尾部加一块，滚动时每次跨月都会发生）时，已挂载的每一块都会被**重建**：
 *
 * | 循环写法 | 挪一次窗口重组的项数（JVM 实测，`KeyedRangeReuseTest`）|
 * | --- | --- |
 * | `val b = blocks[i]; key(b.key) { … }` | 1（只有新挂进来的那块）|
 * | `val b = blocks.getOrNull(i) ?: continue; key(b.key) { … }` | **全部 9~10 块** |
 *
 * 真机后果：整窗 9 块重录，一帧 91~157ms（"跨月顿一下、不跟手"的主因）。下标由窗口维护
 * 保证在 `0..blocks.size-1` 内，所以这里直接取即可。
 */
@Composable
internal fun MonthBlockWindow(
    blocks: List<MonthFlowLayout.Block>,
    range: IntRange,
    content: @Composable (MonthFlowLayout.Block) -> Unit,
) {
    for (i in range) {
        val block = blocks[i]
        key(block.key) { content(block) }
    }
}

/**
 * 一块（一个月）的定位修饰符。
 *
 * **必须 `remember`**：`Modifier.offset { }` 生成的是 `OffsetPxElement`，它的 `equals` 比的是
 * lambda 的**引用**。每帧新建一个实例 ⇒ `MonthFlowBlock` 的 `modifier` 参数永远不相等 ⇒
 * 这一块永远无法 skip ⇒ 跨月时（挂载窗口一变）所有已挂载块一起重组、块内 `Canvas` 的绘制
 * lambda 被换掉、该块的 layer 随之失效重录（真机实测：一次跨月 5 块共 43.6ms）。
 *
 * 挂载/卸载仍由 `key(block.key)` 负责，所以这里只按 `block` 记住一份即可。
 */
@Composable
private fun blockModifier(
    block: MonthFlowLayout.Block,
    scroll: FlowScroll,
    density: Float,
    offsetProbe: FlowOffsetProbe?,
): Modifier = remember(block, scroll, density, offsetProbe) {
    Modifier.offset {
        val off = scroll.offsetDp
        // 把"屏幕上这一帧的偏移"记给 morph 层（普通写，不触发重组）
        offsetProbe?.value = off
        IntOffset(0, ((block.top - off) * density).roundToInt())
    }
}

/** 一整块（一个月）：小标题 + 各行日期。 */
@Composable
private fun MonthFlowBlock(
    block: MonthFlowLayout.Block,
    /** `false` = 这一层当前不可见，**连日期格都不画**（见 `MonthFlow` 里 `drawCells` 的说明）。 */
    drawCells: Boolean = true,
    rowH: Float,
    widthDp: Float,
    weekStart: String,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    labelBandH: Float,
    labelText: (LocalDate) -> String,
    compact: Boolean,
    onDayTap: (LocalDate, Int) -> Unit,
    modifier: Modifier = Modifier,
    /** 实测几何的写出点（morph 冻结源要真实位置）。 */
    geometry: FlowGeometryProbe? = null,
) {
    // 临时探针：组合段 / 布局段分开计时
    ScrollProf.blockComposed(block.key)
    val composeStartNs = System.nanoTime()
    val weeks = remember(block.key, weekStart) {
        CalendarGrid.displayedWeeks(block.monthFirst, weekStart)
    }
    val metrics = remember(widthDp, rowH, showLunar) {
        CalendarLayout.flowMetrics(widthDp, rowH, showLunar)
    }
    val cellW = CalendarLayout.monthCellW(widthDp)
    val label = labelText(block.monthFirst)
    // 小标题的强调色跟**今天**所在的那个月走（iOS `MonthFlowLabel.isCurrent`）
    val todayMonthKey = remember(todayKey) {
        DateUtil.parseDayKey(todayKey)?.let { CalendarGrid.monthKeyOf(it) } ?: 0
    }

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height((block.rowCount * rowH).dp)
            // ⚠️ 必须挂在**链条最里层**（`offset { }` 之内）：`positionInWindow()` 要的是
            // 这个块**真正被画在哪儿**，而块的位置是父层那个 `Modifier.offset { }` 在放置
            // 阶段算出来的。挂在 `offset` 之外只会拿到未偏移的坐标。
            .then(
                if (geometry == null) {
                    Modifier
                } else {
                    Modifier.onGloballyPositioned { coords ->
                        geometry.blockWindowY[block.index] = coords.positionInWindow().y
                    }
                },
            ),
    ) {
        // 不可见时**不画日期格**（见 `drawCells` 的说明：只压 alpha 会与 morph 层叠成重影）
        if (drawCells) {
            BlockCanvas(
                block = block,
                weeks = weeks,
                metrics = metrics,
                rowH = rowH,
                showLunar = showLunar,
                selectedKey = selectedKey,
                todayKey = todayKey,
                flags = flags,
                onDayTap = onDayTap,
                modifier = Modifier,
            )
        }
        // 小标题在**分割线上方**（用户的次序：上个月日期 → 小标题 → 分割线 → 本月日期）。
        // 它借用的是上一行底部那段留白，不额外占高度。
        MonthFlowLabel(
            cellW = cellW,
            column = DateUtil.weekdayIndex(block.monthFirst, weekStart),
            text = label,
            compact = compact,
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset(y = (-(labelBandH + CalendarLayout.LABEL_TIGHT_GAP)).dp),
            isCurrent = block.key == todayMonthKey,
            // ⚠️ 同样要 `remember`：每帧新建会让 `MonthFlowLabel` 无法 skip，
            // 进而每帧重新量一次这一行字（`TextStringSimpleNode::measure` 实测 0.6~1.5ms）。
            onBounds = if (geometry == null) {
                null
            } else {
                remember(geometry, block.key) {
                    { top: Float, bottom: Float ->
                        geometry.labelWindowTop[block.key] = top
                        geometry.labelWindowBottom[block.key] = bottom
                    }
                }
            },
        )
        ScrollProf.composeDone(System.nanoTime() - composeStartNs)
    }
}

/**
 * 一个月块的**整块绘制**版本（连续月历流的快路径）。
 *
 * ## 为什么要这么做
 *
 * 真机实测（`ScrollProf`，MEIZU 21 / 120Hz）：正常帧 8ms（预算内），但**新的一个月首次
 * 挂载**那一帧要 100~280ms。逐项量过之后定位是：
 *
 * | 项目 | 实测 |
 * | --- | --- |
 * | 文本测量（整段缓存未命中代价之和） | **24.6ms** |
 * | 那一帧的**组合段** | 0ms |
 * | 那一帧的布局/放置段 | 100~280ms |
 *
 * 也就是说钱花在**布局节点**上：一个月块有 6 行 × 7 格，每格一个 `Box` + 语义 + 可点，
 * 外加列/行容器 —— 约 **300 个节点**的 measure/placement。
 *
 * 所以这里把整块压成：**一层 Canvas（画全部格子 + 分割线）+ 每行一个命中节点**，
 * 节点数从 ~300 降到个位数。格子视觉复用 morph 层那套 `drawDayCell` / `drawCellDividers`
 *（它在"交班那一帧"与真实组件做过 0 像素差比对），所以看起来是同一张图。
 *
 * ## 无障碍（已知取舍）
 *
 * 这一版**不再为每一格建语义节点**（那正是慢的来源）。整块仍有一个语义节点、
 * 日期与状态仍在无障碍树里，但**逐格导航**（TalkBack 左右滑动一格一格读）没有了。
 * 真机上的取舍：先把"用不了"的卡顿解决，逐格语义如果之后要回来，
 * 应该做成"只在无障碍开启时挂上那些节点"，而不是常驻。
 */
@Composable
internal fun BlockCanvas(
    block: MonthFlowLayout.Block,
    weeks: List<List<LocalDate>>,
    metrics: CalendarLayout.DayMetrics,
    rowH: Float,
    showLunar: Boolean,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    onDayTap: (LocalDate, Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fontScale = density.fontScale
    val measurer = rememberTextMeasurer(cacheSize = BLOCK_TEXT_CACHE)
    val textLayouts = MorphTextLayouts.shared
    val scheme = MaterialTheme.colorScheme
    // P1-3：只有无障碍开启时才挂逐日语义节点；正常模式继续走 Canvas（不引入 300 个布局节点）。
    val accessibilityEnabled = rememberAccessibilityEnabled()

    Box(
        modifier = modifier
            // ⚠️ `metrics.cellW` **已经是格宽**（`monthCellW(width) = width / 7`）。
            // 第一版写成 `monthCellW(cellW * 7)` 又除了一次 7、再取整 ⇒ 宽度 0，
            // 整块既画不出来也收不到手势（实测点日期没反应）。
            .width((metrics.cellW * 7f).dp)
            .height((block.rowCount * rowH).dp)
            .then(
                if (accessibilityEnabled) {
                    Modifier
                } else {
                    // 命中：一格一格的坐标换算，避免为 42 个日期各建一个可点节点。
                    // 起手 2dp 内不消费事件，日期格的点击与月流的拖动互不打架。
                    // 无障碍模式下由下面那层逐日节点接管点击，避免同一次点按被处理两遍。
                    Modifier.pointerInput(block.key, weeks, metrics.cellW, metrics.cellH) {
                        detectTapGestures { pos ->
                            val row = (pos.y / (metrics.cellH * density.density)).toInt()
                            val col = (pos.x / (metrics.cellW * density.density)).toInt()
                            val week = weeks.getOrNull(row) ?: return@detectTapGestures
                            if (col !in week.indices) return@detectTapGestures
                            val date = week[col]
                            // 只有当月日期可点（相邻月在本路径里不画）
                            if (date.year != block.monthFirst.year ||
                                date.monthValue != block.monthFirst.monthValue
                            ) {
                                return@detectTapGestures
                            }
                            onDayTap(date, row)
                        }
                    }
                },
            ),
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            // 临时探针：这一块"我们自己画一遍"到底要多久（区分"首次挂载"与"稳态"）
            val probeStartNs = System.nanoTime()
            val probeMissBase = textLayouts.misses
            val cellPx = metrics.cellW * density.density
            val rowPx = metrics.cellH * density.density
            weeks.forEachIndexed { row, week ->
                val rowYDp = row * metrics.cellH
                // 分割线：与真组件同一条规则 —— 只画"这一行里真正属于当月的那几列"，
                // 且本块的第一行只在"不是整条流第一块"时画（`dividerAboveFirst`）。
                if (row > 0 || block.index != 0) {
                    var first = -1
                    var last = -1
                    for (i in week.indices) {
                        val d = week[i]
                        if (d.year == block.monthFirst.year && d.monthValue == block.monthFirst.monthValue) {
                            if (first < 0) first = i
                            last = i
                        }
                    }
                    if (first >= 0) {
                        // ⚠️ 位置与长度都要与旧版**同一个像素起点**：那条线是本行 Box 的
                        // 顶边（`height 1dp`，`y = 行顶`），横向只跨"有当月日期的那几列"
                        //（用户拍板的规则："分割线范围到日期为限，而不是一整行"）。
                        if (metrics.dividerAlpha > 0.01f) {
                            drawRect(
                                color = scheme.outlineVariant.copy(
                                    alpha = 0.35f * metrics.dividerAlpha,
                                ),
                                topLeft = Offset(first * cellPx, rowYDp * density.density),
                                size = Size(
                                    (last - first + 1) * cellPx,
                                    1.dp.toPx(),
                                ),
                            )
                        }
                    }
                }
                week.forEachIndexed { col, date ->
                    if (date.year != block.monthFirst.year ||
                        date.monthValue != block.monthFirst.monthValue
                    ) {
                        return@forEachIndexed
                    }
                    drawDayCell(
                        day = date,
                        col = col,
                        rowY = rowYDp * density.density,
                        cellW = cellPx,
                        cellH = rowPx,
                        m = metrics,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        measurer = measurer,
                        textLayouts = textLayouts,
                        fontScale = fontScale,
                        primary = scheme.primary,
                        onPrimary = scheme.onPrimary,
                        onSurface = scheme.onSurface,
                        onSurfaceVariant = scheme.onSurfaceVariant,
                        hasLunar = showLunar && metrics.lunarAlpha > 0.01f,
                        t = 1f,
                    )
                }
            }
            ScrollProf.blockDraw(
                key = block.key,
                ns = System.nanoTime() - probeStartNs,
                misses = textLayouts.misses - probeMissBase,
            )
        }
        if (accessibilityEnabled) {
            CalendarDayA11yLayer(
                block = block,
                weeks = weeks,
                metrics = metrics,
                selectedKey = selectedKey,
                todayKey = todayKey,
                flags = flags,
                onDayTap = onDayTap,
            )
        }
    }
}

/**
 * 无障碍开启时，在一个月份块上叠 35–42 个**透明**的逐日节点。
 *
 * 复用周视图那一套 [CalendarDayCell]（`drawVisuals = false`）：节点的
 * `contentDescription`（日期 · 今天/选中 · 有日记/无日记）、`Role.Button`、点击动作
 * 与周视图完全同源；视觉仍是下面那张 Canvas。只在该模式下付出节点成本。
 */
@Composable
private fun CalendarDayA11yLayer(
    block: MonthFlowLayout.Block,
    weeks: List<List<java.time.LocalDate>>,
    metrics: CalendarLayout.DayMetrics,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    onDayTap: (java.time.LocalDate, Int) -> Unit,
) {
    val fontScale = LocalDensity.current.fontScale
    val todayLabel = stringResource(R.string.index_today)
    val selectedLabel = stringResource(R.string.index_selected)
    val hasDiaryLabel = stringResource(R.string.index_has_diary)
    val noDiaryLabel = stringResource(R.string.index_no_diary)
    val dayFontSize = DynamicTypeMetrics.calendarSp(metrics.dayFont.dp, fontScale)
    val lunarFontSize = DynamicTypeMetrics.calendarSp(metrics.lunarFont.dp, fontScale)
    val circleDiameter = metrics.circleDiameter.dp
    val diaryMarkWidth = minOf(20f, metrics.cellW * 0.5f).dp

    Column(modifier = Modifier.fillMaxSize()) {
        weeks.forEachIndexed { row, week ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(metrics.cellH.dp),
            ) {
                week.forEach { date ->
                    val inMonth = date.year == block.monthFirst.year &&
                        date.monthValue == block.monthFirst.monthValue
                    if (!inMonth) {
                        // 相邻月日期不在本块的点击路径里（与 Canvas 的绘制规则一致）
                        Box(modifier = Modifier.weight(1f).fillMaxHeight())
                        return@forEach
                    }
                    val dayKey = DateUtil.dayKeyOf(date)
                    CalendarDayCell(
                        date = date,
                        dayKey = dayKey,
                        drawVisuals = false,
                        showLunar = metrics.lunarAlpha > 0.01f,
                        isSelected = dayKey == selectedKey,
                        isToday = dayKey == todayKey,
                        hasDiary = flags.contains(dayKey),
                        todayKey = todayKey,
                        dayFontSize = dayFontSize,
                        lunarFontSize = lunarFontSize,
                        circleDiameter = circleDiameter,
                        diaryMarkWidth = diaryMarkWidth,
                        enabled = true,
                        todayLabel = todayLabel,
                        selectedLabel = selectedLabel,
                        hasDiaryLabel = hasDiaryLabel,
                        noDiaryLabel = noDiaryLabel,
                        onClick = { onDayTap(date, row) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/**
 * 读 `AccessibilityManager.isEnabled`，并在系统开关变化时重组。
 *
 * Compose 的 `LocalAccessibilityManager` 只暴露了"推荐超时时长"，没有 `isEnabled`，
 * 所以这里直接听平台的两个回调（无障碍服务 / 触摸探索）。
 */
@Composable
private fun rememberAccessibilityEnabled(): Boolean {
    val context = LocalContext.current
    val manager = remember(context) {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
    }
    var enabled by remember(manager) { mutableStateOf(manager?.isEnabled == true) }
    DisposableEffect(manager) {
        if (manager == null) return@DisposableEffect onDispose { }
        val stateListener = AccessibilityManager.AccessibilityStateChangeListener { isEnabled ->
            enabled = isEnabled
        }
        val touchListener = AccessibilityManager.TouchExplorationStateChangeListener {
            // 触摸探索开关不改变 isEnabled，但可能单独切换（TalkBack 刚开/刚关）
            enabled = manager.isEnabled
        }
        manager.addAccessibilityStateChangeListener(stateListener)
        manager.addTouchExplorationStateChangeListener(touchListener)
        onDispose {
            manager.removeAccessibilityStateChangeListener(stateListener)
            manager.removeTouchExplorationStateChangeListener(touchListener)
        }
    }
    return enabled
}

/** 空闲时"向两侧各补挂一块"的节奏（ms）。太密会把几块的重录挤在连续几帧里。 */
private const val IDLE_PREFETCH_STEP_MS = 250L

/** 整块绘制路径的文本测量缓存（日号 1..31 + 农历，覆盖相邻月足够）。 */
private const val BLOCK_TEXT_CACHE = 512

/**
 * 钉住层：**大标题槽 + 星期栏**。月视图与月↔周 morph **共用这一份排版**。
 *
 * ⚠️ 不要再各写一遍（一边真 composable、一边 Canvas）。2026-09-27 的实测缺陷正是这个：
 * morph 那侧把星期栏画在自己的 Canvas 里、而且整块被裁在内容区之下（`clipRect` 的顶边
 * 恰好压在星期栏的下沿），于是**整段动画里星期栏一次都没画出来**，收尾换回真图层时
 * 才"突然闪现"。用户的原话："月视图切换到周视图的时候，让月视图下的星期栏也一并向上，
 * 移动到周视图的星期栏位置，正好是重合的。"
 *
 * 现在两个形态共用这一个 composable，morph 只负责把整条**平移** `p × 标题槽高`：
 * 大标题滑出、星期栏正好落到周视图的位置（0）。
 *
 * @param titleAlpha 只有**大标题**淡出（星期栏不淡）—— 与 iOS `MonthWeekMorphView` 一致。
 * @param drawBackground 是否画不透明底色。月视图 / 月↔周 morph 必须画（它是"内容区上边界"）；
 *   年↔月 morph **不画** —— 那一层底下是正在淡入的年历，压一条底色会把年历顶部切掉。
 */
@Composable
internal fun MonthPinnedHeader(
    title: String,
    widthDp: Float,
    titleHeight: Float,
    weekdayHeight: Float,
    weekStart: String,
    titleAlpha: () -> Float = { 1f },
    modifier: Modifier = Modifier,
    drawBackground: Boolean = true,
) {
    val fontScale = LocalDensity.current.fontScale
    Column(
        modifier = modifier
            .width(widthDp.dp)
            // 不透明底色：morph 里上移时它就是"内容区的上边界"，
            // 往上滑的行 / 小标题会从这里被盖住（与月视图里被钉住层盖住同构）。
            .then(
                if (drawBackground) {
                    Modifier.background(MaterialTheme.colorScheme.surface)
                } else {
                    Modifier
                },
            ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(titleHeight.dp)
                .graphicsLayer { alpha = titleAlpha() },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = title,
                modifier = Modifier.padding(horizontal = 20.dp),
                fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.display, fontScale),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        CalendarWeekdayHeader(
            weekStart = weekStart,
            modifier = Modifier.height(weekdayHeight.dp),
        )
    }
}

/** 右缘的滚动指示条（对应 iOS 的 `scrollIndicator`：拖动时出现、松手 0.7s 后淡出）。 */
@Composable
private fun BoxScope.FlowScrollIndicator(
    scroll: FlowScroll,
    layout: MonthFlowLayout,
    viewportH: Float,
) {
    var show by remember { mutableStateOf(false) }
    LaunchedEffect(scroll.dragging) {
        if (scroll.dragging) {
            show = true
        } else {
            delay(700)
            show = false
        }
    }

    val alpha by animateFloatAsState(if (show) 0.35f else 0f, tween(180))
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    Box(
        modifier = Modifier
            .align(Alignment.TopEnd)
            .padding(end = 2.dp)
            .graphicsLayer {
                this.alpha = alpha
                val maxOff = layout.maxOffset(viewportH)
                val h = max(36f, viewportH * viewportH / max(layout.contentH, 1f))
                val t = if (maxOff > 0f) (scroll.offsetDp / maxOff).coerceIn(0f, 1f) else 0f
                translationY = (viewportH - h) * t * density
            }
            .width(3.dp)
            .height(48.dp)
            .background(color, RoundedCornerShape(2.dp)),
    )
}

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
private suspend fun PointerInputScope.flowDrag(
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
        ScrollProf.start()
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
private suspend fun animateOffset(
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

// --------------------------------------------------------------------------- morph 源构造

/** 把当前可见的行 / 小标题冻结成 morph 源（对应 iOS 的 `morphSource`）。 */
private fun morphSource(
    layout: MonthFlowLayout,
    offsetDp: Float,
    tappedBlockIndex: Int,
    tappedRow: Int,
    titleHeight: Float,
    weekdayHeight: Float,
    viewportH: Float,
    heightDp: Float,
    labelBandH: Float,
    weekStart: String,
    density: Float,
    /**
     * 各块 / 各小标题的**实测**位置。
     *
     * ⚠️ 冻结源里的 y **必须**优先取它。按公式反算在 Compose 上会差（父层
     * `Modifier.offset { }` 的整像素取整、行高的 0.5dp 量化、启动期换过一次布局），
     * 实测能差到 ~200dp —— 用户报的"点下去先跳一下"（"跳上去"）就是它。
     * 实测值缺失（首帧还没放置完）时才退回公式。
     */
    geometry: FlowGeometryProbe? = null,
): MonthFlowMorphSource {
    val visible = layout.visibleBlocks(offsetDp, viewportH, margin = 0f)
    val topIndex = layout.dominantBlockIndex(offsetDp, viewportH)
    val topKey = layout.blocks.getOrNull(topIndex)?.key ?: 0
    val rows = ArrayList<MonthFlowMorphSource.Row>()
    val labels = ArrayList<MonthFlowMorphSource.Label>()
    var selected = 0
    visible.forEach { block ->
        val blockTopDp = geometry?.blockTopDp(block.index, density)
            ?: (block.top - offsetDp + titleHeight + weekdayHeight)
        labels.add(
            MonthFlowMorphSource.Label(
                monthFirst = block.monthFirst,
                key = block.key,
                // 实测的**顶边**；退回公式时也用"顶边"那个式子：
                //     `.offset(y = -(labelBandH + LABEL_TIGHT_GAP))`（见 `MonthFlowBlock`）
                top = geometry?.labelTopDp(block.key, density)
                    ?: (blockTopDp - (labelBandH + CalendarLayout.LABEL_TIGHT_GAP)),
            ),
        )
        CalendarGrid.displayedWeeks(block.monthFirst, weekStart).forEachIndexed { i, week ->
            if (block.index == tappedBlockIndex && i == tappedRow) selected = rows.size
            rows.add(
                MonthFlowMorphSource.Row(
                    week = week,
                    // 实测块顶 + 行号 × 行高：块内每一行是同一张 Canvas 按 `i × rowH` 画的，
                    // 所以块顶量准了，行位置就是准的。
                    top = blockTopDp + i * layout.rowH,
                    anchorMonth = block.monthFirst,
                    showDivider = !(block.index == 0 && i == 0),
                ),
            )
        }
    }
    return MonthFlowMorphSource(
        rows = rows,
        labels = labels,
        selectedIndex = selected.coerceIn(0, max(0, rows.size - 1)),
        topMonthKey = topKey,
        rowH = layout.rowH,
        viewportTop = titleHeight + weekdayHeight,
        height = heightDp,
    )
}

// --------------------------------------------------------------------------- 月 ↔ 周 morph

/**
 * 月↔周 morph：把**冻结下来的连续月历流**里选中那一行抬到星期栏下方、其余行移出屏幕，
 * 同时当日内容从下面顶上来。
 *
 * 行的位置不再由「第几行 × 行高」推出来，而是直接用冻结时的绝对 y —— 月视图改成连续
 * 滚动之后，按下那一刻屏幕上的行可能停在任意位置（半行、跨月），只有照抄这些数字，
 * 切换那一帧才不会跳。反方向（周→月）用同一份源、同一条进度曲线倒放，
 * 于是那一行会**回到它原来的位置**（用户：「从哪来到哪去」）。
 *
 * ⚠️ 这里**不在组合期读进度**：行的位置 / 透明度全在 `graphicsLayer` 的延迟 lambda 里
 * 算，所以 600ms 的 morph 期间不重组任何日期格（只让 layer 失效）。
 */
@Composable
fun MonthWeekMorph(
    progress: () -> Float,
    source: MonthFlowMorphSource,
    widthDp: Float,
    heightDp: Float,
    weekStart: String,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    titleText: (LocalDate) -> String,
    labelText: (LocalDate) -> String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
    content: @Composable () -> Unit,
) {
    // ============================================================================
    // **分层结构与 iOS `MonthWeekMorphView` 逐条对应**（2026-09-27 第二次重写）
    //
    //   ZStack:                                   （→ Compose 里的 Box 叠层）
    //     ① 大标题        .offset(y: −p × titleH).opacity(1 − 2p)   ← 真实 composable
    //     ② 星期栏        .offset(y: lerp(titleH, 0, p))            ← 真实 composable
    //     ③ 行 + 小标题    裁在 clipTop（= 星期栏底边）以下
    //     ④ 当日内容      挂在选中行下面，淡入
    //
    // ⚠️ 上一版把 ①② 也画进 ③ 那张 Canvas、而且跟着一起裁在 `clipTop` 以下 —— 而
    // `clipTop` 恰好等于星期栏的**下沿**，于是大标题与星期栏在整段动画里**一次都没画
    // 出来**，收尾换回真图层时才一起冒出来。用户的原话："顶栏的绘制有问题，导致最后会
    // 突然闪现出来"、"让月视图下的星期栏也一并向上，移动到周视图的星期栏位置，正好是
    // 重合的"。iOS 里 ①② 本来就在裁剪容器**之外**，这一版照着改回来。
    //
    // 现在 ①② 用**与月视图钉住层同一个 composable**（`MonthPinnedHeader`），整条按
    // `p × titleH` 上移：`p = 0` 与月视图逐像素一致，`p = 1` 星期栏正好落在周视图的
    // 位置上（y = 0），两个方向都不跳。
    // ============================================================================
    val density = LocalDensity.current
    val densityF = density.density
    val fontScale = density.fontScale
    val measurer = rememberTextMeasurer(cacheSize = 2048)
    val textLayouts = MorphTextLayouts.shared

    val rowH = source.rowH
    val headH = CalendarLayout.WEEKDAY_HEADER_H
    val titleH = source.viewportTop - headH
    val mMetrics = remember(widthDp, rowH, showLunar) {
        CalendarLayout.flowMetrics(widthDp, rowH, showLunar)
    }
    val wMetrics = remember(widthDp, showLunar) {
        CalendarLayout.weekMetrics(widthDp, showLunar)
    }
    val cellW = CalendarLayout.monthCellW(widthDp)

    val scheme = MaterialTheme.colorScheme
    val todayMonthKey = remember(todayKey) {
        DateUtil.parseDayKey(todayKey)?.let { CalendarGrid.monthKeyOf(it) } ?: 0
    }
    val title = remember(source.topMonthKey, titleText) {
        titleText(CalendarGrid.monthFirstOfMonthKey(source.topMonthKey))
    }
    // 选中行在冻结坐标里的顶：其余行的"让位"、小标题的位移、以及**当日内容挂在哪**
    // 都以它为界（对应 iOS 的 `selectedTop` / `selectedY`）。
    val selectedTop = source.selectedRow?.top ?: source.viewportTop

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            // 与 iOS 那个 `.clipped()` 同：钉住头上移时不许画到顶栏（胶囊那一行）上去。
            // 它同时兜住了"往上滑出的小标题"：越过日历区上沿就往回裁掉。
            .clipToBounds()
            .clearAndSetSemantics { },
    ) {
        // ---- ③ 各行让位（Canvas，裁在星期栏底边以下） ----
        Canvas(modifier = Modifier.fillMaxSize()) {
            val p = progress()
            // 裁切窗的顶边 = **星期栏的底边**：位移直接作用在 y 上。
            val clipTop = CalendarLayout.lerp(source.viewportTop, headH, p) * densityF
            clipRect(top = clipTop, bottom = size.height) {
                // 本月各行的让位动画
                //
                // 用户 2026-09-27 指出（我上一版偷懒只留了当周）：
                // "不是只有当周才有动画，实际上当周以上都要向上移动，以下日期向下逐渐淡出"。
                // 与 iOS 的 `MonthWeekMorph` 同一条：
                //   · 选中行    —— 从**冻结时的真实位置**走到星期栏下沿，行高/字号同步插值；
                //   · 上方各行  —— 一起**向上**滑出（`top − p × (top + rowH)`）；
                //   · 下方各行  —— **向下**滑出（`top + p × (height − top)`）并逐渐淡出。
                //
                // ⚠️ **不按"周首日"去重**（曾经去重过，那是错的）。
                //
                // `displayedWeeks` 会让**跨月那一周**在相邻两个月里各出现一次 —— 但这两条
                // 并不是"同一条周画两遍"：每一条只画**自己那个月**的那几天，位置也各是
                // 自己那一块的行（9 月最后一行画 28~30、10 月第一行画 1~4）。
                // 去重会把 10 月那一行整条吃掉：周→月时它要到最后才"突然跳出来"
                //（用户 2026-09-27："下一月的第一行总是会在动画之后突然跳出来"）。
                val selectedWeekFirst = source.selectedRow?.week?.first()
                val selectedAnchor = source.selectedRow?.anchorMonth
                // 选中周里"另一半"（相邻月那几天）在**它自己月份里的那一行** ——
                // iOS `adjacentShifts` 用它算那几天该从哪儿来、该往哪儿去。
                val adjacentIndex = source.rows.indexOfFirst {
                    it.anchorMonth != selectedAnchor && it.week.first() == selectedWeekFirst
                }
                val adjacentRow = source.rows.getOrNull(adjacentIndex)
                source.rows.forEachIndexed { i, row ->
                    // ⚠️ **不按"属于被点中的那个月"过滤**（用户 2026-09-27：
                    // "周到月的时候，只有本月的日期有动画，但是实际上屏幕内还有其他月的日期，
                    //  也应该有动画"）。屏幕里能看到的行都属于"让位"的一部分：
                    // 选中周之上的全部向上移出、之下的全部向下淡出 —— 相邻月那些行同理。
                    val isSelected = i == source.selectedIndex
                    val rowY: Float
                    val rowAlpha: Float
                    val rowMetrics: CalendarLayout.DayMetrics
                    var adjacentY: Float? = null
                    if (isSelected) {
                        rowY = CalendarLayout.lerp(row.top, headH, p)
                        rowAlpha = 1f
                        rowMetrics = mMetrics.lerp(wMetrics, p)
                        // 选中周里属于相邻月的那几天：**各自**从"自己月份里的那一行"
                        // 走到周条里（iOS `adjacentShifts`）。
                        //
                        //   · `away = smoothstep(1 − p)`：`p = 0`（月视图）时为 1
                        //     ⇒ 位移 = 那一行与选中行的差 ⇒ 正好落在**它自己那一行**上
                        //     （那一刻它自己那一条也画在同一位置，逐像素重合、看不出两份）；
                        //   · `p = 1`（周视图）时为 0 ⇒ 落在周条的格子里。
                        // 用户 2026-09-27："当选中的日期是两个月之间的同一周的情况时，
                        // 两个月的这一周日期分别计算动画，然后分别移动到周视图的最终位置。"
                        if (adjacentRow != null) {
                            val away = CalendarLayout.smoothstep(1f - p)
                            adjacentY = rowY + (adjacentRow.top - selectedTop) * away
                        }
                    } else {
                        rowY = if (row.top < selectedTop) {
                            // 上方：向上滑出
                            row.top - p * (row.top + rowH)
                        } else {
                            // 下方：向下滑出，把位置让给当日内容
                            row.top + p * (heightDp - row.top)
                        }
                        rowAlpha = 1f - CalendarLayout.clamp01(p * 1.4f)
                        rowMetrics = mMetrics
                    }
                    if (rowAlpha <= 0.01f) return@forEachIndexed
                    drawWeekRow(
                        week = row.week,
                        anchorMonth = row.anchorMonth,
                        rowY = rowY * densityF,
                        metrics = rowMetrics,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        alpha = rowAlpha,
                        showDivider = row.showDivider,
                        measurer = measurer,
                        textLayouts = textLayouts,
                        fontScale = fontScale,
                        primary = scheme.primary,
                        onPrimary = scheme.onPrimary,
                        onSurface = scheme.onSurface,
                        onSurfaceVariant = scheme.onSurfaceVariant,
                        dividerColor = scheme.outlineVariant,
                        hasLunar = showLunar,
                        t = 1f,
                        // 相邻月那几格：只在选中行上传（其余行的相邻月由**它们自己那一块**
                        // 的行负责画，重复画就是叠字）。
                        adjacentRowY = adjacentY?.let { it * densityF },
                        // ⚠️ 选中周"另一半"那一行**只画分割线、不画日期**：那几天的日期由
                        // 选中行里那份负责（它从这一行的位置走到周条里）。两边都画的话，
                        // 同一天会有两个动画、中段并不重合（用户 2026-09-27）。
                        // 分割线仍归它自己 —— 那是这一行的顶边，跟着它一起让位。
                        drawCells = i != adjacentIndex,
                    )
                }
            }
        }

        // ---- ③b 小标题：**真实 composable**（与月视图 `MonthFlowBlock` 同一份排版，
        //      所以交班那一帧逐像素一致），位置用冻结时的实测值（见 `FlowGeometryProbe`）。
        //
        // 用户 2026-09-27："月到周的时候，小标题不动"。原来它位置钉死、只淡出，
        // 而它上下的日期都在移动 —— 看着就像"少动了一个元素"。
        // 现在按**与日期行同一条规则**位移（它是本月的第一行上方那一块，同属"让位"）：
        //   · 小标题在选中周之上 ⇒ 向上移出；
        //   · 在选中周之下 ⇒ 向下移出。
        // 往上滑出屏幕的那些由钉住层的不透明底色 + 根节点裁切兜住（与月视图同一条边界）。
        //
        // ⚠️ **透明度挂在这一层（尺寸确定的 Box）上，不能挂到 `MonthFlowLabel` 自己身上**：
        // 实测（2026-09-27）外层挂 `alpha < 1` 时那一块会被画成**完全空白**（连纯色背景
        // 都不画），alpha = 1f 才正常；位置（`translationY`）挂在自己身上是好的。
        Box(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = CalendarLayout.clamp01((1f - progress()) * 2f) },
        ) {
            source.labels.forEach { label ->
                MonthFlowLabel(
                    cellW = cellW,
                    column = DateUtil.weekdayIndex(label.monthFirst, weekStart),
                    text = labelText(label.monthFirst),
                    compact = compact,
                    isCurrent = label.key == todayMonthKey,
                    modifier = Modifier.graphicsLayer {
                        val p = progress()
                        translationY = if (label.top < selectedTop) {
                            label.top - p * (label.top + rowH)
                        } else {
                            label.top + p * (heightDp - label.top)
                        } * densityF
                    },
                )
            }
        }

        // ---- ①② 钉住层：与月视图同一个 composable，整条按 `p × titleH` 上移 ----
        MonthPinnedHeader(
            title = title,
            widthDp = widthDp,
            titleHeight = titleH,
            weekdayHeight = headH,
            weekStart = weekStart,
            titleAlpha = { 1f - CalendarLayout.clamp01(progress() * 2f) },
            modifier = Modifier.graphicsLayer {
                translationY = -progress() * titleH * densityF
            },
        )

        // ---- ④ 当日内容（日期栏 + 日记预览）：真实 composable，**挂在选中行下面**
        //
        // 与 iOS 的 `contentOffset = selectedY + weekStripH` 同一条：
        // 它**不是**按固定曲线滑，而是始终吊在选中行底下 —— 正方向（月→周）"从下面升上来"，
        // 反方向（周→月）"跟着行一起沉下去"。写成固定 y 的代价实测过：周→月时选中行要往下
        // 走 400+dp 回到它那个月，而内容钉在原地不动 —— 日期标题行会被那一行**穿过去**
        //（用户 2026-09-27："日记预览和日期栏也是有动画的，现在缺失了"）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .graphicsLayer {
                    val selectedY = CalendarLayout.lerp(selectedTop, headH, progress())
                    translationY = (selectedY + CalendarLayout.WEEK_STRIP_H) * densityF
                    alpha = CalendarLayout.clamp01((progress() - 0.3f) / 0.5f)
                },
        ) {
            content()
        }
    }
}
