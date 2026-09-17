package com.cov.justdiary.ui.home

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AssistChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cov.justdiary.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.withFrameNanos
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextDecoration
import com.cov.justdiary.model.ContentFlatten
import com.cov.justdiary.model.ContentPart
import com.cov.justdiary.model.ContentPartStyle
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.model.EditBlock
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.Spacing
import com.cov.justdiary.app.DebugFlags
import com.cov.justdiary.service.Log
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.util.Locale

/** 首页的三种形态。 */
enum class HomeViewMode { MONTH, WEEK, YEAR }

/**
 * 首页日历。对应 iOS `Views/Home/HomeView.swift`（竖屏 + 手机横屏）。
 *
 * ## 已实现（M3）
 *
 * - 月历 / 周历 / 年历三种形态，几何全部来自 [CalendarLayout] 的纯函数；
 * - 竖屏上下滑翻月：阈值（90 预测 / 60 实际）、橡皮筋、夹在一页之内与 iOS 同一套数字；
 * - 年月胶囊 → 年历的 morph：网格在两个端点之间插值，落点与真实图层同源
 *   （由 `CalendarMorphTest` 断言），因此收尾不跳变；
 * - 月 ↔ 周 morph：选中行上移、上下行移出淡出、大标题上移淡出；
 * - **减弱动态效果**（`ValueAnimator.areAnimatorsEnabled()`）→ 180ms 线性；
 * - 横屏（可用宽 ≥ 700dp）左右分栏：日历列 345dp + 右栏内容。
 *
 * ## 尚未接入
 *
 * 右栏日记内容（M4）、年份跨月的数据加载与仓库接线（M7）、
 * `-slow-morph` / `-morph-log` 调试开关（M9）、周分页的完整实现（M8）。
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
    /** 当前选中日的片段（横屏右栏"当日内容"用；`null` = 这一天还没有日记）。 */
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
    val weekProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val collapsed = weekProgress.value <= 0.01f
    val animatorsEnabled = remember { runCatching { ValueAnimator.areAnimatorsEnabled() }.getOrDefault(true) }
    // 调试开关：`-slow-morph` / `SLOW_MORPH=1` 把 morph 放慢到 3s（方案 §6.2）
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
            // 减弱动效优先于调试开关：这两者同时开时以"减弱"为准
            tween(durationMillis = CalendarLayout.REDUCED_MORPH_DURATION_MS, easing = LinearEasing)
        }
    }

    // 选中日与年份变化要上报给上层：前者决定"当日内容"面板，后者决定标记的读取区间
    LaunchedEffect(selectedKey) { onSelectDay(selectedKey) }

    // morph 帧率日志（`-morph-log`）：iOS 侧写逐帧日志，这里在结束时汇总一行
    val morphing = yearProgress.value > 0.01f || weekProgress.value > 0.01f
    if (DebugFlags.morphLog) {
        LaunchedEffect(morphing) {
            if (!morphing) return@LaunchedEffect
            var frames = 0
            val start = System.nanoTime()
            while (yearProgress.value > 0.01f || weekProgress.value > 0.01f) {
                withFrameNanos { frames++ }
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000
            val average = if (frames > 0) elapsedMs.toDouble() / frames else 0.0
            Log.app.d("morph: %d 帧 / %d ms（平均 %.1f ms/帧）".format(frames, elapsedMs, average))
        }
    }

    val monthFirst = remember(monthKey) { CalendarGrid.monthFirstOfMonthKey(monthKey) }
    val displayedWeeks = remember(monthKey, weekStart) { CalendarGrid.displayedWeeks(monthFirst, weekStart) }
    val year = CalendarGrid.yearOfMonthKey(monthKey)
    LaunchedEffect(year) { onYearChange(year) }
    val selectedDate = remember(selectedKey) { DateUtil.parseDayKey(selectedKey) ?: monthFirst }

    val context = LocalContext.current

    /**
     * 翻月：选中日跟到新月份的同一天，月末按当月天数夹住（与 iOS 一致）。
     *
     * 分页器、无障碍的"上/下个月"与"上/下一年"都走这一份逻辑，不再各写一遍。
     */
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

    // 无障碍动作的标签要在组合期取好（`semantics` 的 lambda 不是 @Composable）
    val a11yPrevMonth = stringResource(R.string.a11y_prev_month)
    val a11yNextMonth = stringResource(R.string.a11y_next_month)
    val a11yPrevYear = stringResource(R.string.a11y_prev_year)
    val a11yNextYear = stringResource(R.string.a11y_next_year)

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val availableW = maxWidth.value
        val availableH = maxHeight.value
        // 分栏与主栏宽度统一由 AdaptiveLayout 判定：
        // iOS 在 750dp 内容宽下算出左栏 345dp（0.46×750），Android 内容宽更大时按同一公式放大
        val landscape = LocalAdaptiveLayout.current.splitsMasterDetail
        val calendarW = if (landscape) LocalAdaptiveLayout.current.masterWidth else availableW

        Row(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(calendarW.dp)
                    .fillMaxSize()
                    // 年月切换的无障碍入口：iOS 侧这里没有标签，Android 一并补上（方案 §6.3）
                    .semantics {
                        customActions = listOf(
                            CustomAccessibilityAction(a11yPrevMonth) { pageMonth(-1); true },
                            CustomAccessibilityAction(a11yNextMonth) { pageMonth(1); true },
                            CustomAccessibilityAction(a11yPrevYear) { jumpYear(-1); true },
                            CustomAccessibilityAction(a11yNextYear) { jumpYear(1); true },
                        )
                    },
            ) {
                HomeHeader(
                    title = dateFormats?.year(year) ?: stringResource(R.string.date_year, year),
                    inYearMode = yearProgress.value > 0.01f || weekProgress.value > 0.01f,
                    // 与 iOS 的单一返回控件一致：周态 → 月态优先，再才是月态 → 年态
                    onToggleYear = {
                        when {
                            yearProgress.value > 0.01f -> {
                                mode = HomeViewMode.MONTH
                                scope.launch { yearProgress.animateTo(0f, morphSpec) }
                            }

                            weekProgress.value > 0.01f -> {
                                mode = HomeViewMode.MONTH
                                scope.launch { weekProgress.animateTo(0f, morphSpec) }
                            }

                            else -> {
                                mode = HomeViewMode.YEAR
                                scope.launch { yearProgress.animateTo(1f, morphSpec) }
                            }
                        }
                    },
                    onToday = {
                        monthKey = CalendarGrid.monthKeyOf(today)
                        selectedKey = todayKey
                        mode = HomeViewMode.MONTH
                        scope.launch {
                            yearProgress.snapTo(0f)
                            weekProgress.snapTo(0f)
                        }
                    },
                )

                CalendarBody(
                    width = calendarW,
                    availableHeight = availableH,
                    landscape = landscape,
                    weeks = displayedWeeks,
                    monthFirst = monthFirst,
                    selectedKey = selectedKey,
                    todayKey = todayKey,
                    flags = flags,
                    showLunar = showLunar,
                    weekStart = weekStart,
                    showLunarInMonthView = showLunar,
                    yearProgress = yearProgress.value,
                    weekProgress = weekProgress.value,
                    dateFormats = dateFormats,
                    selectedDate = selectedDate,
                    // 月历里点日期 → 展开成周态；周态里点日期 → 打开当天日记（与 iOS 一致）
                    onDayClick = { date ->
                        selectedKey = DateUtil.dayKeyOf(date)
                        if (weekProgress.value <= 0.01f) {
                            mode = HomeViewMode.WEEK
                            scope.launch { weekProgress.animateTo(1f, morphSpec) }
                        } else {
                            onOpenDay(date)
                        }
                    },
                    onPageMonth = { delta -> pageMonth(delta) },
                )
            }

            if (landscape) {
                DayContentPane(
                    blocks = dayBlocks,
                    dayKey = selectedKey,
                    todayKey = todayKey,
                    dateFormats = dateFormats,
                    imageLoader = imageLoader,
                    onOpenDay = { onOpenDay(selectedDate) },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(vertical = 8.dp),
                )
            }
        }

        // 年历层：盖在月历之上，透明度与缩放来自插值（与 iOS 的 yearLayer 一致）
        if (yearProgress.value > 0.01f) {
            val morph = CalendarLayout.yearMonthMorph(
                progress = yearProgress.value,
                miniGrid = CalendarLayout.miniGridRect(CalendarLayout.yearCardRect(1, calendarW, availableH)),
                fullGrid = CalendarLayout.fullMonthGridRect(calendarW, availableH),
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
                    .height(availableH.dp)
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
                    monthKey = CalendarGrid.monthKey(monthFirst.year, month)
                    selectedKey = DateUtil.dayKeyOf(LocalDate.of(monthFirst.year, month, 1))
                    mode = HomeViewMode.MONTH
                    scope.launch { yearProgress.animateTo(0f, morphSpec) }
                },
            )
        }
    }
}

@Composable
private fun CalendarBody(
    width: Float,
    availableHeight: Float,
    landscape: Boolean,
    weeks: List<List<LocalDate>>,
    monthFirst: LocalDate,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    showLunar: Boolean,
    weekStart: String,
    showLunarInMonthView: Boolean,
    yearProgress: Float,
    weekProgress: Float,
    dateFormats: DateFormats?,
    selectedDate: LocalDate,
    onDayClick: (LocalDate) -> Unit,
    onPageMonth: (Int) -> Unit,
) {
    val density = LocalDensity.current
    val densityScale = density.density

    val titleH = if (landscape) CalendarLayout.COMPACT_MONTH_TITLE_H else CalendarLayout.BIG_TITLE_H
    val headerH = if (landscape) CalendarLayout.COMPACT_WEEKDAY_HEADER_H else CalendarLayout.WEEKDAY_HEADER_H
    val areaH = CalendarLayout.portraitAreaHeight(availableHeight)

    // 竖屏不走密度判定：农历显隐只由语言决定；横屏才由可用高度决定
    val resolved = if (landscape) {
        CalendarLayout.resolveDensity(areaH - titleH - headerH, weeks.size, showLunar)
    } else {
        CalendarLayout.Density(CalendarLayout.DensityKind.Month, showLunarInMonthView)
    }
    val showWeekStrip = resolved.kind == CalendarLayout.DensityKind.WeekStrip
    val rows = if (showWeekStrip) listOf(weekOf(weeks, selectedKey)) else weeks

    val metrics = if (showWeekStrip) {
        CalendarLayout.weekMetrics(width, showLunar)
    } else {
        CalendarLayout.monthMetrics(
            width = width,
            areaH = areaH,
            showLunar = resolved.showLunar,
            rows = rows.size,
            headerH = titleH + headerH,
        )
    }

    val weekMetrics = CalendarLayout.weekMetrics(width, showLunar)
    val selectedRow = rows.indexOfFirst { row -> row.any { DateUtil.dayKeyOf(it) == selectedKey } }.coerceAtLeast(0)
    val morph = CalendarLayout.monthWeekMorph(
        progress = weekProgress,
        baseRowY = metrics.cellH * selectedRow,
        monthHeight = metrics.cellH * rows.size,
        monthMetrics = metrics,
        weekMetrics = weekMetrics,
    )

    // 月↔周 morph：只有选中行保持不透明，其余行向外移出并淡出
    val rowOffsets = FloatArray(rows.size) { row ->
        when {
            row == selectedRow -> morph.selectedRowY
            row < selectedRow -> morph.rowAboveY - metrics.cellH * (selectedRow - row - 1)
            else -> morph.rowBelowY + metrics.cellH * (row - selectedRow - 1)
        }
    }
    val rowAlphas = FloatArray(rows.size) { row -> if (row == selectedRow) 1f else morph.rowAboveAlpha }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 标题槽：morph 期间上移淡出
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(titleH.dp)
                .graphicsLayer { translationY = morph.titleOffsetY * densityScale; alpha = morph.titleOpacity },
            contentAlignment = Alignment.CenterStart,
        ) {
            Text(
                text = if (showWeekStrip) {
                    dateFormats?.weekHeaderTitle(selectedDate) ?: weekHeaderFallback(selectedDate)
                } else {
                    dateFormats?.monthTitle(monthFirst.year, monthFirst)
                        ?: stringResource(R.string.date_month_short, monthFirst.monthValue)
                },
                modifier = Modifier.padding(horizontal = Spacing.page),
                style = if (landscape) {
                    MaterialTheme.typography.titleLarge
                } else {
                    MaterialTheme.typography.displaySmall
                },
                color = MaterialTheme.colorScheme.primary,
            )
        }

        WeekdayHeader(width = width, weekStart = weekStart, compact = landscape)

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height((rows.size * metrics.cellH).dp)
                .pointerInput(rows, showWeekStrip, metrics.cellH) {
                    var drag = 0f
                    val tracker = VelocityTracker()
                    detectVerticalDragGestures(
                        onDragStart = { drag = 0f; tracker.resetTracking() },
                        onDragCancel = { drag = 0f },
                        onDragEnd = {
                            val velocity = tracker.calculateVelocity().y
                            val predictedEnd = drag + velocity * CalendarLayout.DragPagerMath.PROJECTION_SECONDS
                            when (CalendarLayout.DragPagerMath.decide(drag, predictedEnd, true, true)) {
                                CalendarLayout.PageTarget.Next -> onPageMonth(1)
                                CalendarLayout.PageTarget.Previous -> onPageMonth(-1)
                                CalendarLayout.PageTarget.Stay -> Unit
                            }
                            // offset 清零与翻页在同一帧提交（Compose 的状态写入同批生效），
                            // 避免先用旧偏移渲染一帧新页 —— 与 iOS 的同一事务要求等价
                            drag = 0f
                        },
                        onVerticalDrag = { change, delta ->
                            change.consume()
                            tracker.addPosition(change.uptimeMillis, change.position)
                            drag = CalendarLayout.DragPagerMath.clampOffset(drag + delta / densityScale, metrics.cellH)
                        },
                    )
                },
        ) {
            MonthCanvas(
                weeks = rows,
                anchorMonth = monthFirst,
                metrics = metrics,
                selectedKey = selectedKey,
                todayKey = todayKey,
                flags = flags,
                modifier = Modifier
                    .fillMaxWidth()
                    .height((rows.size * metrics.cellH).dp)
                    .graphicsLayer {
                        // 只按年历进度淡出：月↔周 morph 期间淡出的是**非选中行**（rowAlphas），
                        // 而不是整个月层 —— 选中行本身就是月层的一部分。
                        alpha = 1f - CalendarLayout.clamp01((yearProgress - 0.45f) / 0.55f)
                    },
                showAdjacent = !showWeekStrip,
                showLunar = resolved.showLunar,
                rowOffsets = if (weekProgress > 0.01f) rowOffsets else null,
                rowAlphas = if (weekProgress > 0.01f) rowAlphas else null,
                onDayClick = onDayClick,
            )
        }
    }
}

/** 星期栏（半速动态字体，对应 iOS 的 `.diaryCalendarFont`）。 */
@Composable
private fun WeekdayHeader(width: Float, weekStart: String, compact: Boolean) {
    val fontScale = LocalDensity.current.fontScale
    val labels = remember(weekStart) {
        DateFormats.weekdayNames(Locale.getDefault(), weekStart)
    }
    val height = if (compact) CalendarLayout.COMPACT_WEEKDAY_HEADER_H else CalendarLayout.WEEKDAY_HEADER_H
    val cellW = width / 7f
    val fontSizePx = CalendarLayout.weekdayFontSize(CalendarLayout.monthCellW(width))

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(height.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEach { label ->
            Text(
                text = label,
                modifier = Modifier.width(cellW.dp),
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontSize = DynamicTypeMetrics.calendarSp(fontSizePx.dp, fontScale),
            )
        }
    }
}

@Composable
private fun HomeHeader(
    title: String,
    inYearMode: Boolean,
    onToggleYear: () -> Unit,
    onToday: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.page, vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AssistChip(
            onClick = onToggleYear,
            label = { Text(if (inYearMode) "‹ $title" else title) },
        )
        TextButton(onClick = onToday) { Text(TODAY_LABEL) }
    }
}

// ---------------------------------------------------------------------- 内部

private val DAY_EMPTY_LABEL: String
    @Composable get() = stringResource(R.string.index_day_empty)
private val DAY_FUTURE_LABEL: String
    @Composable get() = stringResource(R.string.index_future_empty)
private val DAY_WRITE_LABEL: String
    @Composable get() = stringResource(R.string.index_write)
private val DAY_CONTINUE_LABEL: String
    @Composable get() = stringResource(R.string.index_continue_write)

/**
 * 横屏分栏右侧的"当日内容"。对应 iOS `Views/Home/DayContentView.swift`。
 *
 * 与 iOS 的差别只有渲染方式：iOS 用 `DiaryBlockCard`（玻璃卡片 + `RichTextView`），
 * 这里用 M3 的 `Card` + 逐段落渲染（与 `DiaryReadScreen` 同一套段落组件）。
 * 空态与"写日记 / 继续写日记"按钮的显示条件与 iOS 完全一致：
 * **只有今天**才有写入口，未来的日期显示"还没有到来"。
 */
@Composable
private fun DayContentPane(
    blocks: List<EditBlock>?,
    dayKey: String,
    todayKey: String,
    dateFormats: DateFormats?,
    imageLoader: (String) -> ImageBitmap?,
    onOpenDay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val isToday = dayKey == todayKey
    val isFuture = dayKey > todayKey
    val hasBlocks = !blocks.isNullOrEmpty()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (hasBlocks) {
            blocks.orEmpty().forEach { block ->
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
                        Text(
                            text = startLine,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        ContentFlatten.parseContent(block.contentJson).forEach { part ->
                            DayPanePart(part = part, imageLoader = imageLoader)
                        }
                    }
                }
            }
            if (isToday) {
                Button(
                    onClick = onOpenDay,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(DAY_CONTINUE_LABEL)
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 56.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = if (isFuture) DAY_FUTURE_LABEL else DAY_EMPTY_LABEL,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
                if (isToday) {
                    Button(onClick = onOpenDay) { Text(DAY_WRITE_LABEL) }
                }
            }
        }
    }
}

/** 当日内容面板里的一个段落（只做只读渲染，编辑与勾选都在日记页里）。 */
@Composable
private fun DayPanePart(part: ContentPart, imageLoader: (String) -> ImageBitmap?) {
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
                Text(
                    text = (if (part.style == ContentPartStyle.todo) (if (done) "☑ " else "☐ ") else "• ") + item,
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
private val TODAY_LABEL: String
    @Composable get() = stringResource(R.string.index_today)

/** 周条形态下取选中日所在的那一周（找不到就退回第一行）。 */
private fun weekOf(weeks: List<List<LocalDate>>, selectedKey: String): List<LocalDate> =
    weeks.firstOrNull { row -> row.any { DateUtil.dayKeyOf(it) == selectedKey } } ?: weeks.first()

/**
 * 没有 `DateFormats` 时的兜底周标题（只有预览会走到这条路）。
 *
 * 兜底也走资源，而不是写死中文 —— 否则预览里的文案会与真机不一致。
 */
@Composable
private fun weekHeaderFallback(date: LocalDate): String = stringResource(
    R.string.date_day_title,
    stringResource(R.string.date_month_short, date.monthValue),
    date.dayOfMonth,
    date.dateOnlyWeekday(),
)

/** 兜底周标题用的星期名（跟随当前资源语言）。 */
private fun LocalDate.dateOnlyWeekday(): String =
    dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.getDefault())
