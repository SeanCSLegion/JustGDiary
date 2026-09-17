package com.cov.justdiary.ui.home

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.service.Lunar
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.TypeSize
import java.time.LocalDate

/**
 * 日历的三种形态，全部用 `Canvas` 手绘（与 iOS 一致：一个月 = 1 个 Canvas）。
 *
 * 所有几何都来自 [CalendarLayout] 的纯函数，这里只负责"把算好的数字画出来"。
 * 命中也复用同一套纯函数，因此"画在哪"和"点在哪"不可能不一致。
 *
 * ## 两条绘制约定（与 iOS 相同）
 *
 * 1. **文字也用 `CalendarLayout` 给的度量**，但字号额外乘上"半速动态字体"系数
 *    （[DynamicTypeMetrics.calendarSp]）—— 格子尺寸由屏幕决定，全速放大会挤出格子；
 * 2. 未来的日期整体降到 [CalendarLayout.FUTURE_ALPHA]，相邻月份的日期降到
 *    [CalendarLayout.ADJACENT_MONTH_ALPHA]。
 */
@Composable
fun MonthCanvas(
    weeks: List<List<LocalDate>>,
    anchorMonth: LocalDate,
    metrics: CalendarLayout.DayMetrics,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    modifier: Modifier = Modifier,
    showAdjacent: Boolean = true,
    showLunar: Boolean = true,
    /**
     * 月 ↔ 周 morph 用：每一行的 y 偏移（px）与透明度。
     * 传 null 表示按行号等距排布（普通月历）。
     */
    rowOffsets: FloatArray? = null,
    rowAlphas: FloatArray? = null,
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)

    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    val dayStyle = textStyle(measurer, metrics.dayFont, density)
    val lunarStyle = textStyle(measurer, metrics.lunarFont, density)

    val lunarLabels = remember(weeks, showLunar, metrics.lunarFont) {
        if (!showLunar) emptyMap()
        else weeks.flatten().associateWith { Lunar.dayLabelOf(it) }
    }

    Canvas(
        modifier = modifier.then(
            if (onDayClick == null) {
                Modifier
            } else {
                Modifier.pointerInput(metrics, weeks, showAdjacent) {
                    detectTapGestures { offset ->
                        val px = with(density) { offset.x.toDp().value }
                        val py = with(density) { offset.y.toDp().value }
                        CalendarLayout.dayAt(px, py, metrics, weeks, anchorMonth, showAdjacent)
                            ?.let(onDayClick)
                    }
                }
            },
        ),
    ) {
        val cellW = metrics.cellW.dp.toPx()
        val cellH = metrics.cellH.dp.toPx()

        // 行分隔线：整行都不属于当月时不画（与 iOS 一致）
        if (metrics.dividerAlpha > 0.01f) {
            for (row in 1 until weeks.size) {
                val rowHasMonthDay = weeks[row].any { it.year == anchorMonth.year && it.monthValue == anchorMonth.monthValue }
                if (!rowHasMonthDay) continue
                val y = cellH * row
                drawLine(
                    color = outlineVariant.copy(alpha = 0.35f * metrics.dividerAlpha),
                    start = Offset(0f, y),
                    end = Offset(cellW * 7f, y),
                    strokeWidth = 1.dp.toPx(),
                )
            }
        }

        weeks.forEachIndexed { row, week ->
            val rowY = rowOffsets?.getOrNull(row) ?: (cellH * row)
            val rowAlpha = rowAlphas?.getOrNull(row) ?: 1f
            if (rowAlpha <= 0.01f) return@forEachIndexed
            week.forEachIndexed { col, date ->
                drawDay(
                    measurer = measurer,
                    date = date,
                    col = col,
                    rowY = rowY,
                    rowAlpha = rowAlpha,
                    cellW = cellW,
                    cellH = cellH,
                    metrics = metrics,
                    dayStyle = dayStyle,
                    lunarStyle = lunarStyle,
                    lunarLabel = lunarLabels[date].orEmpty(),
                    selectedKey = selectedKey,
                    todayKey = todayKey,
                    hasDiary = flags.contains(DateUtil.dayKeyOf(date)),
                    anchorMonth = anchorMonth,
                    showAdjacent = showAdjacent,
                    primary = primary,
                    onPrimary = onPrimary,
                    onSurface = onSurface,
                    onSurfaceVariant = onSurfaceVariant,
                    density = density,
                )
            }
        }
    }
}

/** 周条：一行 7 天，横向翻周。命中只需要列。 */
@Composable
fun WeekRowCanvas(
    week: List<LocalDate>,
    metrics: CalendarLayout.DayMetrics,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    modifier: Modifier = Modifier,
    showLunar: Boolean = false,
    onDayClick: ((LocalDate) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)

    val lunarLabels = remember(week, showLunar) {
        if (!showLunar) emptyMap() else week.associateWith { Lunar.dayLabelOf(it) }
    }

    // 同 YearCanvas：绘制 lambda 里不能读主题
    val primary = MaterialTheme.colorScheme.primary
    val onPrimary = MaterialTheme.colorScheme.onPrimary
    val onSurface = MaterialTheme.colorScheme.onSurface
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier.then(
            if (onDayClick == null) {
                Modifier
            } else {
                Modifier.pointerInput(metrics, week) {
                    detectTapGestures { offset ->
                        val px = with(density) { offset.x.toDp().value }
                        CalendarLayout.weekDayAt(px, metrics, week)?.let(onDayClick)
                    }
                }
            },
        ),
    ) {
        val cellW = metrics.cellW.dp.toPx()
        val cellH = metrics.cellH.dp.toPx()
        val dayStyle = textStyle(measurer, metrics.dayFont, density)
        val lunarStyle = textStyle(measurer, metrics.lunarFont, density)

        week.forEachIndexed { col, date ->
            drawDay(
                measurer = measurer,
                date = date,
                col = col,
                rowY = 0f,
                cellW = cellW,
                cellH = cellH,
                metrics = metrics,
                dayStyle = dayStyle,
                lunarStyle = lunarStyle,
                lunarLabel = lunarLabels[date].orEmpty(),
                selectedKey = selectedKey,
                todayKey = todayKey,
                hasDiary = flags.contains(DateUtil.dayKeyOf(date)),
                anchorMonth = date,
                showAdjacent = true,
                rowAlpha = 1f,
                primary = primary,
                onPrimary = onPrimary,
                onSurface = onSurface,
                onSurfaceVariant = onSurfaceVariant,
                density = density,
            )
        }
    }
}

/**
 * 年历：3 列 × 4 行的迷你月。每张卡片有自己的标题槽与迷你网格，
 * 位置全部由 [CalendarLayout.yearCardRect] 等纯函数给出。
 */
@Composable
fun YearCanvas(
    year: Int,
    selectedMonth: Int,
    todayKey: String,
    flags: Set<String>,
    modifier: Modifier = Modifier,
    weekStart: String = DateUtil.WEEK_START_MONDAY,
    showLunar: Boolean = false,
    onMonthClick: ((Int) -> Unit)? = null,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = TEXT_CACHE_SIZE)

    // Canvas 的绘制 lambda 不是 @Composable 上下文，主题色必须在这里先取出
    val primary = MaterialTheme.colorScheme.primary
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant

    Canvas(
        modifier = modifier.then(
            if (onMonthClick == null) {
                Modifier
            } else {
                Modifier.pointerInput(year) {
                    detectTapGestures { offset ->
                        val width = with(density) { size.width.toDp().value }
                        val height = with(density) { size.height.toDp().value }
                        val px = with(density) { offset.x.toDp().value }
                        val py = with(density) { offset.y.toDp().value }
                        for (month in 1..12) {
                            val card = CalendarLayout.yearCardRect(month, width, height)
                            if (px >= card.x && px <= card.maxX && py >= card.y && py <= card.maxY) {
                                onMonthClick(month)
                                break
                            }
                        }
                    }
                }
            },
        ),
    ) {
        val widthDp = size.width / density.density
        val heightDp = size.height / density.density

        val titleStyle = TextStyle(
            fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.rowTitle, density.fontScale),
            fontWeight = FontWeight.SemiBold,
        )
        val dayStyle = TextStyle(
            fontSize = DynamicTypeMetrics.calendarSp(TypeSize.caption, density.fontScale),
        )

        for (month in 1..12) {
            val card = CalendarLayout.yearCardRect(month, widthDp, heightDp)
            val title = CalendarLayout.miniTitleRect(card)
            val grid = CalendarLayout.miniGridRect(card)
            val miniMetrics = CalendarLayout.miniMetrics(grid)

            val monthFirst = LocalDate.of(year, month, 1)
            val isCurrentMonth = month == selectedMonth
            val titleColor = if (isCurrentMonth) primary else onSurfaceVariant

            val titleText = measurer.measure(monthName(monthFirst), titleStyle.copy(color = titleColor))
            drawText(
                textLayoutResult = titleText,
                topLeft = Offset(
                    title.x.dp.toPx(),
                    title.y.dp.toPx() + (title.h.dp.toPx() - titleText.size.height) / 2f,
                ),
            )

            val weeks = CalendarGrid.weeks(monthFirst, weekStart)
            val cellW = miniMetrics.cellW.dp.toPx()
            val cellH = miniMetrics.cellH.dp.toPx()

            weeks.forEachIndexed { row, week ->
                week.forEachIndexed { col, date ->
                    val inMonth = date.year == year && date.monthValue == month
                    val key = DateUtil.dayKeyOf(date)
                    val isToday = key == todayKey
                    val alpha = when {
                        isToday -> 1f
                        !inMonth -> 0f
                        else -> 0.85f
                    }
                    if (alpha <= 0.01f) return@forEachIndexed

                    val textColor = if (isToday) primary else onSurfaceVariant
                    val cx = grid.x.dp.toPx() + (col + 0.5f) * cellW
                    val cy = grid.y.dp.toPx() + (row + 0.5f) * cellH

                    if (isToday) {
                        drawCircle(
                            color = primary.copy(alpha = 0.18f),
                            radius = minOf(cellW, cellH) * 0.45f,
                            center = Offset(cx, cy),
                        )
                    }
                    if (flags.contains(key)) {
                        drawLine(
                            color = primary,
                            start = Offset(cx - cellW * 0.3f, cy + cellH * 0.32f),
                            end = Offset(cx + cellW * 0.3f, cy + cellH * 0.32f),
                            strokeWidth = 1.dp.toPx(),
                        )
                    }
                    val layout = measurer.measure(date.dayOfMonth.toString(), dayStyle.copy(color = textColor.copy(alpha = alpha)))
                    drawText(
                        textLayoutResult = layout,
                        topLeft = Offset(cx - layout.size.width / 2f, cy - layout.size.height / 2f),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------- 内部

/** 文本测量缓存的容量（对应 iOS 的 24000 条 + 0.5pt 量化）。 */
private const val TEXT_CACHE_SIZE = 512

/**
 * 日号/农历用的文本样式。
 *
 * 字号走 [DynamicTypeMetrics.calendarSp]（半速），并**量化到 0.5**（与 iOS 一致）：
 * morph 期间字号每半磅就产生一个新 key，量化能显著提高测量缓存命中率。
 */
private fun textStyle(measurer: TextMeasurer, designSize: Float, density: Density): TextStyle {
    val sp = DynamicTypeMetrics.calendarSp(designSize.dp, density.fontScale)
    val quantized = (sp.value * 2f).toInt() / 2f
    return TextStyle(fontSize = quantized.sp)
}

private fun DrawScope.drawDay(
    measurer: TextMeasurer,
    date: LocalDate,
    col: Int,
    rowY: Float,
    rowAlpha: Float,
    cellW: Float,
    cellH: Float,
    metrics: CalendarLayout.DayMetrics,
    dayStyle: TextStyle,
    lunarStyle: TextStyle,
    lunarLabel: String,
    selectedKey: String,
    todayKey: String,
    hasDiary: Boolean,
    anchorMonth: LocalDate,
    showAdjacent: Boolean,
    primary: Color,
    onPrimary: Color,
    onSurface: Color,
    onSurfaceVariant: Color,
    density: Density,
) {
    val key = DateUtil.dayKeyOf(date)
    val inMonth = date.year == anchorMonth.year && date.monthValue == anchorMonth.monthValue
    val alpha = rowAlpha * when {
        key > todayKey -> CalendarLayout.FUTURE_ALPHA
        !inMonth && !showAdjacent -> CalendarLayout.ADJACENT_MONTH_ALPHA
        !inMonth -> 0.55f
        else -> 1f
    }
    if (alpha <= 0.01f) return

    val rowYpx = rowY.dp.toPx()
    val metricsPx = metrics.scaled(density.density)
    val top = CalendarLayout.contentTop(rowYpx, metricsPx)
    val cx = CalendarLayout.cellCenterX(col, cellW)

    val isSelected = key == selectedKey
    val isToday = key == todayKey

    if (isSelected) {
        drawCircle(color = primary.copy(alpha = alpha), radius = metricsPx.circleDiameter / 2f, center = Offset(cx, CalendarLayout.circleCenterY(top, metricsPx)))
    } else if (isToday) {
        val inset = CalendarLayout.TODAY_RING_WIDTH.dp.toPx() / 2f
        drawCircle(
            color = primary.copy(alpha = alpha),
            radius = metricsPx.circleDiameter / 2f - inset,
            center = Offset(cx, CalendarLayout.circleCenterY(top, metricsPx)),
            style = Stroke(width = CalendarLayout.TODAY_RING_WIDTH.dp.toPx()),
        )
    }

    val dayColor = when {
        isSelected -> onPrimary.copy(alpha = alpha)
        isToday -> primary.copy(alpha = alpha)
        else -> onSurface.copy(alpha = alpha)
    }
    val dayLayout = measurer.measure(date.dayOfMonth.toString(), dayStyle.copy(color = dayColor))
    drawText(
        textLayoutResult = dayLayout,
        topLeft = Offset(cx - dayLayout.size.width / 2f, CalendarLayout.dayNumberCenterY(top, metricsPx) - dayLayout.size.height / 2f),
    )

    if (metrics.lunarAlpha > 0.01f && lunarLabel.isNotEmpty()) {
        val lunarLayout = measurer.measure(
            lunarLabel,
            lunarStyle.copy(color = onSurfaceVariant.copy(alpha = alpha * 0.75f * metrics.lunarAlpha)),
        )
        drawText(
            textLayoutResult = lunarLayout,
            topLeft = Offset(cx - lunarLayout.size.width / 2f, CalendarLayout.lunarCenterY(top, metricsPx) - lunarLayout.size.height / 2f),
        )
    }

    if (hasDiary) {
        val mark = CalendarLayout.diaryMark(top, metricsPx)
        drawRoundRect(
            color = if (isSelected) onPrimary.copy(alpha = alpha) else primary.copy(alpha = alpha),
            topLeft = Offset(cx - mark.width / 2f, mark.y),
            size = Size(mark.width, mark.height.dp.toPx()),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(Radius.hairline.toPx()),
        )
    }
}

/** 年历迷你月的标题用的是紧凑月名（与 iOS 的 `monthName` 槽位一致）。 */
private fun monthName(date: LocalDate): String = "${date.monthValue}月"

/** 把 dp 度量换算成 px 度量（Canvas 绘制用）。 */
private fun CalendarLayout.DayMetrics.scaled(density: Float): CalendarLayout.DayMetrics =
    CalendarLayout.DayMetrics(
        cellW = cellW * density,
        cellH = cellH * density,
        dayFont = dayFont * density,
        lunarFont = lunarFont * density,
        lunarAlpha = lunarAlpha,
        dividerAlpha = dividerAlpha,
    )
