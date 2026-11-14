package com.cov.justdiary.ui.home

import android.content.Context
import android.view.accessibility.AccessibilityManager
import com.cov.justdiary.R
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.TypeSize
import java.time.LocalDate
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/**
 * 月流的绘制层：块窗口、日期画布、钉住的标题栏与滚动指示器。
 *
 * 从 [MonthFlow] 拆出；组合结构与主入口保持原样，只按"画什么"分组。
 */

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
internal fun blockModifier(
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
internal fun MonthFlowBlock(
    block: MonthFlowLayout.Block,
    scroll: FlowScroll,
    viewportH: Float,
    /**
     * 这一层当前是否可见。传**函数**而不是布尔：周态/年态下它恒为 false，
     * 但月流仍要保持挂载（滚动位置不能丢）。如果按布尔参数切换，切回月态时
     * 挂载窗口里的 8 块会同时重组并新建 Canvas 图层 —— 真机实测那一帧 41ms。
     * 现在只在**绘制阶段**读它，切换只让图层重画一次，不重建组合/图层。
     */
    layerVisible: () -> Boolean = { true },
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
    val weeks = remember(block.key, weekStart) {
        CalendarGrid.displayedWeeks(block.monthFirst, weekStart)
    }
    val metrics = remember(widthDp, rowH, showLunar) {
        CalendarLayout.flowMetrics(widthDp, rowH, showLunar)
    }
    val cellW = CalendarLayout.monthCellW(widthDp)
    // P2-3：只画**可能可见的行**。整块 5~6 行的 Canvas 命令录制是"新月份首帧 17~19ms"
    // 的主要来源，而屏幕上通常只有 1~2 行。这里按块顶 / 视口高度算出行区间：
    // - 用 `derivedStateOf` 读偏移，只有"可见行变了"才重组（滚动过程中约每行一次）；
    // - 不可见的块直接不挂 `BlockCanvas`，于是周→月回来时不会一次重录挂载窗口里的 8 块。
    val visibleRows by remember(block, scroll, viewportH, rowH) {
        derivedStateOf {
            val blockTop = block.top
            val blockBottom = blockTop + block.rowCount * rowH
            val viewportTop = scroll.offsetDp
            val viewportBottom = viewportTop + viewportH
            if (blockBottom <= viewportTop || blockTop >= viewportBottom) {
                IntRange.EMPTY
            } else {
                val first = ((viewportTop - blockTop) / rowH)
                    .toInt().coerceIn(0, block.rowCount - 1)
                val last = ((viewportBottom - blockTop) / rowH)
                    .toInt().coerceIn(0, block.rowCount - 1)
                first..last
            }
        }
    }
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
        // 月流层不可见时由 `layerVisible` 在绘制阶段直接 return，不压 alpha 硬画
        // （只压 alpha 会与 morph 层叠成重影）。
        BlockCanvas(
            block = block,
            weeks = weeks,
            metrics = metrics,
            visibleRows = visibleRows,
            layerVisible = layerVisible,
            rowH = rowH,
            showLunar = showLunar,
            selectedKey = selectedKey,
            todayKey = todayKey,
            flags = flags,
            onDayTap = onDayTap,
            modifier = Modifier,
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
    }
}

/**
 * 一个月块的**整块绘制**版本（连续月历流的快路径）。
 *
 * ## 为什么要这么做
 *
 * 真机实测（MEIZU 21 / 120Hz）：正常帧 8ms（预算内），但**新的一个月首次
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
    /** 只画这些行（含两端）；空表示整块不在视口内。测试/预览默认全画。 */
    visibleRows: IntRange = 0 until Int.MAX_VALUE,
    /** 月流层是否可见；在**绘制阶段**读，避免切换形态时重建图层。 */
    layerVisible: () -> Boolean = { true },
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
                            if (!layerVisible()) return@detectTapGestures
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
            // 月流层不可见（周态/年态）时一笔都不画；这里读的是函数，切换只失效绘制。
            if (!layerVisible() || visibleRows.isEmpty()) return@Canvas
            val cellPx = metrics.cellW * density.density
            val rowPx = metrics.cellH * density.density
            weeks.forEachIndexed { row, week ->
                if (row !in visibleRows) return@forEachIndexed
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
        if (accessibilityEnabled && layerVisible()) {
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
internal fun BoxScope.FlowScrollIndicator(
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
