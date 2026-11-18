package com.cov.justdiary.ui.home

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.Canvas
import androidx.compose.material3.LocalTextStyle
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.hideFromAccessibility
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import java.time.LocalDate
import kotlin.math.abs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// ===========================================================================
// 连续月历流（月视图：竖屏整屏 / 横屏分栏左栏）
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
    // P2-3：用**稳定的函数**传可见性（而不是布尔参数）。这样周态 ↔ 月态切换时，
    // 挂载窗口里的块不会因为参数变化而整窗重组、重新创建 Canvas 图层；
    // 只有绘制阶段会读它，切换只多一次重画。
    val layerVisibleState = rememberUpdatedState(visible)
    val layerVisible: () -> Boolean = remember { { layerVisibleState.value } }
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
                        scroll = scroll,
                        viewportH = viewportH,
                        layerVisible = layerVisible,
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
                        // 后果实测（`-morph-log` 逐帧日志）：跨月时（`range` 一变）已挂载的**每一块**
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

/** 空闲时"向两侧各补挂一块"的节奏（ms）。太密会把几块的重录挤在连续几帧里。 */
internal const val IDLE_PREFETCH_STEP_MS = 250L

/** 整块绘制路径的文本测量缓存（日号 1..31 + 农历，覆盖相邻月足够）。 */
internal const val BLOCK_TEXT_CACHE = 512
