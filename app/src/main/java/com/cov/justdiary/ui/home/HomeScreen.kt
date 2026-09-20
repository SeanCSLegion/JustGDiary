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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
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
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.Spacing
import com.cov.justdiary.ui.theme.TypeSize
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
    /**
     * 「写日记 / 继续写日记」的去向：**直接进编辑器**（对应 iOS 的 `openEditor(dayKey)`）。
     *
     * 不传时退回 [onOpenDay]（阅读页），保证旧调用点行为不变。
     */
    onWrite: (LocalDate) -> Unit = onOpenDay,
    /** 当前选中日的片段（当日面板用；`null` = 这一天还没有日记）。 */
    dayBlocks: List<EditBlock>? = null,
    /**
     * `auto_time` 设置：是否显示片段的开始时间。
     *
     * 关闭时只显示地点（与 iOS 的 `vm.showsTime` 同一语义）；数据层照旧记录。
     */
    showTime: Boolean = true,
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

    // ⚠️ 两个进度条必须**从恢复出来的 mode 初始化**，不能固定从 0 起。
    //
    // `mode` 是 `rememberSaveable`（配置变更 / 进程重建后会恢复），而 `Animatable`
    // 不是 —— 于是"在年态下旋转屏幕再切深色"时会出现：mode 恢复成 YEAR，而
    // yearProgress 回到 0 → 年层 `alpha = yearOpacity = 0`，整屏年历消失，
    // 只剩下顶栏的「‹ 2026年」（实测缺陷，见方案 §2 D2）。
    //
    // 规则：图层的**可见性由状态（mode）决定**，动画只负责过渡。
    // 从 mode 初始化等价于"恢复时直接贴到终值"，零帧闪烁，且不影响正常动画。
    val yearProgress = remember { Animatable(if (mode == HomeViewMode.YEAR) 1f else 0f) }
    val weekProgress = remember { Animatable(if (mode == HomeViewMode.WEEK) 1f else 0f) }
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

    /** 月态 → 年历（缩放 morph）。 */
    fun openYear() {
        if (mode == HomeViewMode.YEAR) return
        mode = HomeViewMode.YEAR
        scope.launch { yearProgress.animateTo(1f, morphSpec) }
    }

    /** 周态 / 年态 → 月态。周态回退时把月份拉回选中日所在的月（与 iOS 的 `goBack` 一致）。 */
    fun backToMonth() {
        if (mode == HomeViewMode.WEEK) {
            monthKey = CalendarGrid.monthKeyOf(selectedDate)
            setWeekMode(false)
            return
        }
        if (mode == HomeViewMode.YEAR) {
            mode = HomeViewMode.MONTH
            scope.launch { yearProgress.animateTo(0f, morphSpec) }
        }
    }

    /** 年历里点某个月 → 缩放 morph 回月态。 */
    fun openMonthFromYear(month: Int) {
        monthKey = CalendarGrid.monthKey(year, month)
        selectedKey = DateUtil.dayKeyOf(LocalDate.of(year, month, 1))
        mode = HomeViewMode.MONTH
        scope.launch { yearProgress.animateTo(0f, morphSpec) }
    }

    /**
     * 回到今天（规范 §5.6）。
     *
     * **周态与月态都保持当前形态**，只有年态要退回月态 —— 与 iOS 一致
     * （`todayTapped`：year → 当月；month → `resetToToday`；week → `select(now)`）。
     * 首轮一律 `mode = MONTH`，于是在周态点"今天"会被踢回月态。
     */
    fun goToday() {
        monthKey = CalendarGrid.monthKeyOf(today)
        selectedKey = todayKey
        when (mode) {
            HomeViewMode.YEAR -> {
                mode = HomeViewMode.MONTH
                scope.launch { yearProgress.snapTo(0f) }
            }
            HomeViewMode.WEEK -> Unit
            HomeViewMode.MONTH -> scope.launch { yearProgress.snapTo(0f) }
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
        val densityScale = LocalDensity.current.density

        // 当月**实际**行数（4…6）：`displayedWeeks` 从尾部删掉"整行都不属于本月"的行。
        // 与 `weeks()` 的固定 6 行不同 —— 只画真正含当月日期的行（规范 §5.1 / §5.2），
        // 于是 5 行月的最后一行是留白，而不是一排相邻月的日期。
        val displayed = remember(monthFirst, weekStart) {
            CalendarGrid.displayedWeeks(monthFirst, weekStart)
        }
        val rows = displayed.size

        // 月态日历带 = 顶栏之外的全部可用高；行高按**固定 6 行**等分、不封顶
        // （规范 §5.1：行高跨月恒定 ⇒ 翻月不抖；6 行月铺满、5 行月留一行白）。
        val topBarH = if (split) 0f else CalendarLayout.HEADER_H
        val monthAreaH = maxOf(0f, availableH - topBarH)
        // 分栏（横屏）的密度降级（规范 §5.8）：左栏高度稀缺，按每行可用高决定农历显隐 ——
        // `perRow ≥ 60` 显示农历、`≥ 44` 隐藏、否则退成周条。竖屏不走这条（农历只由语言决定）。
        val splitHeaderH = CalendarLayout.COMPACT_MONTH_TITLE_H + CalendarLayout.COMPACT_WEEKDAY_HEADER_H
        val density = remember(monthAreaH, rows, showLunar, split) {
            if (split) {
                CalendarLayout.resolveDensity(monthAreaH - splitHeaderH, rows, showLunar)
            } else {
                CalendarLayout.Density(CalendarLayout.DensityKind.Month, showLunar)
            }
        }
        val showLunarHere = density.showLunar
        val cellH = if (split) {
            // 分栏要**铺满**（不封顶也不留白）：均分是唯一不出错的做法
            CalendarLayout.fitCellHeight(monthAreaH - splitHeaderH, rows)
        } else {
            CalendarLayout.cellHeightFor(monthAreaH)
        }
        val gridH = CalendarLayout.monthGridHeight(rows, cellH)
        val metrics = remember(calendarW, cellH, showLunarHere) {
            CalendarLayout.monthPaneMetrics(
                cellW = CalendarLayout.monthCellW(calendarW),
                cellH = cellH,
                showLunar = showLunarHere,
            )
        }

        // ---- 月 ↔ 周 morph（规范 §5.5.1）----
        val weekMetrics = remember(calendarW, showLunarHere) {
            CalendarLayout.weekMetrics(calendarW, showLunarHere)
        }
        val selectedWeekIndex = remember(displayed, selectedKey) {
            displayed.indexOfFirst { row -> row.any { DateUtil.dayKeyOf(it) == selectedKey } }
                .coerceAtLeast(0)
        }
        val p = weekProgress.value
        val morphed = remember(p, weekMetrics, metrics, rows, selectedWeekIndex) {
            CalendarLayout.monthWeekMorph(
                progress = p,
                baseRowY = cellH * selectedWeekIndex,
                monthHeight = cellH * rows,
                monthMetrics = metrics,
                weekMetrics = weekMetrics,
                // 落点 = 网格容器的局部 y=0：容器自己会被上移 BIG_TITLE_H·p，
                // 于是选中行落在**绝对** y = 星期栏底部，正是真实周条的位置。
                stripTopY = 0f,
            )
        }
        // 每一行的**绝对**网格局部 y（rowOffsets 用的就是它）。
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
        // 选中行在 morph 中**缩小**到周条尺寸（其余行保持月格尺寸后移出）。
        // 只改度量不改行高会让"选中行上移"看起来像整行平移，收尾时和周条对不上。
        val rowMetrics = remember(p, metrics, weekMetrics, rows, selectedWeekIndex) {
            if (p <= 0.01f) {
                null
            } else {
                Array(rows) { row ->
                    if (row == selectedWeekIndex) metrics.lerp(weekMetrics, p) else metrics
                }
            }
        }
        val weekMode = p > 0.01f
        val fullBandH = maxOf(0f, availableH - topBarH)
        // 周态的日历带 = 星期栏 30 + 周条 68（大标题槽已经移出屏幕）
        val weekBandH = CalendarLayout.WEEKDAY_HEADER_H + CalendarLayout.WEEK_STRIP_H
        val bandH = CalendarLayout.lerp(fullBandH, weekBandH, p)
        val yearHeight = if (split) availableH else fullBandH
        val monthTitle = monthTitleText(monthFirst, dateFormats, context)

        // 进入分栏（横屏 / 宽窗口）时归位到月态：横屏不提供年历入口，也没有周态
        // （规范 §5.8，与 iOS 的 `settleToMonth()` 等价）。
        LaunchedEffect(split) {
            if (split) {
                mode = HomeViewMode.MONTH
                yearProgress.snapTo(0f)
                weekProgress.snapTo(0f)
            }
        }

        // 上下滑翻月 / 翻年（规范 §5.6）：阈值与橡皮筋复用 `DragPagerMath`。
        val pagerModifier = Modifier
            .fillMaxWidth()
            .height(bandH.dp)
            .clipToBounds()
            .pointerInput(mode, rows, cellH) {
                var accumulated = 0f
                val tracker = VelocityTracker()
                detectVerticalDragGestures(
                    onDragStart = {
                        accumulated = 0f
                        tracker.resetTracking()
                    },
                    onDragCancel = { accumulated = 0f },
                    onDragEnd = {
                        val velocity = tracker.calculateVelocity().y
                        val predictedEnd =
                            accumulated + velocity * CalendarLayout.DragPagerMath.PROJECTION_SECONDS
                        when (CalendarLayout.DragPagerMath.decide(accumulated, predictedEnd, true, true)) {
                            CalendarLayout.PageTarget.Next -> if (mode == HomeViewMode.YEAR) jumpYear(1) else pageMonth(1)
                            CalendarLayout.PageTarget.Previous -> if (mode == HomeViewMode.YEAR) jumpYear(-1) else pageMonth(-1)
                            CalendarLayout.PageTarget.Stay -> Unit
                        }
                        accumulated = 0f
                    },
                    onVerticalDrag = { change, delta ->
                        change.consume()
                        tracker.addPosition(change.uptimeMillis, change.position)
                        accumulated = CalendarLayout.DragPagerMath.clampOffset(
                            accumulated + delta / densityScale,
                            cellH,
                        )
                    },
                )
            }

        Column(modifier = Modifier.fillMaxSize()) {
            // 分栏（横屏/宽窗口）时头部隐藏：可用高只有 ~400dp，头部会吃掉日历（与 iOS 一致）
            if (!split) {
                HomeTopBar(
                    title = homeTitle(mode, year, monthFirst, dateFormats, context),
                    mode = mode,
                    onTitleClick = {
                        // 月态 → 年历；周态 / 年态 → 回月态（规范 §5.7）
                        if (mode == HomeViewMode.MONTH) openYear() else backToMonth()
                    },
                    onToday = ::goToday,
                )
            }

            val onDayClick: (LocalDate) -> Unit = { date ->
                val key = DateUtil.dayKeyOf(date)
                when {
                    // 月态：点日期 = **整屏 morph 进周态**并选中（与 iOS `openDay` 一致）。
                    // 首轮这里是"只换下方面板、不换屏"，morph 只能靠顶栏那个图标触发（缺陷 D6/D7）。
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
                showLunar = showLunarHere,
                weekMetrics = weekMetrics,
                weekProgress = p,
                rowOffsets = rowOffsets,
                rowAlphas = rowAlphas,
                rowMetrics = rowMetrics,
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
                // 大标题槽只在竖屏存在；分栏时高度太紧（规范 §5.8）
                showBigTitle = !split,
                monthTitleLabel = monthTitle,
            )
            if (split) {
                // 横屏 / 宽窗口：左右双栏。左栏只有月态（横屏不提供年历入口，也没有周态）。
                // ⚠️ 三块必须在 **Row** 里（规范 §5.8）：放进 Column 会被按高度分配，
                // 表现为"日历占满左半边、右栏整块消失"。
                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                    CalendarSection(args = calendarArgs)
                }
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 1.dp,
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
                )
                DayPanel(
                    blocks = dayBlocks,
                    selectedDate = selectedDate,
                    todayKey = todayKey,
                    dateFormats = dateFormats,
                    showTime = showTime,
                    imageLoader = imageLoader,
                    onOpenDay = { onOpenDay(selectedDate) },
                    onWrite = { onWrite(selectedDate) },
                    onToday = ::goToday,
                    showHeader = true,
                    showLunar = showLunarHere,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                )
                }
            } else {
                when (mode) {
                    // 周态：周条 + 下方日记内容（内容只在周态出现）
                    HomeViewMode.WEEK -> Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        Box(modifier = pagerModifier) { CalendarSection(args = calendarArgs) }
                        DayPanel(
                            blocks = dayBlocks,
                            selectedDate = selectedDate,
                            todayKey = todayKey,
                            dateFormats = dateFormats,
                            showTime = showTime,
                            imageLoader = imageLoader,
                            onOpenDay = { onOpenDay(selectedDate) },
                            onWrite = { onWrite(selectedDate) },
                            onToday = null,
                            showHeader = true,
                            showLunar = showLunarHere,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                        )
                    }

                    // 月态：整屏
                    HomeViewMode.MONTH -> Box(modifier = pagerModifier) {
                        CalendarSection(args = calendarArgs)
                    }

                    // 年态：整屏（12 张迷你月 + 页内大标题）
                    HomeViewMode.YEAR -> Box(modifier = pagerModifier) {
                        YearLayer(
                            args = calendarArgs,
                            year = year,
                            monthFirst = monthFirst,
                            monthTitleLabel = monthTitle,
                            yearHeight = yearHeight,
                            yearWidth = calendarW,
                            monthGridH = gridH,
                            yearProgress = yearProgress.value,
                            dateFormats = dateFormats,
                            onMonthClick = { month -> openMonthFromYear(month) },
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
    monthTitleLabel: String,
    yearHeight: Float,
    yearWidth: Float,
    /** 月层网格的**实际**高度（行数 × 行高），morph 的月端点。 */
    monthGridH: Float,
    yearProgress: Float,
    dateFormats: DateFormats?,
    onMonthClick: (Int) -> Unit,
) {
    val context = LocalContext.current
    val morph = remember(yearProgress, yearHeight, monthGridH, yearWidth, args.showLunar, args.selectedWeekIndex, args.cellHeight) {
        CalendarLayout.yearMonthMorph(
            progress = yearProgress,
            // **源卡片 = 当前选中月的那一张**：年↔月 morph 从它自己的迷你网格长出来。
            // 首轮固定用 1 月那张卡，于是 9 月放大时动画从左上角飞过来（观感"动画不对"）。
            miniGrid = CalendarLayout.miniGridRect(
                CalendarLayout.yearCardRect(monthFirst.monthValue, yearWidth, yearHeight),
            ),
            // 网格区从「大标题槽 + 星期栏」之下开始，高 = 行数 × 行高。
            // 这里必须用**封顶/派生之后**的真实网格高，否则收尾时格子尺寸对不上（会跳）。
            fullGrid = CalendarLayout.fullMonthGridRect(
                yearWidth,
                CalendarLayout.BIG_TITLE_H + CalendarLayout.WEEKDAY_HEADER_H + monthGridH,
                CalendarLayout.BIG_TITLE_H + CalendarLayout.WEEKDAY_HEADER_H,
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
        yearTitle = dateFormats?.year(year) ?: context.getString(R.string.date_year, year),
        zodiacLabel = dateFormats?.yearZodiac(year).orEmpty(),
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
    /** 月↔周 morph：每一行的度量（选中行在 morph 中缩到周条尺寸）。 */
    val rowMetrics: Array<CalendarLayout.DayMetrics>?,
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
    /** 是否画 72dp 的大标题槽（竖屏 true；分栏时为省高度画 false）。 */
    val showBigTitle: Boolean,
    /** 大标题文案（`九月`）。 */
    val monthTitleLabel: String,
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
    val density = LocalDensity.current.density
    val fontScale = LocalDensity.current.fontScale
    val p = args.weekProgress
    // 月↔周 morph（规范 §5.5.1）：大标题槽上移淡出、星期栏上移、网格上移。
    // 三者的位移量都是 -BIG_TITLE_H·p，于是 p=1 时星期栏落在 y=0、网格容器落在 y=30，
    // 正好是周条的真实位置 —— 这就是"动画终点即真实图层"。
    // 竖屏：72dp 的 32sp 大标题；分栏：34dp 的 24sp 紧凑标题（规范 §5.8，与 iOS 的
    // `compactMonthTitleH` 一致 —— 横屏高度稀缺，但完全不画标题会让左栏失去锚点）。
    val bigTitleH = if (args.showBigTitle) {
        CalendarLayout.BIG_TITLE_H
    } else {
        CalendarLayout.COMPACT_MONTH_TITLE_H
    }
    val shiftY = bigTitleH * p
    val titleOpacity = 1f - CalendarLayout.clamp01(p * 2f)

    Column(
        modifier = Modifier
            .width(args.calendarW.dp)
            // ⚠️ 必须 `unbounded`：周态的色带只有 98dp（星期栏 30 + 周条 68），
            // 而月层天然要 72 + 30 + 网格 ≈ 640dp。不加这一条时 Column 会被父约束压缩，
            // 最后一个子项（网格容器）分到 0 高度 —— 周条**完全不渲染**，实测就是这个现象。
            // 正确做法是让月层保持自然高度、由外层 band 负责裁剪。
            .wrapContentHeight(unbounded = true, align = Alignment.Top)
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
        // 大标题槽（月态与分栏都有，只是高度不同）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(bigTitleH.dp)
                .graphicsLayer {
                    translationY = -bigTitleH * p * density
                    alpha = titleOpacity
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                // 32sp 大标题（规范 §5.2）：iOS 的视觉重心就在这一行，
                // 首轮把它并进顶栏，于是整块版面又扁又空（实测缺陷 D5）。
                Text(
                    text = args.monthTitleLabel,
                    fontSize = if (args.showBigTitle) {
                        DynamicTypeMetrics.effectiveSp(TypeSize.display, fontScale)
                    } else {
                        DynamicTypeMetrics.effectiveSp(TypeSize.pageTitle, fontScale)
                    },
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = if (args.showBigTitle) 20.dp else 16.dp),
                )
        }
        Box(modifier = Modifier.graphicsLayer { translationY = -shiftY * density }) {
            CalendarWeekdayHeader(weekStart = args.weekStart, compact = !args.showBigTitle)
        }
        // 高度在「月态网格」与「周条」之间插值：用 `if` 二选一会让容器瞬间跳掉一整块。
        // 不加 clipToBounds —— 周条在 morph 前段位于容器上边界之外，裁掉会露出空白；
        // 真正的裁剪交给外面那一层 band（让行的移出被裁掉，而周条仍在屏幕内）。
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(
                    CalendarLayout.lerp(
                        calendarGridHeight(args.displayed.size, args.cellHeight).value,
                        CalendarLayout.WEEK_STRIP_H + bigTitleH,
                        p,
                    ).dp,
                )
                .graphicsLayer { translationY = -shiftY * density },
        ) {
            if (p > 0.01f) {
                WeekStrip(
                    week = weekOf(args.displayed, args.selectedKey),
                    weekIndex = args.selectedWeekIndex,
                    progress = p,
                    stripWidth = minOf(args.calendarW, WEEK_STRIP_MAX_WIDTH_DP),
                    metrics = args.weekMetrics,
                    showLunar = args.showLunar,
                    selectedKey = args.selectedKey,
                    todayKey = args.todayKey,
                    flags = args.flags,
                    onDayClick = args.onDayClick,
                    onPageWeek = args.onPageWeek,
                    // 周条在**绝对** y=30 处（星期栏之下）；换算到本容器的局部坐标
                    modifier = Modifier.offset(y = (CalendarLayout.WEEKDAY_HEADER_H * (p - 1f)).dp),
                )
            }
            if (p < 0.99f) {
                CalendarDayGrid(
                    rows = args.displayed,
                    anchorMonth = args.monthFirst,
                    metrics = args.metrics,
                    selectedKey = args.selectedKey,
                    todayKey = args.todayKey,
                    flags = args.flags,
                    onDayClick = args.onDayClick,
                    modifier = Modifier.graphicsLayer {
                        // 年历 morph 期间月层淡出；月↔周期间由行级 alpha 负责
                        alpha = 1f - fadeProgress(args.fadeForYear)
                    },
                    // 规范 §5.2：**只画当月日期**，相邻月留空
                    showAdjacent = false,
                    showLunar = args.showLunar,
                    showDividers = true,
                    rowOffsets = args.rowOffsets.takeIf { p > 0.01f },
                    rowAlphas = if (p > 0.01f) {
                        FloatArray(args.rowAlphas.size) { args.rowAlphas[it] * (1f - p) }
                    } else {
                        null
                    },
                    rowMetrics = args.rowMetrics,
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
 * 首页顶栏。M3 规格（规范 §5.7）：`titleLarge` + 尾部 `TextButton`，高度 `minHeight`。
 *
 * | 形态 | 左 | 右 |
 * | --- | --- | --- |
 * | 月态 | `▾ 2026年`（整块可点 → 年历） | `今天` |
 * | 周态 | `‹ 九月`（整块可点 → **回月态**） | `今天` |
 * | 年态 | `‹ 2026年`（整块可点 → 回月态） | `今天` |
 *
 * **这里没有"月↔周"按钮**：iOS 没有那个入口，而且它的图标在月/周两态下完全一样，
 * 摆在「今天」旁边只会被读成第二个日历入口（实测缺陷 D7）。回月态的入口就是周态
 * 顶栏左侧的 `‹ 九月`，与 iOS 一致。
 */
@Composable
private fun HomeTopBar(
    title: String,
    mode: HomeViewMode,
    onTitleClick: () -> Unit,
    onToday: () -> Unit,
) {
    val backLabel = stringResource(R.string.a11y_back_to_month)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = CalendarLayout.HEADER_H.dp)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .clickable(onClick = onTitleClick)
                .padding(horizontal = 8.dp, vertical = 6.dp)
                .semantics {
                    if (mode != HomeViewMode.MONTH) contentDescription = backLabel
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Icon(
                imageVector = if (mode == HomeViewMode.MONTH) {
                    Icons.Filled.ArrowDropDown
                } else {
                    Icons.AutoMirrored.Filled.KeyboardArrowLeft
                },
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

/**
 * 顶栏标题。
 *
 * 月态只放**年份**（规范 §5.7）：月份由下面 72dp 的 32sp 大标题承担 —— 首轮把
 * "2026年 九月"整串塞进顶栏，结果既没有大标题、顶栏又比 iOS 重（实测缺陷 D5 的一部分）。
 */
private fun homeTitle(
    mode: HomeViewMode,
    year: Int,
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String = when (mode) {
    HomeViewMode.YEAR, HomeViewMode.MONTH ->
        formats?.year(year) ?: context.getString(R.string.date_year, year)
    // 周态左侧是"返回月历"，文案用月份名（与 iOS 的 `titleText` 一致）
    HomeViewMode.WEEK -> formats?.monthFull(monthFirst)
        ?: context.getString(R.string.date_month_short, monthFirst.monthValue)
}

/** 月历大标题的文案（`九月` / `September`）。 */
private fun monthTitleText(
    monthFirst: LocalDate,
    formats: DateFormats?,
    context: android.content.Context,
): String = formats?.monthFull(monthFirst)
    ?: context.getString(R.string.date_month_short, monthFirst.monthValue)

// ---------------------------------------------------------------------- 日历

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
    showTime: Boolean,
    imageLoader: (String) -> ImageBitmap?,
    /** 点日记卡片 → 进编辑器（iOS 的 `openEditor`）。 */
    onOpenDay: () -> Unit,
    /** 「写日记 / 继续写日记」按钮 → 进编辑器。 */
    onWrite: () -> Unit,
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
                // 这里**不画**相对日文案（今天 / 昨天 / 明天）：
                // iOS 的当日标题行只有「日期 + 农历」，"今天"是横屏右栏标题行里那枚**按钮**。
                // 首轮两处都画，于是横屏标题行出现两个"今天"（实测缺陷 D9）。
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
                            showTime = showTime,
                            imageLoader = imageLoader,
                            onOpenDay = onOpenDay,
                        )
                    }
                    if (isToday) {
                        Button(onClick = onWrite, modifier = Modifier.fillMaxWidth()) {
                            Text(stringResource(R.string.index_continue_write))
                        }
                    }
                } else {
                    DayEmptyState(isFuture = isFuture, isToday = isToday, onWrite = onWrite)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

/** 空态：图标 + 一行说明 +（仅今天）写入口。垂直居中，不靠"顶到 56dp"的硬编码。 */
@Composable
private fun DayEmptyState(isFuture: Boolean, isToday: Boolean, onWrite: () -> Unit) {
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
            Button(onClick = onWrite) { Text(stringResource(R.string.index_write)) }
        }
    }
}

/** 一天的日记片段卡片（只读渲染；编辑与勾选都在日记页里）。 */
@Composable
private fun DayBlockCard(
    block: EditBlock,
    dateFormats: DateFormats?,
    showTime: Boolean,
    imageLoader: (String) -> ImageBitmap?,
    onOpenDay: () -> Unit,
) {
    // `auto_time` 关闭时不显示开始时间，只保留地点（与 iOS 的 `showsTime` 一致）
    val startLine = buildString {
        if (showTime && block.startTimeUtc > 0) {
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
