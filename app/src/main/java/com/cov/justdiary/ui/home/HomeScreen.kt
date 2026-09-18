package com.cov.justdiary.ui.home

import android.animation.ValueAnimator
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clipToBounds
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

/**
 * 首页的三种形态。对应 iOS `HomeView` 的 `CalendarMode`。
 *
 * 三态之间的 morph 与 iOS 同一套数学（[CalendarLayout.monthWeekMorph] /
 * [CalendarLayout.yearMonthMorph]），只是端点几何跟着 §5.1 的新区高走。
 */
enum class HomeViewMode { MONTH, WEEK, YEAR }

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
    val weekProgress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val morphing = yearProgress.value > 0.01f || weekProgress.value > 0.01f
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

    /** 月 ↔ 周 morph（选中行上移到周条位置）。 */
    fun setWeekMode(expand: Boolean) {
        if ((weekProgress.value > 0.99f) == expand) return
        mode = if (expand) HomeViewMode.WEEK else HomeViewMode.MONTH
        scope.launch { weekProgress.animateTo(if (expand) 1f else 0f, morphSpec) }
    }

    /** 周态下横向翻周：选中日跟着走一周（与 iOS `weekLayer` 的翻周一致）。 */
    fun pageWeek(delta: Int) {
        val next = selectedDate.plusDays(7L * delta)
        selectedKey = DateUtil.dayKeyOf(next)
        // 跨月时把月份也带过去，否则月↔周 morph 的起点会错位
        monthKey = CalendarGrid.monthKeyOf(next)
    }

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
        scope.launch {
            yearProgress.snapTo(0f)
            weekProgress.snapTo(0f)
        }
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

        // **固定 6 行**（`CalendarGrid.weeks`，与 iOS 一致）：月历是整屏视图，
        // 行数随月份在 5/6 之间跳会让整块版面在翻月时上下抖，而且 5 行时屏幕下方留一大块空白。
        // 固定 6 行 = 行高稳定、版面稳定，末行不属于当月的日期按"相邻月"淡显。
        val displayed = remember(monthFirst, weekStart) {
            CalendarGrid.weeks(monthFirst, weekStart)
        }
        val rows = displayed.size
        // 月态是**整屏视图**（规范 §5.1）：日历带 = 顶栏之外的全部可用高，
        // 行高由它反算并封顶在 MAX_CELL_H（避免首轮"126dp 行高把日历拉散"）。
        // 周态的日历带只有「星期栏 + 周条」，下方才是日记预览；
        // 横屏分栏时左栏撑满可用高。
        val topBarH = if (split) 0f else CALENDAR_BAND_TOP
        val monthAreaH = if (split) {
            maxOf(CalendarLayout.PORTRAIT_MIN_AREA_H, availableH - CalendarLayout.WEEKDAY_HEADER_H)
        } else {
            CalendarLayout.monthAreaHeight(availableH - topBarH, rows)
        }
        val cellH = CalendarLayout.cellHeightFor(monthAreaH, rows)
        // 月层**实际**占的网格高：行高被 MAX_CELL_H 封顶后，网格比"可用高"矮。
        // 年↔月 morph 的月端点必须用这个值，否则收尾会跳（年层的格子尺寸对不上）。
        val gridH = rows * cellH
        val areaH = CalendarLayout.WEEKDAY_HEADER_H + gridH
        val metrics = remember(calendarW, areaH, rows, cellH, showLunar) {
            CalendarLayout.monthPaneMetrics(
                cellW = CalendarLayout.monthCellW(calendarW),
                cellH = cellH,
                showLunar = showLunar,
            )
        }

        // ---- 月 ↔ 周 morph（几何与 iOS 同一套数学，只是 cellH 换成本轮的 56dp）----
        val weekMetrics = remember(calendarW, showLunar) {
            CalendarLayout.weekMetrics(calendarW, showLunar)
        }
        val selectedWeekIndex = remember(displayed, selectedKey) {
            displayed.indexOfFirst { row -> row.any { DateUtil.dayKeyOf(it) == selectedKey } }
                .coerceAtLeast(0)
        }
        val morphed = remember(weekProgress.value, weekMetrics, metrics, rows, selectedWeekIndex) {
            CalendarLayout.monthWeekMorph(
                progress = weekProgress.value,
                baseRowY = cellH * selectedWeekIndex,
                monthHeight = cellH * rows,
                monthMetrics = metrics,
                weekMetrics = weekMetrics,
                // 落点 = 星期栏底部：星期栏在三态里都保留，选中行落在它下面
                stripTopY = CalendarLayout.WEEKDAY_HEADER_H,
            )
        }
        // 月↔周 morph 期间：选中行不动，其余行向外移出并淡出（与 iOS 的 rowOffsets 一致）
        val rowOffsets = FloatArray(rows) { row ->
            when {
                row == selectedWeekIndex -> morphed.selectedRowY
                row < selectedWeekIndex -> morphed.rowAboveY - cellH * (selectedWeekIndex - row - 1)
                else -> morphed.rowBelowY + cellH * (row - selectedWeekIndex - 1)
            }
        }
        val rowAlphas = FloatArray(rows) { row ->
            if (row == selectedWeekIndex) 1f else morphed.rowAboveAlpha
        }
        val weekMode = weekProgress.value > 0.01f
        // 三态的日历带高（`docs/design-spec.md` §5.1）：
        //  - 月 / 年：**整屏**（顶栏之外的全部高度）
        //  - 周：星期栏 + 周条 68，下方是日记预览（预览**只在周态**出现）
        // 分栏模式下由 Row 决定（左栏撑满），带高不参与。
        val fullBandH = maxOf(0f, availableH - topBarH)
        val weekBandH = CalendarLayout.WEEKDAY_HEADER_H + CalendarLayout.WEEK_STRIP_H
        // 年历的真实高度 = 整屏（年历与月历都是整屏视图）
        val yearHeight = if (split) availableH else fullBandH

        Column(modifier = Modifier.fillMaxSize()) {
            // 分栏（横屏/宽窗口）时头部隐藏：只有 402dp 高，头部会吃掉日历（与 iOS 一致）
            if (!split) {
                HomeTopBar(
                    title = homeTitle(mode, year, monthFirst, dateFormats, context),
                    isYearMode = mode == HomeViewMode.YEAR,
                    expandedWeek = mode == HomeViewMode.WEEK,
                    onTitleClick = ::toggleYear,
                    onToggleWeek = { setWeekMode(!weekMode) },
                    onToday = ::goToday,
                )
            }

            val onDayClick: (LocalDate) -> Unit = { date ->
                val key = DateUtil.dayKeyOf(date)
                when {
                    // 月态：点日期 = 进周态并选中（与 iOS `openDay` 一致）
                    !weekMode -> {
                        selectedKey = key
                        setWeekMode(true)
                    }
                    // 周态：点另一天 = 换选中；点已选中的那天 = 打开日记
                    key != selectedKey -> selectedKey = key
                    else -> onOpenDay(date)
                }
            }
            val calendarArgs = CalendarSectionArgs(
                displayed = displayed,
                monthFirst = monthFirst,
                metrics = metrics,
                cellHeight = cellH,
                calendarW = calendarW,
                weekStart = weekStart,
                showWeekdayHeader = mode != HomeViewMode.YEAR,
                selectedKey = selectedKey,
                todayKey = todayKey,
                flags = flags,
                showLunar = showLunar,
                weekMetrics = weekMetrics,
                weekProgress = weekProgress.value,
                rowOffsets = rowOffsets,
                rowAlphas = rowAlphas,
                selectedWeekIndex = selectedWeekIndex,
                fadeForYear = yearProgress.value,
                onDayClick = onDayClick,
                onPageMonth = ::pageMonth,
                onPageWeek = ::pageWeek,
                a11yPrevMonth = a11yPrevMonth,
                a11yNextMonth = a11yNextMonth,
                a11yPrevYear = a11yPrevYear,
                a11yNextYear = a11yNextYear,
                pageMonth = ::pageMonth,
                jumpYear = ::jumpYear,
            )
            if (split) {
                // 横屏 / 宽窗口：左右双栏，左边日历（月 / 周 / 年三态都在左栏），右边日记预览
                Row(modifier = Modifier.fillMaxSize()) {
                    CalendarSection(args = calendarArgs)
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
                // 竖屏：月 / 年是**整屏**日历；周态是「周条 + 下方日记预览」。
                // 日记预览**只在周态**出现（iOS `calendarArea`：月/年层里没有 DayContentView）。
                when (mode) {
                    HomeViewMode.WEEK -> Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        Box(modifier = Modifier.height(weekBandH.dp)) {
                            CalendarSection(args = calendarArgs)
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

                    // 月态：整屏。日历带高 = 全部可用高，行高由它反算并封顶。
                    HomeViewMode.MONTH -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        CalendarSection(args = calendarArgs)
                    }

                    // 年态：同样整屏（覆盖整个可用高，12 张卡片均匀铺开）。
                    HomeViewMode.YEAR -> Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                    ) {
                        YearLayer(
                            args = calendarArgs,
                            year = year,
                            monthFirst = monthFirst,
                            yearHeight = yearHeight,
                            monthGridH = gridH,
                            yearProgress = yearProgress.value,
                            dateFormats = dateFormats,
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
        }
    }
}

/**
 * 年历层：盖在月历之上，透明度与缩放来自插值（对应 iOS 的 `yearLayer` + `YearMonthMorphView`）。
 *
 * 与月历一样是**整屏视图**（竖屏占满、分栏时占左栏），因此 morph 的终点取
 * [yearHeight] 而不是旧的固定下限 —— 两个端点分别与各自真实图层同源，收尾才不跳。
 */
@Composable
private fun YearLayer(
    args: CalendarSectionArgs,
    year: Int,
    monthFirst: LocalDate,
    yearHeight: Float,
    /** 月层网格的**实际**高度（行高封顶后），morph 的月端点。 */
    monthGridH: Float,
    yearProgress: Float,
    dateFormats: DateFormats?,
    onMonthClick: (Int) -> Unit,
) {
    val context = LocalContext.current
    val morph = remember(yearProgress, yearHeight, monthGridH, args.calendarW, args.showLunar) {
        CalendarLayout.yearMonthMorph(
            progress = yearProgress,
            miniGrid = CalendarLayout.miniGridRect(
                CalendarLayout.yearCardRect(1, args.calendarW, yearHeight),
            ),
            // 网格区从星期栏底部开始，高 = 行数 × 行高
            fullGrid = CalendarLayout.fullMonthGridRect(
                args.calendarW,
                CalendarLayout.WEEKDAY_HEADER_H + monthGridH,
                CalendarLayout.WEEKDAY_HEADER_H,
            ),
            showLunar = args.showLunar,
        )
    }
    YearCanvas(
        year = year,
        selectedMonth = monthFirst.monthValue,
        todayKey = args.todayKey,
        flags = args.flags,
        weekStart = args.weekStart,
        modifier = Modifier
            .fillMaxWidth()
            .height(yearHeight.dp)
            .graphicsLayer {
                alpha = morph.yearOpacity
                scaleX = morph.yearScale
                scaleY = morph.yearScale
            },
        monthLabel = { date ->
            dateFormats?.monthName(date)
                ?: context.getString(R.string.date_month_short, date.monthValue)
        },
        onMonthClick = onMonthClick,
    )
}

/**
 * 日历区的全部入参。
 *
 * 打成一个包是因为三态（月 / 周 / 年）共用同一份几何与回调，逐项传参会写出两份
 * 三十行的调用点，容易漏项 —— 首轮就是那样漏掉了 `showLunar` 与 `onPageWeek`。
 */
private data class CalendarSectionArgs(
    val displayed: List<List<LocalDate>>,
    val monthFirst: LocalDate,
    val metrics: CalendarLayout.DayMetrics,
    val cellHeight: Float,
    val calendarW: Float,
    val weekStart: String,
    val showWeekdayHeader: Boolean,
    val selectedKey: String,
    val todayKey: String,
    val flags: Set<String>,
    val showLunar: Boolean,
    val weekMetrics: CalendarLayout.DayMetrics,
    val weekProgress: Float,
    val rowOffsets: FloatArray,
    val rowAlphas: FloatArray,
    val selectedWeekIndex: Int,
    val fadeForYear: Float,
    val onDayClick: (LocalDate) -> Unit,
    val onPageMonth: (Int) -> Unit,
    val onPageWeek: (Int) -> Unit,
    val a11yPrevMonth: String,
    val a11yNextMonth: String,
    val a11yPrevYear: String,
    val a11yNextYear: String,
    val pageMonth: (Int) -> Unit,
    val jumpYear: (Int) -> Unit,
)

/**
 * 日历区：星期栏 + （月格网格 或 周条）。
 *
 * 竖屏是整宽的一段，横屏分栏时只占左侧 [CalendarSectionArgs.calendarW]。
 * **高度由调用方给出的容器决定**，因此下面的「选中日」面板一定拿得到剩余空间。
 *
 * 月 ↔ 周 morph 期间月层的行位移/淡出由 [CalendarSectionArgs.rowOffsets] /
 * [CalendarSectionArgs.rowAlphas] 驱动，周条按 [CalendarSectionArgs.weekProgress] 淡入，
 * 两者在同一个区间里交叉 —— 这正是 iOS `calendarArea` 里月层与 morph 层同时存在的那一段。
 */
@Composable
private fun CalendarSection(args: CalendarSectionArgs) {
    Column(
        modifier = Modifier
            .width(args.calendarW.dp)
            // 年月切换的无障碍入口（iOS 侧没有标签，Android 一并补上）
            .semantics {
                customActions = listOf(
                    CustomAccessibilityAction(args.a11yPrevMonth) { args.pageMonth(-1); true },
                    CustomAccessibilityAction(args.a11yNextMonth) { args.pageMonth(1); true },
                    CustomAccessibilityAction(args.a11yPrevYear) { args.jumpYear(-1); true },
                    CustomAccessibilityAction(args.a11yNextYear) { args.jumpYear(1); true },
                )
            },
    ) {
        if (args.showWeekdayHeader) {
            // 星期栏在三态里都保留：周条显示的是"周一到周日"这一行的日期，
            // 与它上面的列标题天然对齐。月态下它就是同一份列标题。
            CalendarWeekdayHeader(weekStart = args.weekStart)
        }
        Box(
            modifier = Modifier
                .fillMaxWidth()
                // 高度在「月态（行数 × 行高）」与「周态（周条 68）」之间等值插值：
                // 用 `if` 二选一会让容器瞬间跳掉一整块，插值则是跟着 morph 一起收，
                // 下方面板平滑地长上来。行落点恰好等于收完后的容器顶部（星期栏底），
                // 所以收尾时选中行正好落在容器里、不会跳。
                .height(
                    CalendarLayout.lerp(
                        calendarGridHeight(args.displayed.size, args.cellHeight).value,
                        CalendarLayout.WEEK_STRIP_H,
                        args.weekProgress,
                    ).dp,
                )
                // 行会被平移到容器之外，必须裁掉
                .clipToBounds(),
        ) {
            if (args.weekProgress > 0.01f) {
                WeekStrip(
                    week = weekOf(args.displayed, args.selectedKey),
                    weekIndex = args.selectedWeekIndex,
                    progress = args.weekProgress,
                    stripWidth = minOf(args.calendarW, WEEK_STRIP_MAX_WIDTH_DP),
                    metrics = args.weekMetrics,
                    showLunar = args.showLunar,
                    selectedKey = args.selectedKey,
                    todayKey = args.todayKey,
                    flags = args.flags,
                    onDayClick = args.onDayClick,
                    onPageWeek = args.onPageWeek,
                    modifier = Modifier.graphicsLayer { translationY = 0f },
                )
            }
            if (args.weekProgress < 0.99f) {
                CalendarDayGrid(
                    rows = args.displayed,
                    anchorMonth = args.monthFirst,
                    metrics = args.metrics,
                    selectedKey = args.selectedKey,
                    todayKey = args.todayKey,
                    flags = args.flags,
                    onDayClick = args.onDayClick,
                    modifier = Modifier.graphicsLayer {
                        // 年历 morph 期间月层淡出；月↔周期间由行级 alpha 负责，这里不变
                        alpha = 1f - fadeProgress(args.fadeForYear)
                    },
                    // 固定 6 行时末行常常整行都属于下个月：那些日期仍然画出来（淡显）
                    showAdjacent = true,
                    showLunar = args.showLunar,
                    showDividers = true,
                    rowOffsets = args.rowOffsets.takeIf { args.weekProgress > 0.01f },
                    rowAlphas = if (args.weekProgress > 0.01f) {
                        // 与周条交叉：月层行再乘一个整体淡出系数
                        FloatArray(args.rowAlphas.size) { args.rowAlphas[it] * (1f - args.weekProgress) }
                    } else {
                        null
                    },
                    cellHeight = args.cellHeight.dp,
                )
            }
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
private fun WeekStrip(
    week: List<LocalDate>,
    weekIndex: Int,
    /** 月↔周 morph 进度：进来时从 0 → 1 淡入。 */
    progress: Float,
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
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val delta = remember { Animatable(0f) }
    val spec = tween<Float>(CalendarLayout.QUICK_SETTLE_DURATION_MS)
    // 无障碍文案必须在组合期取好：`semantics { }` 的 lambda 不是 @Composable 上下文
    val gridLabel = weekDescription(week)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(CalendarLayout.WEEK_STRIP_H.dp)
            // 手指抬起时按位移判断去留（与翻月同一套 `DragPagerMath`）
            .pointerInput(weekIndex, stripWidth) {
                detectHorizontalDragGestures(
                    onDragEnd = {
                        val current = delta.value
                        val page = when {
                            current <= -WEEK_PAGE_THRESHOLD -> 1
                            current >= WEEK_PAGE_THRESHOLD -> -1
                            else -> 0
                        }
                        scope.launch {
                            delta.animateTo(-page * stripWidth, spec)
                            if (page != 0) onPageWeek(page)
                            // 与新一周在同一帧提交，避免用旧偏移渲染一帧新周
                            delta.snapTo(0f)
                        }
                    },
                    onDragCancel = { scope.launch { delta.snapTo(0f) } },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        // 拖动期间**不启动协程**（每帧 launch 会排队、手感发滞），
                        // 直接把换算后的 dp 写进 Animatable 的当前值。
                        scope.launch {
                            delta.snapTo(
                                CalendarLayout.DragPagerMath.clampOffset(
                                    delta.value + dragAmount / density.density,
                                    stripWidth,
                                ),
                            )
                        }
                    },
                )
            },
    ) {
        Row(
            modifier = Modifier
                .align(Alignment.Center)
                .width(stripWidth.dp)
                .graphicsLayer {
                    translationX = delta.value * density.density
                    alpha = progress
                },
        ) {
            CalendarDayGrid(
                rows = listOf(week),
                anchorMonth = week.first(),
                metrics = metrics,
                selectedKey = selectedKey,
                todayKey = todayKey,
                flags = flags,
                onDayClick = onDayClick,
                showAdjacent = true,
                showLunar = showLunar,
                showDividers = false,
                cellHeight = CalendarLayout.WEEK_STRIP_H.dp,
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = gridLabel },
            )
        }
    }
}

/** 顶栏的高度（与 [HomeTopBar] 的 `heightIn(min = 52.dp)` 对应）。 */
private val CALENDAR_BAND_TOP = 52f

/** 周条在宽窗口里的宽度上限（与当日面板的正文列同一个量级）。 */
private const val WEEK_STRIP_MAX_WIDTH_DP = 560f

/** 横向拖动翻周的判定阈值（dp）。 */
private const val WEEK_PAGE_THRESHOLD = 60f

/** 周条形态下取选中日所在的那一周（找不到就退回第一行）。 */
private fun weekOf(weeks: List<List<LocalDate>>, selectedKey: String): List<LocalDate> =
    weeks.firstOrNull { row -> row.any { DateUtil.dayKeyOf(it) == selectedKey } } ?: weeks.first()

/** 周条的无障碍描述：`2026年9月14日 周一 那一周`。 */
@Composable
private fun weekDescription(week: List<LocalDate>): String {
    val first = week.firstOrNull() ?: return ""
    return stringResource(R.string.a11y_week_grid, DateFormats.dayTitleFor(first))
}

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
    expandedWeek: Boolean,
    onTitleClick: () -> Unit,
    onToggleWeek: () -> Unit,
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
        // 月 ↔ 周：iOS 上这个入口是"点日期进周条"，Android 另外给一个显式按钮，
        // 否则用户没有办法在周态与月态之间来回（点日期只会一直进周）。
        IconButton(onClick = onToggleWeek) {
            Icon(
                imageVector = Icons.Filled.DateRange,
                contentDescription = stringResource(
                    if (expandedWeek) R.string.index_toggle_month else R.string.index_toggle_week,
                ),
                tint = if (expandedWeek) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            )
        }
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
    // 月态与周态都显示"年月"：周态的大标题由周条本身表达（与 iOS 的 `titleText` 略有不同，
    // iOS 在周态显示 `MonthFull`；这里统一成年月，避免顶栏与当日标题行重复同一条日期）
    HomeViewMode.MONTH, HomeViewMode.WEEK -> formats?.monthTitle(year, monthFirst)
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
