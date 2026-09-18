package com.cov.justdiary.ui.home

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import com.cov.justdiary.R
import com.cov.justdiary.app.DebugFlags
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.Log
import com.cov.justdiary.service.Lunar
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate

/** 首页的两种形态。周态已按 `docs/design-spec.md` §5.3 移除（与下方面板信息重复）。 */
enum class HomeViewMode { MONTH, YEAR }

/**
 * 首页日历。对应 iOS `Views/Home/HomeView.swift`，版面与交互按
 * [`docs/design-spec.md`](../../../../../docs/design-spec.md) §5 重新设计。
 *
 * ## 与 iOS 的关系
 *
 * | 维度 | iOS | 这里 |
 * | --- | --- | --- |
 * | 竖屏结构 | 整屏日历 + 三态 morph | **月历 + 选中日面板**两段（M3 单屏信息架构） |
 * | 点日期 | 整屏 morph 进周态 | 选中该日 + 下方面板换内容（**不换屏**） |
 * | 打开日记 | 周态里再点一次 | 点面板按钮，或再点一次已选中的日期 |
 * | 月 ↔ 年 | 缩放 morph | 保留（几何与月历同源，收尾不跳变） |
 * | 上下滑翻月 | 有 | 保留（同阈值、同橡皮筋） |
 *
 * ## 上机实测修掉的两个 v1 缺陷（见规范 §1）
 *
 * 1. **月格被拉散**：`portraitAreaHeight` 原先照搬 iOS 的 `max(320, screenH - 64)`，
 *    在 952dp 高的屏上算出 126dp 的行高，而格子内容只有 40dp。现在行高固定 56dp，
 *    区高由行数派生（[CalendarLayout.portraitAreaHeight]）。
 * 2. **日历在无障碍树里不存在**：月格从 `Canvas` 手绘改为真实组件
 *    （[CalendarDayGrid]），每一天都是一个可聚焦节点。
 */
@Composable
fun HomeScreen(
    modifier: Modifier = Modifier,
    today: LocalDate = LocalDate.now(),
    flags: Set<String> = emptySet(),
    weekStart: String = DateUtil.WEEK_START_MONDAY,
    showLunar: Boolean = true,
    dateFormats: DateFormats? = null,
    onOpenDay: (LocalDate) -> Unit = {},
    /** 当前选中日的片段（当日面板用；`null` = 这一天还没有日记）。 */
    dayBlocks: List<EditBlock>? = null,
    /** 年份变化 → 让上层按年份区间重新读日记标记。 */
    onYearChange: (Int) -> Unit = {},
    /** 选中日变化 → 让上层读这一天的内容。 */
    onSelectDay: (String) -> Unit = {},
    /** 内联图片加载器（接 `MediaStore`）。 */
    imageLoader: (String) -> ImageBitmap? = { null },
) {
    val todayKey = DateUtil.dayKeyOf(today)
    var monthKey by rememberSaveable { mutableStateOf(CalendarGrid.monthKeyOf(today)) }
    var selectedKey by rememberSaveable { mutableStateOf(todayKey) }
    var mode by rememberSaveable { mutableStateOf(HomeViewMode.MONTH) }

    val yearProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val animatorsEnabled = remember {
        runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true)
    }
    val morphSpec = remember(animatorsEnabled, DebugFlags.slowMorph) {
        if (animatorsEnabled) {
            tween<Float>(
                durationMillis = if (DebugFlags.slowMorph) {
                    DebugFlags.SLOW_MORPH_DURATION_MS
                } else {
                    CalendarLayout.MORPH_DURATION_MS
                },
                easing = CubicBezierEasing(
                    CalendarLayout.MORPH_EASING[0],
                    CalendarLayout.MORPH_EASING[1],
                    CalendarLayout.MORPH_EASING[2],
                    CalendarLayout.MORPH_EASING[3],
                ),
            )
        } else {
            // 减弱动效优先于调试开关
            tween(durationMillis = CalendarLayout.REDUCED_MORPH_DURATION_MS, easing = LinearEasing)
        }
    }

    // 选中日与年份变化上报给上层（与 iOS 的同名回调等价）
    LaunchedEffect(selectedKey) { onSelectDay(selectedKey) }
    val monthFirst = remember(monthKey) { CalendarGrid.monthFirstOfMonthKey(monthKey) }
    val year = monthFirst.year
    LaunchedEffect(year) { onYearChange(year) }
    val selectedDate = remember(selectedKey) { DateUtil.parseDayKey(selectedKey) ?: monthFirst }

    // morph 帧率日志（`-morph-log`）
    if (DebugFlags.morphLog) {
        LaunchedEffect(yearProgress.value > 0.01f) {
            if (yearProgress.value <= 0.01f) return@LaunchedEffect
            var frames = 0
            val start = System.nanoTime()
            while (yearProgress.value > 0.01f) {
                withFrameNanos { frames++ }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val average = if (frames > 0) elapsedMs.toDouble() / frames else 0.0
            Log.app.d("morph: %d 帧 / %d ms（平均 %.1f ms/帧）".format(frames, elapsedMs, average))
        }
    }

    val context = LocalContext.current

    /** 翻月：选中日跟到新月份的同一天，月末按当月天数夹住（与 iOS 一致）。 */
    fun pageMonth(delta: Int) {
        val keys = CalendarGrid.MONTH_KEYS
        val index = keys.indexOf(monthKey)
        val target = index + delta
        if (target !in keys.indices) return
        monthKey = keys[target]
        val first = CalendarGrid.monthFirstOfMonthKey(keys[target])
        val day = (DateUtil.parseDayKey(selectedKey) ?: today).dayOfMonth
        val clamped = if (day < first.lengthOfMonth()) day else first.lengthOfMonth()
        selectedKey = DateUtil.dayKeyOf(first.withDayOfMonth(clamped))
    }

    fun jumpYear(delta: Int) = pageMonth(delta * 12)

    fun toggleYear() {
        if (yearProgress.value > 0.01f) {
            mode = HomeViewMode.MONTH
            scope.launch { yearProgress.animateTo(0f, morphSpec) }
        } else {
            mode = HomeViewMode.YEAR
            scope.launch { yearProgress.animateTo(1f, morphSpec) }
        }
    }

    fun goToday() {
        monthKey = CalendarGrid.monthKeyOf(today)
        selectedKey = todayKey
        mode = HomeViewMode.MONTH
        scope.launch { yearProgress.snapTo(0f) }
    }

    // 无障碍动作的标签要在组合期取好（`semantics` 的 lambda 不是 @Composable）
    val a11yPrevMonth = stringResource(R.string.a11y_prev_month)
    val a11yNextMonth = stringResource(R.string.a11y_next_month)
    val a11yPrevYear = stringResource(R.string.a11y_prev_year)
    val a11yNextYear = stringResource(R.string.a11y_next_year)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val availableH = maxHeight.value
        val availableW = maxWidth.value
        val adaptive = LocalAdaptiveLayout.current
        val split = adaptive.splitsMasterDetail
        val calendarW = if (split) adaptive.masterWidth else availableW

        val displayed = remember(monthFirst, weekStart) {
            CalendarGrid.displayedWeeks(monthFirst, weekStart)
        }
        val rows = displayed.size
        // 区高按内容派生（规范 §5.1）：星期栏 + 行数 × 行高，并被可用高封顶
        val areaH = CalendarLayout.portraitAreaHeight(availableH, rows)
        val cellH = CalendarLayout.cellHeightFor(areaH, rows)
        val metrics = remember(calendarW, areaH, rows, cellH, showLunar) {
            CalendarLayout.monthPaneMetrics(
                cellW = CalendarLayout.monthCellW(calendarW),
                cellH = cellH,
                showLunar = showLunar,
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            // 分栏（横屏/宽窗口）时头部隐藏：只有 402dp 高，头部会吃掉日历（与 iOS 一致）
            if (!split) {
                HomeTopBar(
                    title = homeTitle(mode, year, monthFirst, dateFormats, context),
                    isYearMode = mode == HomeViewMode.YEAR,
                    onTitleClick = ::toggleYear,
                    onToday = ::goToday,
                )
            }

            // 日历区按**自己的内容高度**精确占位（顶栏 + 区高），剩下的高度留给
            // 「选中日」面板。这里绝不能用 fillMaxSize 让 Row 铺满：那样面板会被摊开到
            // 屏幕底部，年历层的第 4 行卡片还会压住当日标题行（首轮上机实测）。
            val bandHeight = (if (split) 0f else CALENDAR_BAND_TOP) + areaH
            if (split) {
                Row(modifier = Modifier.fillMaxSize()) {
                    CalendarSection(
                        displayed = displayed,
                        monthFirst = monthFirst,
                        metrics = metrics,
                        cellHeight = cellH,
                        calendarW = calendarW,
                        weekStart = weekStart,
                        showWeekdayHeader = mode != HomeViewMode.YEAR,
                        showDividers = true,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        showLunar = showLunar,
                        onDayClick = { date ->
                            val key = DateUtil.dayKeyOf(date)
                            // 点日期 = 选中它；再点一次已选中的日期才打开日记（规范 §5.3）
                            if (key == selectedKey) onOpenDay(date) else selectedKey = key
                        },
                        onPageMonth = ::pageMonth,
                        fadeForYear = yearProgress.value,
                        onOpenDay = onOpenDay,
                        a11yPrevMonth = a11yPrevMonth,
                        a11yNextMonth = a11yNextMonth,
                        a11yPrevYear = a11yPrevYear,
                        a11yNextYear = a11yNextYear,
                        pageMonth = ::pageMonth,
                        jumpYear = ::jumpYear,
                    )
                    DayPanel(
                        blocks = dayBlocks,
                        selectedDate = selectedDate,
                        todayKey = todayKey,
                        dateFormats = dateFormats,
                        imageLoader = imageLoader,
                        onOpenDay = { onOpenDay(selectedDate) },
                        onToday = ::goToday,
                        showHeader = true,
                        showLunar = showLunar,
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                    )
                }
            } else {
                Column(modifier = Modifier.height(bandHeight.dp)) {
                    CalendarSection(
                        displayed = displayed,
                        monthFirst = monthFirst,
                        metrics = metrics,
                        cellHeight = cellH,
                        calendarW = calendarW,
                        weekStart = weekStart,
                        showWeekdayHeader = mode != HomeViewMode.YEAR,
                        showDividers = true,
                        selectedKey = selectedKey,
                        todayKey = todayKey,
                        flags = flags,
                        showLunar = showLunar,
                        onDayClick = { date ->
                            val key = DateUtil.dayKeyOf(date)
                            // 点日期 = 选中它；再点一次已选中的日期才打开日记（规范 §5.3）
                            if (key == selectedKey) onOpenDay(date) else selectedKey = key
                        },
                        onPageMonth = ::pageMonth,
                        fadeForYear = yearProgress.value,
                        onOpenDay = onOpenDay,
                        a11yPrevMonth = a11yPrevMonth,
                        a11yNextMonth = a11yNextMonth,
                        a11yPrevYear = a11yPrevYear,
                        a11yNextYear = a11yNextYear,
                        pageMonth = ::pageMonth,
                        jumpYear = ::jumpYear,
                    )
                }
                DayPanel(
                    blocks = dayBlocks,
                    selectedDate = selectedDate,
                    todayKey = todayKey,
                    dateFormats = dateFormats,
                    imageLoader = imageLoader,
                    onOpenDay = { onOpenDay(selectedDate) },
                    onToday = null,
                    showHeader = true,
                    showLunar = showLunar,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                )
            }
        }

        // 年历层：盖在月历之上，透明度与缩放来自插值（与 iOS 的 yearLayer 一致）
        if (yearProgress.value > 0.01f) {
            // 年历用**自己**的高度：它比月历吃高（见 CalendarLayout.yearAreaHeight）。
            // morph 的两个端点分别取各自真实图层，所以收尾仍然不跳。
            val yearH = CalendarLayout.yearAreaHeight(availableH)
            val morph = CalendarLayout.yearMonthMorph(
                progress = yearProgress.value,
                miniGrid = CalendarLayout.miniGridRect(
                    CalendarLayout.yearCardRect(1, calendarW, yearH),
                ),
                fullGrid = CalendarLayout.fullMonthGridRect(
                    calendarW,
                    areaH,
                    CalendarLayout.WEEKDAY_HEADER_H,
                ),
                showLunar = showLunar,
            )
            YearCanvas(
                year = year,
                selectedMonth = monthFirst.monthValue,
                todayKey = todayKey,
                flags = flags,
                weekStart = weekStart,
                modifier = Modifier
                    .width(calendarW.dp)
                    .height(yearH.dp)
                    .graphicsLayer {
                        alpha = morph.yearOpacity
                        scaleX = morph.yearScale
                        scaleY = morph.yearScale
                    },
                monthLabel = { date ->
                    dateFormats?.monthName(date)
                        ?: context.getString(R.string.date_month_short, date.monthValue)
                },
                onMonthClick = { month ->
                    monthKey = CalendarGrid.monthKey(year, month)
                    selectedKey = DateUtil.dayKeyOf(LocalDate.of(year, month, 1))
                    mode = HomeViewMode.MONTH
                    scope.launch { yearProgress.animateTo(0f, morphSpec) }
                },
            )
        }
    }
}

/**
 * 日历区：星期栏 + 月格网格。
 *
 * 竖屏是整宽的一段，横屏分栏时只占左侧 [calendarW]。**高度由调用方给出的容器决定**
 * （星期栏 + 行数 × 行高），因此下面的「选中日」面板一定拿得到剩余空间。
 */
@Composable
private fun CalendarSection(
    displayed: List<List<LocalDate>>,
    monthFirst: LocalDate,
    metrics: CalendarLayout.DayMetrics,
    cellHeight: Float,
    calendarW: Float,
    weekStart: String,
    showWeekdayHeader: Boolean,
    showDividers: Boolean,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    onDayClick: (LocalDate) -> Unit,
    onPageMonth: (Int) -> Unit,
    fadeForYear: Float,
    onOpenDay: (LocalDate) -> Unit,
    a11yPrevMonth: String,
    a11yNextMonth: String,
    a11yPrevYear: String,
    a11yNextYear: String,
    pageMonth: (Int) -> Unit,
    jumpYear: (Int) -> Unit,
) {
    Column(
        modifier = Modifier
            .width(calendarW.dp)
            // 年月切换的无障碍入口（iOS 侧没有标签，Android 一并补上）
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(a11yPrevMonth) { pageMonth(-1); true },
                    CustomAccessibilityAction(a11yNextMonth) { pageMonth(1); true },
                    CustomAccessibilityAction(a11yPrevYear) { jumpYear(-1); true },
                    CustomAccessibilityAction(a11yNextYear) { jumpYear(1); true },
                )
            },
    ) {
        if (showWeekdayHeader) {
            CalendarWeekdayHeader(weekStart = weekStart)
        }
        MonthPager(
            weeks = displayed,
            monthFirst = monthFirst,
            metrics = metrics,
            cellHeight = cellHeight,
            selectedKey = selectedKey,
            todayKey = todayKey,
            flags = flags,
            showLunar = showLunar,
            showAdjacent = displayed.size > 1,
            showDividers = showDividers,
            onDayClick = onDayClick,
            onPageMonth = onPageMonth,
            fadeForYear = fadeForYear,
        )
    }
}

/** 顶栏的高度（与 [HomeTopBar] 的 `heightIn(min = 52.dp)` 对应）。 */
private val CALENDAR_BAND_TOP = 52f

// ---------------------------------------------------------------------- 顶栏

/**
 * 首页顶栏。M3 规格（规范 §5.4）：`titleLarge` + 尾部 `TextButton`，高度 `minHeight`。
 *
 * 整块标题可点 → 年历（与 iOS 的"年月胶囊"同一个入口，只是不再画成玻璃胶囊）。
 */
@Composable
private fun HomeTopBar(
    title: String,
    isYearMode: Boolean,
    onTitleClick: () -> Unit,
    onToday: () -> Unit,
) {
    val backLabel = stringResource(R.string.a11y_back_to_month)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 52.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onTitleClick)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics {
                    if (isYearMode) contentDescription = backLabel
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (isYearMode) Icons.AutoMirrored.Filled.KeyboardArrowLeft else Icons.Filled.ArrowDropDown,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
            )
        }
        Spacer(modifier = Modifier.weight(1f))
        TextButton(onClick = onToday) {
            Text(stringResource(R.string.index_today))
        }
    }
}

/** 顶栏标题：月态 `2026年 九月` / 年态 `2026年`。 */
private fun homeTitle(
    mode: HomeViewMode,
    year: Int,
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String = when (mode) {
    HomeViewMode.YEAR -> formats?.year(year) ?: context.getString(R.string.date_year, year)
    HomeViewMode.MONTH -> formats?.monthTitle(year, monthFirst)
        ?: context.getString(R.string.date_month_short, monthFirst.monthValue)
}

// ---------------------------------------------------------------------- 日历

/**
 * 月历 + 上下滑翻月。
 *
 * 手势阈值、橡皮筋、预测终点全部复用 [CalendarLayout.DragPagerMath]（与 iOS 同一套数字）。
 * 拖动期间用 `translationY` 跟随，松手后翻页并把偏移清零 —— 与 iOS 的"同一事务提交"等价。
 */
@Composable
private fun MonthPager(
    weeks: List<List<LocalDate>>,
    monthFirst: LocalDate,
    metrics: CalendarLayout.DayMetrics,
    cellHeight: Float,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    showAdjacent: Boolean,
    showDividers: Boolean,
    onDayClick: (LocalDate) -> Unit,
    onPageMonth: (Int) -> Unit,
    /** 年历 morph 进度：月层按它淡出（与 iOS 的 `1 - clamp((p - 0.45)/0.55)` 同一曲线）。 */
    fadeForYear: Float,
) {
    val density = LocalDensity.current
    val densityScale = density.density
    var drag by remember { mutableStateOf(0f) }
    val gridHeightDp = calendarGridHeight(weeks.size, cellHeight)
    val monthAlpha = 1f - fadeProgress(fadeForYear)

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(gridHeightDp)
            .pointerInput(weeks, cellHeight) {
                var accumulated = 0f
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        accumulated = 0f
                        tracker.resetTracking()
                    },
                    onDragCancel = { drag = 0f },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().y
                        val predictedEnd =
                            accumulated + velocity * CalendarLayout.DragPagerMath.PROJECTION_SECONDS
                        when (CalendarLayout.DragPagerMath.decide(accumulated, predictedEnd, true, true)) {
                            CalendarLayout.PageTarget.Next -> onPageMonth(1)
                            CalendarLayout.PageTarget.Previous -> onPageMonth(-1)
                            CalendarLayout.PageTarget.Stay -> Unit
                        }
                        // 偏移清零与翻页在同一帧提交，避免用旧偏移渲染一帧新页
                        drag = 0f
                    },
                    onVerticalDrag = { change, delta ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        accumulated = CalendarLayout.DragPagerMath.clampOffset(
                            accumulated + delta / densityScale,
                            cellHeight,
                        )
                        drag = accumulated
                    },
                )
            },
    ) {
        // 年历 morph 期间月层淡出（行的 morph 已按 §5.3 移除，这里只有年↔月一条曲线）
        CalendarDayGrid(
            rows = weeks,
            anchorMonth = monthFirst,
            metrics = metrics,
            selectedKey = selectedKey,
            todayKey = todayKey,
            flags = flags,
            onDayClick = onDayClick,
            modifier = Modifier.graphicsLayer {
                translationY = drag * densityScale
                alpha = monthAlpha
            },
            showAdjacent = showAdjacent,
            showLunar = showLunar,
            showDividers = showDividers,
            cellHeight = cellHeight.dp,
        )
    }
}

/** 年历 morph 期间月层的不透明度曲线（对应 iOS `1 - clamp01((p - 0.45) / 0.55)`）。 */
private fun fadeProgress(progress: Float): Float =
    CalendarLayout.clamp01((progress - 0.45f) / 0.55f)

// ---------------------------------------------------------------------- 当日面板

/**
 * 竖屏下方的「选中日」面板 / 横屏右栏。对应 iOS `Views/Home/DayContentView.swift`
 * 与 `CalendarPaneViews.swift` 的 `DayPane`。
 *
 * 与 iOS 相同的三条业务规则：
 *  1. 只有**今天**才有写入口；未来的日期显示"还没有到来"；
 *  2. 有内容时底部给「继续写日记」，空态给「写日记」；
 *  3. 标题行显示完整日期 + 农历。
 */
@Composable
private fun DayPanel(
    blocks: List<EditBlock>?,
    selectedDate: LocalDate,
    todayKey: String,
    dateFormats: DateFormats?,
    imageLoader: (String) -> ImageBitmap?,
    onOpenDay: () -> Unit,
    onToday: (() -> Unit)?,
    showHeader: Boolean,
    showLunar: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val dayKey = DateUtil.dayKeyOf(selectedDate)
    val isToday = dayKey == todayKey
    val isFuture = dayKey > todayKey
    val hasBlocks = !blocks.isNullOrEmpty()

    Column(modifier = modifier) {
        if (showHeader) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = CalendarLayout.DAY_TITLE_H.dp)
                    .padding(horizontal = Spacing.page, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = dateFormats?.dayTitle(selectedDate)
                        ?: DateFormats.dayTitleFor(selectedDate),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                )
                if (showLunar) {
                    Text(
                        text = lunarLabelOf(selectedDate),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
                if (isToday) {
                    Text(
                        text = stringResource(R.string.index_today),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                if (onToday != null) {
                    TextButton(onClick = onToday) {
                        Text(stringResource(R.string.index_today))
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page, vertical = 8.dp),
            contentAlignment = Alignment.TopCenter,
        ) {
            // 正文列限宽（规范 §4.2）：宽窗口里不限宽会把一行拉成 100+ 字
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .widthIn(max = 560.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (hasBlocks) {
                    blocks.orEmpty().forEach { block ->
                        DayBlockCard(
                            block = block,
                            dateFormats = dateFormats,
                            imageLoader = imageLoader,
                            onOpenDay = onOpenDay,
                        )
                    }
                    if (isToday) {
                        Button(onClick = onOpenDay, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.index_continue_write))
                        }
                    }
                } else {
                    DayEmptyState(isFuture = isFuture, isToday = isToday, onOpenDay = onOpenDay)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** 空态：图标 + 一行说明 +（仅今天）写入口。垂直居中，不靠"顶到 56dp"的硬编码。 */
@Composable
private fun DayEmptyState(isFuture: Boolean, isToday: Boolean, onOpenDay: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(MaterialTheme.colorScheme.surfaceContainerHighest, CircleShape),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = if (isFuture) "🌙" else "✏️",
                style = MaterialTheme.typography.titleMedium,
            )
        }
        Text(
            text = stringResource(
                if (isFuture) R.string.index_future_empty else R.string.index_day_empty,
            ),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        if (isToday) {
            Button(onClick = onOpenDay) { Text(stringResource(R.string.index_write)) }
        }
    }
}

/** 一天的日记片段卡片（只读渲染；编辑与勾选都在日记页里）。 */
@Composable
private fun DayBlockCard(
    block: EditBlock,
    dateFormats: DateFormats?,
    imageLoader: (String) -> ImageBitmap?,
    onOpenDay: () -> Unit,
) {
    val startLine = buildString {
        if (block.startTimeUtc > 0) {
            append(dateFormats?.timeOf(Instant.ofEpochMilli(block.startTimeUtc)).orEmpty())
        }
        if (block.locText.isNotEmpty()) {
            if (isNotEmpty()) append(" · ")
            append(block.locText)
        }
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onOpenDay),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.card),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (startLine.isNotEmpty()) {
                Text(
                    text = startLine,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            ContentFlatten.parseContent(block.contentJson).forEach { part ->
                DayPanelPart(part = part, imageLoader = imageLoader)
            }
        }
    }
}

/** 面板里的一个段落（只读）。 */
@Composable
private fun DayPanelPart(part: ContentPart, imageLoader: (String) -> ImageBitmap?) {
    when {
        part.src != null -> {
            val bitmap = imageLoader(part.src)
            if (bitmap != null) {
                Image(
                    bitmap = bitmap,
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)),
                    contentScale = ContentScale.FillWidth,
                )
            }
        }

        !part.items.isNullOrEmpty() -> Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            part.items.orEmpty().forEachIndexed { index, item ->
                val done = part.done?.getOrNull(index) == true
                val bullet = when {
                    part.style != ContentPartStyle.todo -> "• "
                    done -> "☑ "
                    else -> "☐ "
                }
                Text(
                    text = bullet + item,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (done) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.onSurface
                    },
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
            }
        }

        else -> Text(
            text = ContentFlatten.flattenPart(part),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 该日期的农历全称（干支年 + 闰月 + 日），如「丙午年八月初四」。 */
private fun lunarLabelOf(date: LocalDate): String =
    Lunar.fullLabel(Lunar.ChineseCalendarProvider.lunarDateOf(date, java.time.ZoneId.systemDefault()))
