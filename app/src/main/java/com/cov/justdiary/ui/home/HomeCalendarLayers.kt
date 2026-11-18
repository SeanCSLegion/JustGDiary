package com.cov.justdiary.ui.home

import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.service.DateFormats
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 首页的周层与年层：周条、翻页视口、年历层。
 *
 * 从 [HomeScreen] 拆出；三态 morph 的端点几何由 [CalendarLayout] 提供。
 */

/**
 * 年历层：年态（以及年 morph 期间）盖在连续月历流之上。
 *
 * 与月历一样是**整屏视图**（竖屏占满、分栏时占左栏），因此几何直接取日历区那一块
 * —— 两个端点分别与各自真实图层同源，收尾才不跳。
 *
 * `yearProgress` 传的是**读取器**而不是值：传值 = 组合期读 state = 动画每一帧都重组
 * 这一层并让 Canvas 重画（12 张迷你月、约 500 次文本测量）。传 lambda 之后，
 * alpha / scale 在 `graphicsLayer {}` 的延迟 lambda 里算 —— 那只让 layer 失效，
 * **Canvas 不需要重画**（平移 / 缩放 / 透明都是 layer 属性）。
 */
@Composable
internal fun YearLayer(
    year: Int,
    selectedMonth: Int,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    weekStart: String,
    showLunar: Boolean,
    yearHeight: Float,
    yearWidth: Float,
    yearProgress: () -> Float,
    dateFormats: DateFormats?,
    onMonthClick: (Int) -> Unit,
    /** 年↔月 morph 期间不画这一张迷你月（morph 层正在同一矩形里画它，见 `YearCanvas`）。 */
    hiddenMonth: Int? = null,
    /**
     * ⚠️ 这一层**只在年态静止时挂载**（`mode == YEAR && !yearMorphing`）。
     *
     * 不要改成"常驻 + `alpha = 0`"：透明不阻止命中测试，`YearCanvas` 里那 12 个
     * 铺满日历区的卡片点击层会把月态的拖动事件接走（真机实测拖动完全失效），
     * 而且年态翻年时它会跟着放大层一起参与绘制、底部露残留。
     * 它首次排版（字形加载 + 12 张迷你月文本整形）约 230~390ms，要预热就另找
     * 不参与命中测试的办法。
     */
) {
    val context = LocalContext.current
    // 与进度无关的那部分几何：算一次就够（它只跟尺寸和选中月有关）
    val card = remember(yearHeight, yearWidth, selectedMonth) {
        CalendarLayout.yearCardRect(selectedMonth, yearWidth, yearHeight)
    }
    YearCanvas(
        year = year,
        selectedMonth = selectedMonth,
        selectedKey = selectedKey,
        todayKey = todayKey,
        flags = flags,
        weekStart = weekStart,
        hiddenMonth = hiddenMonth,
        yearTitle = dateFormats?.year(year) ?: context.getString(R.string.date_year, year),
        zodiacLabel = dateFormats?.yearZodiac(year).orEmpty(),
        modifier = Modifier
            // ⚠️ 必须与月历用**同一个限宽**：原来这里是 `fillMaxWidth()`，只有月历层被限到
            // `calendarW`。宽窗口下年历会拿到 ~703dp，卡片被拉成 ~220dp 的空盒，
            // 而且年历与月历横向错位 ~31dp —— morph 的锚点也就对不上了（规范 §2.2/§5.2）。
            .width(yearWidth.dp)
            .height(yearHeight.dp)
            .graphicsLayer {
                // 进度在 layer 里读（延迟）：动画每一帧只让 layer 失效，Canvas 不重画
                val p = yearProgress()
                // 年层的淡入曲线与月层的淡出曲线**互补**（月层用 `1 - yearOpacity`），
                // 两者在整段区间里和为 1，不会"双重曝光"、也不会"前 25% 什么都不见"。
                alpha = CalendarLayout.yearLayerOpacity(p)
                // 1.6 → 1：从"源卡片炸开"的起点略大一点，收尾正好与真实年历同尺寸。
                val k = 1f + (1f - p) * 0.6f
                scaleX = k
                scaleY = k
                // 缩放锚点 = **源月卡片（选中月那一张）的中心**，不是整层的中心。
                // 用层中心时年历是从屏幕正中"炸开"的，与用户点的那个月没关系；
                // 锚在源卡片上才是"从这张卡长出来"。（曾擅自把纵向锚点改成层顶想解决
                // 第一排被切的问题，用户指出"不要随便改锚点"——已还原，问题另行处理。）
                transformOrigin = TransformOrigin(
                    pivotFractionX = ((card.x + card.w / 2f) / yearWidth).coerceIn(0f, 1f),
                    pivotFractionY = ((card.y + card.h / 2f) / yearHeight).coerceIn(0f, 1f),
                )
            },
        monthLabel = { date ->
            dateFormats?.monthName(date)
                ?: context.getString(R.string.date_month_short, date.monthValue)
        },
        onMonthClick = onMonthClick,
    )
}

/**
 * 周层（真实图层）：morph **收尾之后**才出现。
 *
 * 排版必须与 [MonthWeekMorph] 的终态逐像素一致，否则 morph 结束换回真图层时会闪一下：
 * 星期栏 30 + 周条 80 + 分隔线 + 当日内容（起始 y = 110）。
 */
@Composable
internal fun WeekLayer(
    calendarW: Float,
    weekStart: String,
    week: List<LocalDate>,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    dayBlocks: List<EditBlock>?,
    selectedDate: LocalDate,
    dateFormats: DateFormats?,
    showTime: Boolean,
    imageLoader: suspend (String) -> ImageBitmap?,
    onDayClick: (LocalDate) -> Unit,
    onPageWeek: (Int) -> Unit,
    onOpenDay: () -> Unit,
    onWrite: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val metrics = remember(calendarW, showLunar) {
        CalendarLayout.weekMetrics(calendarW, showLunar)
    }
    Column(modifier = modifier.width(calendarW.dp).fillMaxHeight()) {
        CalendarWeekdayHeader(weekStart = weekStart)
        WeekStrip(
            week = week,
            stripWidth = minOf(calendarW, WEEK_STRIP_MAX_WIDTH_DP),
            metrics = metrics,
            showLunar = showLunar,
            selectedKey = selectedKey,
            todayKey = todayKey,
            flags = flags,
            onDayClick = onDayClick,
            onPageWeek = onPageWeek,
        )
        DayContentBlock(
            blocks = dayBlocks,
            selectedDate = selectedDate,
            todayKey = todayKey,
            dateFormats = dateFormats,
            showTime = showTime,
            imageLoader = imageLoader,
            onOpenDay = onOpenDay,
            onWrite = onWrite,
            showLunar = showLunar,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 周态的当日内容块：分隔线 + 日期标题行 + 分隔线 + 日记内容。
 *
 * 对应 iOS 的 `dayContentBlock`：morph 层与真实周层**共用同一份**，于是 morph 收尾
 * 换回真图层时位置、内容、列宽全都不变。
 */
@Composable
internal fun DayContentBlock(
    blocks: List<EditBlock>?,
    selectedDate: LocalDate,
    todayKey: String,
    dateFormats: DateFormats?,
    showTime: Boolean,
    imageLoader: suspend (String) -> ImageBitmap?,
    onOpenDay: () -> Unit,
    onWrite: () -> Unit,
    showLunar: Boolean,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        DayPanel(
            blocks = blocks,
            selectedDate = selectedDate,
            todayKey = todayKey,
            dateFormats = dateFormats,
            showTime = showTime,
            imageLoader = imageLoader,
            onOpenDay = onOpenDay,
            onWrite = onWrite,
            onToday = null,
            showHeader = true,
            showLunar = showLunar,
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth(),
        )
    }
}

/**
 * 翻页视口：把「跟手位移 + 邻页补齐」这套结构收在一处，月层与年层共用。
 *
 * [page] 的入参是相对偏移：`0` 当前页、`+1` 下一页（下方）、`-1` 上一页（上方）。
 * 邻页只在真正拖动时组合（`showNext` / `showPrev`）—— 日历与年历都是自绘的，
 * 常驻三页会让每帧多画两遍。
 *
 * 邻页与本页之间正好隔一个 [bandH]，于是"本页滑出屏幕"的同时"邻页滑进来"，中间不露白。
 */
@Composable
internal fun PagerViewport(
    bandH: Float,
    /**
     * 位移的**读取器**而不是值。
     *
     * 传值意味着调用方在组合期读了 state，于是拖动每一帧都要重组整屏；
     * 传 lambda 之后 `graphicsLayer` 的 deferred lambda 自己去读，
     * 位移变化只让 layer 失效（跳过组合与布局）。
     */
    offsetPx: () -> Float,
    showNext: Boolean,
    showPrev: Boolean,
    page: @Composable (Int) -> Unit,
) {
    val densityScale = LocalDensity.current.density
    val pagePx = bandH * densityScale
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(bandH.dp)
            .clipToBounds(),
        // ⚠️ 每一页都要**自己居中**：日历与年历都限宽 `min(640, 内容宽)`，
        // 而这里的 Box 是整宽的 —— 少了 `TopCenter`，宽屏上日历会贴左边，
        // 右边空出一大块（实测回归：包了这一层之后丢的）。
        contentAlignment = Alignment.TopCenter,
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bandH.dp)
                .graphicsLayer { translationY = offsetPx() },
            contentAlignment = Alignment.TopCenter,
        ) {
            if (showNext) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bandH.dp)
                        .graphicsLayer { translationY = pagePx },
                    contentAlignment = Alignment.TopCenter,
                ) { page(1) }
            }
            if (showPrev) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(bandH.dp)
                        .graphicsLayer { translationY = -pagePx },
                    contentAlignment = Alignment.TopCenter,
                ) { page(-1) }
            }
            page(0)
        }
    }
}

/**
 * 周条：一行 7 天，横向拖动翻周。
 *
 * 对应 iOS `weekLayer` 的 `DragPagePager(axis: .horizontal)`：
 * 拖动时两页跟手（旧页淡出、新页淡入），松手按**位移或速度**决定去留，
 * 落定后把偏移清零（单位数组 `listOf(current)`，于是新页天然居中）。
 */
@Composable
internal fun WeekStrip(
    week: List<LocalDate>,
    /** 周条的可用宽度（dp，已限宽并居中）。 */
    stripWidth: Float,
    metrics: CalendarLayout.DayMetrics,
    showLunar: Boolean,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    onDayClick: (LocalDate) -> Unit,
    onPageWeek: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    // 与 iOS `weekLayer` 的 `DragPagePager(axis: .horizontal)` 同一套逻辑：
    // **一次只翻一页**、跟手、松手按"位移或速度"决定去留，落定后把偏移清零。
    // 复用年份翻页那套 `CalendarLayout.DragPagerMath`（它本身就是 `DragPagePager.swift` 的移植）。
    //
    // ⚠️ 之前这里用的是 `LazyRow` + 官方吸附：它的惯性会**一次翻好几周**
    //（实测一次快滑直接跳了 4 周、慢拖 20% 又回弹），与 iOS"滑动即切换一周、
    //  选中日期跟着走一周"完全不是一回事（用户："周视图下周不能左右滑动切换日期，
    //  滑动之后都会自动跳回到之前的日期。这里和 iOS 的逻辑也不一样"）。
    val density = LocalDensity.current.density
    val gridLabel = weekDescription(week)
    // ⚠️ 必须取**最新**的回调。`pointerInput(stripWidth)` 的 key 在翻周时不变，
    // 于是那个协程块只在**第一次组合**时启动一次 —— 直接调用参数 `onPageWeek` 会一直用
    // 第一次捕获的 lambda，而它闭包着当时的 `selectedDate`：每次翻周都算出"那一天 +7"，
    // 结果是**只能翻一次**，之后无论怎么滑都停在原地（用户 2026-09-27："左右切换不能
    // 一直切换下去，只能切换一次似乎"）。`rememberUpdatedState` 让它每次读到的都是新的。
    val currentOnPageWeek by rememberUpdatedState(onPageWeek)
    var dragDp by remember { mutableFloatStateOf(0f) }
    val tracker = remember { VelocityTracker() }
    val scope = rememberCoroutineScope()
    val settleAnim = remember { Animatable(0f) }
    val pageDp = stripWidth

    Box(
        modifier = modifier
            .width(stripWidth.dp)
            .height(CalendarLayout.WEEK_STRIP_H.dp)
            .clipToBounds()
            .pointerInput(stripWidth) {
                detectHorizontalDragGestures(
                    onDragStart = {
                        tracker.resetTracking()
                        // ⚠️ 先停掉可能还在跑的落定动画，并把它的剩余量并进手指位移。
                        //
                        // iOS 的 `DragPagePager` 同样在拖动手势里独占偏移；不停的话动画会继续
                        // 往旧目标写 `settleAnim`，与手指的 `dragDp` 叠加 → 「不跟手」。
                        // 并进 `dragDp` 是为了让 `dragDp + settleAnim` 这个**总位移**保持连续
                        //（直接清掉会视觉跳一下）。
                        scope.launch {
                            settleAnim.stop()
                            val leftover = settleAnim.value
                            if (leftover != 0f) {
                                dragDp += leftover
                                settleAnim.snapTo(0f)
                            }
                        }
                    },
                    onDragEnd = {
                        // 单位统一：位移是 dp，`VelocityTracker` 给 px/s
                        val velocity = tracker.calculateVelocity().x / density
                        val predicted = dragDp +
                            velocity * CalendarLayout.DragPagerMath.PROJECTION_SECONDS
                        val target = CalendarLayout.DragPagerMath.decide(
                            translation = dragDp,
                            predictedEnd = predicted,
                            hasPrev = true,
                            hasNext = true,
                        )
                        val step = CalendarLayout.stepOf(target)
                        scope.launch {
                            // ⚠️ **负号不能丢**：`step = +1` 是"翻到下一周"，内容要**左移**一页
                            //（新的一周从右边进来），所以终点是 `-pageDp`。
                            // 写成 `+step * pageDp` 的话，松手后内容先往反方向飞一页，
                            // 落到终点再换数据 —— 用户看到的"滑动之后手离开，周视图会跳回
                            // 前面，然后再跳到新的周"就是这个（iOS 那边是
                            // `let delta = goNext ? -pageSize : pageSize`）。
                            // 年份翻页 `commitPx = -step * pagePx` 也是同一个约定。
                            settleAnim.animateTo(
                                targetValue = -step * pageDp - dragDp,
                                animationSpec = tween(
                                    durationMillis = if (step != 0) {
                                        CalendarLayout.DragPagerMath.PAGE_SETTLE_MS
                                    } else {
                                        CalendarLayout.DragPagerMath.PAGE_CANCEL_MS
                                    },
                                    easing = FastOutSlowInEasing,
                                ),
                            )
                            // 落定：把"选中日"挪一周（与 iOS `onPageChange` → `vm.select(+7天)` 一致），
                            // 再把偏移清零 —— 新的一周此刻正好在正中，视觉上无缝。
                            if (step != 0) currentOnPageWeek(step)
                            dragDp = 0f
                            settleAnim.snapTo(0f)
                        }
                    },
                    onHorizontalDrag = { change, delta ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        dragDp = CalendarLayout.DragPagerMath.clampOffset(
                            dragDp + delta / density,
                            pageDp,
                        )
                    },
                )
            },
    ) {
        // 三页：上一周 / 本周 / 下一周。只有**本周**是无障碍节点（邻周是纯视觉过渡）。
        for (i in -1..1) {
            val days = remember(week, i) { week.map { it.plusWeeks(i.toLong()) } }
            Box(
                modifier = Modifier
                    .width(stripWidth.dp)
                    .height(CalendarLayout.WEEK_STRIP_H.dp)
                    .graphicsLayer {
                        // 拖动 + 落定动画都在这里读（延迟读：只让 layer 失效，不重组）
                        translationX = (i * pageDp + dragDp + settleAnim.value) * density
                    },
            ) {
                CalendarDayGrid(
                    rows = listOf(days),
                    anchorMonth = days.first(),
                    metrics = metrics,
                    // 与月流同一条 Canvas 路径（逐格真实组件在 80dp 的条里字号会被压变形）
                    canvasCells = true,
                    selectedKey = selectedKey,
                    todayKey = todayKey,
                    flags = flags,
                    onDayClick = onDayClick,
                    showAdjacent = true,
                    showLunar = showLunar,
                    showDividers = false,
                    modifier = Modifier
                        .fillMaxWidth()
                        .then(
                            if (i == 0) {
                                Modifier.semantics { contentDescription = gridLabel }
                            } else {
                                Modifier.clearAndSetSemantics { }
                            },
                        ),
                )
            }
        }
    }
}

/** 周条在宽窗口里的宽度上限（与当日面板的正文列同一个量级）。 */
internal const val WEEK_STRIP_MAX_WIDTH_DP = 560f

/** 周条形态下取选中日所在的那一周（找不到就退回第一行）。 */
/** 周条的无障碍描述：`2026年9月14日 周一 那一周`。 */
@Composable
internal fun weekDescription(week: List<LocalDate>): String {
    val first = week.firstOrNull() ?: return ""
    return stringResource(R.string.a11y_week_grid, DateFormats.dayTitleFor(first))
}
