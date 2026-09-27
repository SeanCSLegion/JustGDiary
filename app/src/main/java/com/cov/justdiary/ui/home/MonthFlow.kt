package com.cov.justdiary.ui.home

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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalDensity
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
            .width(cellW.dp),
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
    val labelBandH: Float,
    /** 视口在日历区里的顶（= 标题槽 + 星期栏）。 */
    val viewportTop: Float,
    /** 整块日历区的高度。 */
    val height: Float,
) {
    val selectedRow: Row? get() = rows.getOrNull(selectedIndex)

    /** 冻结时的一行日期。 */
    data class Row(
        val week: List<LocalDate>,
        /** 冻结时这一行在**日历区坐标**里的顶（已含标题槽、星期栏与滚动偏移）。 */
        val top: Float,
        val anchorMonth: LocalDate,
        /** 本块第一行不画分隔线（与流里的判断一致）。 */
        val showDivider: Boolean,
        /**
         * 这一行是否属于**被点中的那个月块**。
         *
         * ⚠️ `visibleBlocks` 会返回 2~4 个块（含预挂载），而月流里相邻月块是**首尾相接**
         * 排的 —— 把它们的行全都塞进 morph 层，就会出现"同一条周被画两遍、数字叠在一起"
         *（用户 2026-09-27："好多日号都重叠起来了"，截图里 `17/18`、`26/26` 这种叠字）。
         * 月↔周 morph 只需要本月这些行（相邻月由真实图层负责）。
         */
        val inSelectedBlock: Boolean,
    )

    /** 冻结时的一个月份小标题。 */
    data class Label(
        val monthFirst: LocalDate,
        /** 月份键（`CalendarGrid.monthKey`）—— morph 里判 `isCurrent` 用。 */
        val key: Int,
        /** 冻结时小标题**底边**在日历区坐标里的 y（紧贴日期数字上方）。 */
        val bottom: Float,
    )
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

    // 预挂载：启动后把"视口外上下各一块"也组合出来。
    // 每一步都会让新挂载的那一块付掉它那份布局代价（实测一块 90~365ms）——
    // 但那是在用户还没滚到那里的时候付的，滚动过程中就不会再顿。
    // ⚠️ 只预挂 1 块：2 块会把启动那一瞬间拉出 500ms 的卡顿（实测），得不偿失。
    var preMount by remember(layout) { mutableStateOf(0) }
    LaunchedEffect(layout) {
        withFrameNanos { }
        preMount = 1
    }

    // 可见块窗口：**只在跨块时**才变，于是滚动过程中这条流不重组
    val range by remember(layout, viewportH) {
        derivedStateOf {
            layout.visibleBlockRange(scroll.offsetDp, viewportH, layout.rowH, preMount)
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
    // 钉住层的透明度 = 可见性。
    // ⚠️ 必须单独判：周态 / 年态下整条流的不透明度是 0（它必须常驻以保住滚动位置），
    // 但钉住层是**另一个** Box，会照旧画在顶上 —— 实测周态里那颗大标题穿在周条后面。
    val headerAlpha: () -> Float = remember(visible) { { if (visible) 1f else HIDDEN_ALPHA } }

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            .clipToBounds(),
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
            for (i in range) {
                val block = layout.blocks.getOrNull(i) ?: continue
                val blockStartNs = System.nanoTime()
                key(block.key) {
                    MonthFlowBlock(
                        block = block,
                        onPlacedNs = { ScrollProf.measure(System.nanoTime() - blockStartNs) },
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
                        onDayTap = { date, row ->
                            // morph 源必须与**屏幕上这一帧**一致：先把惯性停在当前帧，
                            // 再按同一份数字建源（iOS 的 `morphSource` 开头就是 freeze）。
                            scroll.animJob?.cancel()
                            onDayTap(
                                date,
                                morphSource(
                                    layout = layout,
                                    offsetDp = scroll.offsetDp,
                                        tappedBlockIndex = block.index,
                                        tappedRow = row,
                                        titleHeight = titleHeight,
                                        weekdayHeight = weekdayHeight,
                                        viewportH = viewportH,
                                        heightDp = heightDp,
                                    labelBandH = labelBandH,
                                    weekStart = weekStart,
                                ),
                            )
                        },
                        modifier = Modifier
                            .offset {
                                val off = scroll.offsetDp
                                // 把"屏幕上这一帧的偏移"记给 morph 层（普通写，不触发重组）
                                offsetProbe?.value = off
                                IntOffset(0, ((block.top - off) * density).roundToInt())
                            }
                    )
                }
            }

            // 滚动指示条：贴在右缘（iOS 的 `scrollIndicator`）
            FlowScrollIndicator(scroll = scroll, layout = layout, viewportH = viewportH)
        }

        // ---------------- 钉住层（画在最后 = 最上面） ----------------
        MonthFlowHeader(
            title = titleText(topBlock.monthFirst),
            widthDp = widthDp,
            titleHeight = titleHeight,
            weekdayHeight = weekdayHeight,
            weekStart = weekStart,
            alpha = headerAlpha,
        )
    }
}

/** 一整块（一个月）：小标题 + 各行日期。 */
@Composable
private fun MonthFlowBlock(
    block: MonthFlowLayout.Block,
    /** 临时探针：本块从开始组合到放置完成（纳秒）。 */
    onPlacedNs: (() -> Unit)? = null,
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
) {
    // 临时探针：组合段 / 布局段分开计时
    val composeStartNs = System.nanoTime()
    val placedNs = composeStartNs
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
            .then(if (onPlacedNs != null) Modifier.onPlaced { onPlacedNs() } else Modifier),
    ) {
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
            modifier = Modifier.onPlaced { ScrollProf.measure(System.nanoTime() - placedNs) },
        )
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
private fun BlockCanvas(
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

    Box(
        modifier = modifier
            // ⚠️ `metrics.cellW` **已经是格宽**（`monthCellW(width) = width / 7`）。
            // 第一版写成 `monthCellW(cellW * 7)` 又除了一次 7、再取整 ⇒ 宽度 0，
            // 整块既画不出来也收不到手势（实测点日期没反应）。
            .width((metrics.cellW * 7f).dp)
            .height((block.rowCount * rowH).dp)
            // 命中：一格一格的坐标换算，避免为 42 个日期各建一个可点节点。
            // 起手 2dp 内不消费事件，日期格的点击与月流的拖动互不打架。
            .pointerInput(block.key, weeks, metrics.cellW, metrics.cellH) {
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
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
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
        }
    }
}

/** 整块绘制路径的文本测量缓存（日号 1..31 + 农历，覆盖相邻月足够）。 */
private const val BLOCK_TEXT_CACHE = 512

/** 钉住层：大月份标题 + 星期栏。**不随滚动移动**，只跟着换字。 */
@Composable
private fun MonthFlowHeader(
    title: String,
    widthDp: Float,
    titleHeight: Float,
    weekdayHeight: Float,
    weekStart: String,
    alpha: () -> Float,
) {
    val fontScale = LocalDensity.current.fontScale
    Box(
        modifier = Modifier
            .width(widthDp.dp)
            .graphicsLayer { this.alpha = alpha() },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(titleHeight.dp),
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
): MonthFlowMorphSource {
    val visible = layout.visibleBlocks(offsetDp, viewportH, margin = 0f)
    val topIndex = layout.dominantBlockIndex(offsetDp, viewportH)
    val topKey = layout.blocks.getOrNull(topIndex)?.key ?: 0
    val rows = ArrayList<MonthFlowMorphSource.Row>()
    val labels = ArrayList<MonthFlowMorphSource.Label>()
    var selected = 0
    visible.forEach { block ->
        labels.add(
            MonthFlowMorphSource.Label(
                monthFirst = block.monthFirst,
                key = block.key,
                bottom = block.top - offsetDp + titleHeight + weekdayHeight -
                    CalendarLayout.LABEL_TIGHT_GAP,
            ),
        )
        CalendarGrid.displayedWeeks(block.monthFirst, weekStart).forEachIndexed { i, week ->
            if (block.index == tappedBlockIndex && i == tappedRow) selected = rows.size
            rows.add(
                MonthFlowMorphSource.Row(
                    week = week,
                    top = block.top + i * layout.rowH - offsetDp + titleHeight + weekdayHeight,
                    anchorMonth = block.monthFirst,
                    showDivider = !(block.index == 0 && i == 0),
                    inSelectedBlock = block.index == tappedBlockIndex,
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
        labelBandH = labelBandH,
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
    val titleH = source.viewportTop - CalendarLayout.WEEKDAY_HEADER_H
    val headH = CalendarLayout.WEEKDAY_HEADER_H
    val rowH = source.rowH
    val density = LocalDensity.current.density
    val fontScale = LocalDensity.current.fontScale
    val mMetrics = remember(widthDp, rowH, showLunar) {
        CalendarLayout.flowMetrics(widthDp, rowH, showLunar)
    }
    val wMetrics = remember(widthDp, showLunar) {
        CalendarLayout.weekMetrics(widthDp, showLunar)
    }
    val cellW = CalendarLayout.monthCellW(widthDp)
    val selectedTop = source.selectedRow?.top ?: source.viewportTop
    // 当日内容顶端的**终态**位置：星期栏（30）+ 周条（80）。morph 收尾换回真实周层时
    // 两边的排版完全一致，所以这里用终态高度做布局、只用位移表达"从下面顶上来"。
    val contentTop = headH + CalendarLayout.WEEK_STRIP_H

    Box(
        modifier = modifier
            .width(widthDp.dp)
            .height(heightDp.dp)
            // morph 层是**纯视觉**的过渡内容：无障碍树里只保留底下那个真实图层
            .clearAndSetSemantics { },
    ) {
        // 大标题：向上移出并淡出
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(titleH.dp)
                .graphicsLayer {
                    val p = progress()
                    translationY = -p * titleH * density
                    alpha = 1f - CalendarLayout.clamp01(p * 2f)
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = titleText(CalendarGrid.monthFirstOfMonthKey(source.topMonthKey)),
                modifier = Modifier.padding(horizontal = 20.dp),
                fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.display, fontScale),
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        // 星期栏：从「标题槽之后」上移到顶（月视图那侧的落点是 `titleH`，不是 `viewportTop`）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(headH.dp)
                .graphicsLayer {
                    translationY = CalendarLayout.lerp(titleH, 0f, progress()) * density
                },
        ) {
            CalendarWeekdayHeader(weekStart = weekStart)
        }

        // 行与小标题：裁在「跟着顶栏移动的边界」以下。
        //
        // 裁剪的做法是「占位 + frame + clipToBounds」：外层整体下移 `clipTop`、内层自己
        // `clipToBounds`，于是**裁切窗跟着顶栏一起走**。⚠️ 不能写成
        // `offset(...).clipToBounds()` —— `offset` 是渲染期平移，`clipToBounds` 会跟着
        // 一起移，等于没裁（iOS 侧为这一条专门取过帧）。
        val clipTop: () -> Float = {
            CalendarLayout.lerp(source.viewportTop, headH, progress())
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(heightDp.dp)
                .graphicsLayer { translationY = clipTop() * density },
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clipToBounds(),
            ) {
                // ⚠️ 小标题画在**日期行之后**（= 在它们上层，但仍在那条当日内容之下）——
                // 与连续流 `MonthFlowBlock` 的顺序一致（那里也是先网格、后小标题）。
                // 原来画在行之前，morph 期间会被移动中的日期行整块盖住，等行让开才露出来，
                // 看着就是"小标题最后才出现"（用户两次提到这一点）。
                //
                // 小标题的强调色（今天所在月 = 加粗 + 主色）**必须与连续流一致**：
                // iOS 的 `MonthFlowLabel` 自己按日期算 `isCurrent`，morph 与流用的是同一个
                // 视图；Android 这边 `isCurrent` 是入参，morph 里漏传 → 交班那一帧小标题
                // 会突然由灰变蓝（用户："月视图下月份小标题你没有做动画"）。
                val todayMonthKey = remember(todayKey) {
                    DateUtil.parseDayKey(todayKey)?.let { CalendarGrid.monthKeyOf(it) } ?: 0
                }
                // ⚠️ **按"周首日"去重**：`CalendarGrid.displayedWeeks` 对相邻两个月都会把
                // **跨月的那一周**算进去（上个月的最后一周 = 下个月的第一周），于是
                // `morphSource` 会把同一条周收两遍 —— 两份行落点相近，同一格的日号与农历
                // 就被画了两遍，看起来就是"日号叠字/重影"（用户 2026-09-27："非常明显有问题，
                // 有重叠"）。实测行清单：`…3:2026-09-21* 4:2026-09-28* 5:2026-09-28 6:2026-10-05…`
                // —— 第 4、5 行是同一条周。
                val drawnWeeks = HashSet<LocalDate>()
                source.rows.forEachIndexed { i, row ->
                    // 只画**被点中那个月**的行（相邻月整块交给真实图层）
                    if (!row.inSelectedBlock) return@forEachIndexed
                    if (!drawnWeeks.add(row.week.first())) return@forEachIndexed
                    val isSelected = i == source.selectedIndex
                    if (isSelected) {
                        // 被点中的那一行要**逐帧改度量**（月格行高 → 周条高度），
                        // 所以单独放一个组合作用域读进度：每帧只重组这 7 个日期格，
                        // 其余行完全不重组。
                        SelectedMorphRow(
                            row = row,
                            mMetrics = mMetrics,
                            wMetrics = wMetrics,
                            progress = progress,
                            clipTop = clipTop,
                            selectedKey = selectedKey,
                            todayKey = todayKey,
                            flags = flags,
                            showLunar = showLunar,
                        )
                    } else {
                        MorphRow(
                            row = row,
                            metrics = mMetrics,
                            progress = progress,
                            selectedTop = selectedTop,
                            clipTop = clipTop,
                            heightDp = heightDp,
                            selectedKey = selectedKey,
                            todayKey = todayKey,
                            flags = flags,
                            showLunar = showLunar,
                            density = density,
                        )
                    }
                }
                source.labels.forEach { label ->
                    MonthFlowLabel(
                        cellW = cellW,
                        column = DateUtil.weekdayIndex(label.monthFirst, weekStart),
                        text = labelText(label.monthFirst),
                        isCurrent = label.key == todayMonthKey,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .graphicsLayer {
                                // 位置**钉在终点**、不加位移（用户拍板："让小标题直接在最终
                                // 位置算了。这样比跳变更好。"）。
                                //
                                // 透明度：`p = 0.5` 以下**全不透明**，往上快速淡出 ——
                                // 也就是"周→月时它一开始就在"（用户："现在周视图切换到月视图
                                // 的时候，小标题还是最后出现的"），"月→周时它很快消失"。
                                // ⚠️ 不能用 iOS 那条 `1 - clamp01(p * 2.2)`：那会让它在
                                // `p < 0.45` 才开始出现、`p < 0.2` 才看得清，别的日期都动完了
                                // 它才冒出来 —— 用户否掉两次的就是这个观感。
                                // （这里原本**多写了一行** `alpha = 1 - clamp01(progress() * 2.2f)`，
                                //   把上面的值覆盖掉了，等于用户否掉的那版一直在生效；
                                //   2026-09-27 清理时删掉。）
                                val pp = progress()
                                alpha = CalendarLayout.clamp01((1f - pp) * 2f)
                                translationY =
                                    (label.bottom - source.labelBandH - clipTop()) * density
                            },
                    )
                }
            }
        }

        // 当日内容：**始终挂在选中行下面**（行底 + 周条高度）。挂在固定曲线上时，
        // 反方向（周→月）行会从内容中间穿过去，日期标题就压在周行上（iOS 实测过）。
        // 布局高度取「终态」（收尾换回真实图层时高度不会变），位移用延迟读取。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // ⚠️ **布局**位置必须是终态的 `contentTop`（= 星期栏 + 周条高度）。
                // 只写 `graphicsLayer.translationY` 是不够的：`graphicsLayer` 只改**绘制**，
                // 布局仍在 y = 0，于是整个当日内容（日期标题 + 空态插画）在 morph 全程
                // 高了 110dp，标题压在周条上 —— 用户报的"周视图的日期栏出现时机和位置问题"。
                .offset(y = contentTop.dp)
                .height((heightDp - contentTop).coerceAtLeast(0f).dp)
                .graphicsLayer {
                    val p = progress()
                    // 内容顶 = 选中行当前所在的位置（`selectedY + 周条高度`），
                    // 而它的**布局**位置已经是 `contentTop`（终态），所以位移取两者之差。
                    translationY =
                        (CalendarLayout.lerp(selectedTop, headH, p) - headH) * density
                    alpha = CalendarLayout.clamp01((p - 0.3f) / 0.5f)
                },
        ) {
            content()
        }
    }
}

/** morph 里**被点中的那一行**：度量逐帧插值（月格行高 → 周条高度）。 */
@Composable
private fun SelectedMorphRow(
    row: MonthFlowMorphSource.Row,
    mMetrics: CalendarLayout.DayMetrics,
    wMetrics: CalendarLayout.DayMetrics,
    progress: () -> Float,
    clipTop: () -> Float,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
) {
    // 只有这一个组合作用域在组合期读进度（下面那条注释解释了为什么其余行不必）
    val p = progress()
    val density = LocalDensity.current.density
    val metrics = remember(p, mMetrics, wMetrics) { mMetrics.lerp(wMetrics, p) }
    CalendarDayGrid(
        // ⚠️ 必须 remember：这一行是 morph 里唯一在**组合期**逐帧重组的（行高/字号要跟着 p 走），
        // 而 `List` 在 Compose 里按实例比较 —— 每帧新建 `listOf(...)` 会让
        // `CalendarDayGrid` 连同 7 个格子每帧全量重组（各自 4 次 stringResource + 农历 + 标题拼接）。
        rows = remember(row.week) { listOf(row.week) },
        anchorMonth = row.anchorMonth,
        metrics = metrics,
        selectedKey = selectedKey,
        todayKey = todayKey,
        flags = flags,
        onDayClick = {},
        // 被点中的这一行就是正在变成周条的那一行：它要带相邻月的日期（周条一直这么画），
        // 并按进度淡入 —— `p = 0` 时它们完全透明，与月视图那一帧逐像素一致。
        showAdjacent = true,
        adjacentAlpha = { p },
        showLunar = showLunar,
        // ⚠️ 分割线：morph 层是**一行一个 `CalendarDayGrid`**，所以"这一行有没有分割线"
        // 必须走 `dividerAboveFirst`（`CalendarDayGrid` 里 `row > 0` 对单行恒为假）。
        // 少了这一条，月↔周 morph 的第一帧所有分割线就整条消失 —— 用户报的
        // "一些分割线的动画还是不对"就是它。
        showDividers = true,
        dividerAboveFirst = row.showDivider,
        // ⚠️ **必须与月流 / 周条走同一条 Canvas 路径**（用户 2026-09-27 报"月↔周切换时
        // 农历明显有跳动"）。morph 原先用真组件 `CalendarDayCell`，它那套排版是
        // `Column(居中, +3dp) [圆, 农历(-6dp), 标记]` —— 与新的"整组居中（日号→标记→农历）"
        // 差着整整一段，于是交班（morph ↔ 真实图层）那一帧农历会跳 ~36dp。
        canvasCells = true,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val y = CalendarLayout.lerp(row.top, CalendarLayout.WEEKDAY_HEADER_H, p)
                translationY = (y - clipTop()) * density
            },
    )
}

/** morph 里**其余的行**：位置与透明度全部在延迟 lambda 里读进度（不触发重组）。 */
@Composable
private fun MorphRow(
    row: MonthFlowMorphSource.Row,
    metrics: CalendarLayout.DayMetrics,
    progress: () -> Float,
    selectedTop: Float,
    clipTop: () -> Float,
    heightDp: Float,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    density: Float,
) {
    CalendarDayGrid(
        rows = listOf(row.week),
        anchorMonth = row.anchorMonth,
        metrics = metrics,
        selectedKey = selectedKey,
        todayKey = todayKey,
        flags = flags,
        onDayClick = {},
        // 其余行和月视图一样只画本月：月份边界那一周由两个月各画自己那一半，
        // 否则同一批日期会在两行里各画一遍（iOS 的注释专门记过这条）。
        showAdjacent = false,
        showLunar = showLunar,
        showDividers = true,
        dividerAboveFirst = row.showDivider,
        canvasCells = true,
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                val p = progress()
                val y = if (row.top < selectedTop) {
                    // 选中行**上方**的行：一起往上滑出屏幕
                    row.top - p * (row.top + metrics.cellH)
                } else {
                    // 下方各行：往下滑出去，把位置让给当日内容
                    row.top + p * (heightDp - row.top)
                }
                translationY = (y - clipTop()) * density
                alpha = 1f - CalendarLayout.clamp01(p * 1.4f)
            },
    )
}
