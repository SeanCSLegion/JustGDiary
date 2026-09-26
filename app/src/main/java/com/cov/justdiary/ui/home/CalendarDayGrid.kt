package com.cov.justdiary.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ripple
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cov.justdiary.R
import com.cov.justdiary.model.DateUtil
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.Lunar
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.Radius
import java.time.LocalDate
import java.util.Locale

/**
 * 日历的**月格 / 周条**：每个日期都是一个真实的 Compose 组件。
 *
 * ## 为什么不再用 Canvas（首轮迁移最大的缺陷）
 *
 * 首轮把 iOS 的 `Canvas` 绘制逐字搬了过来。iOS 那边用
 * `.accessibilityRepresentation` / `accessibilityAdjustableAction` 单独补语义；
 * Android 的 `Canvas` 是**一个**节点，所有日期在无障碍树里根本不存在 ——
 * TalkBack 只能读到月份标题，日历完全不可用（实测 `uiautomator dump` 里
 * 一个日期都没有）。
 *
 * 改成组件之后一次解决三件事：
 *  1. **无障碍**：每格一个可聚焦节点，带完整日期、今天/选中/有日记状态与点击动作；
 *  2. **版面**：格子高度就是布局值，居中交给 Compose，不再靠"把圆画在哪"；
 *  3. **点击**：不再需要把 px 反算成 dp 的命中测试（[CalendarLayout.dayAt] 仍保留，
 *     给纯数学单测用）。
 *
 * 几何参数（格宽、格高、字号）依然来自 [CalendarLayout] 的纯函数，所以
 * `CalendarLayoutTest` / `CalendarMorphTest` 仍守着这套版面的数值。
 */
@Composable
fun CalendarDayGrid(
    rows: List<List<LocalDate>>,
    anchorMonth: LocalDate,
    metrics: CalendarLayout.DayMetrics,
    selectedKey: String,
    todayKey: String,
    flags: Set<String>,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    /**
     * 是否画出相邻月的日期。
     *
     * **默认 false**（规范 §5.2）：iOS 的月历只画当月日期，相邻月的位置留空。
     * 首轮默认 true，于是 8 月 31 日、10 月 1 日… 全都画了出来（实测缺陷 D4）。
     */
    showAdjacent: Boolean = false,
    showLunar: Boolean = true,
    /**
     * 月 ↔ 周 morph：每一行的 y 偏移（dp，**绝对**网格局部 y）与透明度；null 表示等距排布。
     *
     * 注意是"绝对 y"而不是"位移量"：行高在 morph 中会变（选中行缩到周条尺寸），
     * 流动排布的 y 于是不再是 `row × cellH`，位移必须由"目标 y − 该行的流动 y"算出来。
     */
    rowOffsets: FloatArray? = null,
    rowAlphas: FloatArray? = null,
    /** 月 ↔ 周 morph：每一行的度量（不传则全部用 [metrics]）。 */
    rowMetrics: Array<CalendarLayout.DayMetrics>? = null,
    /** 行之间是否画分隔线（周条不画）。 */
    showDividers: Boolean = true,
) {
    val fontScale = LocalDensity.current.fontScale
    val dayFontSize: TextUnit = DynamicTypeMetrics.calendarSp(metrics.dayFont.dp, fontScale)
    val lunarFontSize: TextUnit = DynamicTypeMetrics.calendarSp(metrics.lunarFont.dp, fontScale)
    val outlineVariant = MaterialTheme.colorScheme.outlineVariant

    Column(modifier = modifier.fillMaxWidth()) {
        // 该行在**流动排布**下的顶部 y（含前面各行的高度与分隔线厚度）。
        var flowY = 0f
        rows.forEachIndexed { row, week ->
            val m = rowMetrics?.getOrNull(row) ?: metrics
            // 分隔线：只画在"这一行至少有一个当月日期"的位置。
            // 不能用 `!showAdjacent || ...` 短路 —— showAdjacent=false 时那会变成恒真，
            // 于是整行都是相邻月的空行也会画一条线（实测缺陷 D12）。
            val rowHasMonthDay = week.any {
                it.year == anchorMonth.year && it.monthValue == anchorMonth.monthValue
            }
            if (row > 0 && showDividers && m.dividerAlpha > 0.01f && rowHasMonthDay) {
                HorizontalDivider(
                    thickness = 1.dp,
                    color = outlineVariant.copy(alpha = 0.35f * m.dividerAlpha),
                )
                flowY += 1f
            }
            // 目标 y（绝对）− 流动 y = 需要施加的位移
            val offsetY = rowOffsets?.getOrNull(row)?.let { absY -> absY - flowY } ?: 0f
            val rowAlpha = rowAlphas?.getOrNull(row) ?: 1f
            val rowH = m.cellH.dp

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(rowH)
                    .graphicsLayer {
                        translationY = offsetY * density
                        alpha = rowAlpha
                    },
            ) {
                week.forEach { date ->
                    val inMonth =
                        date.year == anchorMonth.year && date.monthValue == anchorMonth.monthValue
                    if (!inMonth && !showAdjacent) {
                        // 相邻月：留空 —— 不绘制、不可点、**不进无障碍树**（规范 §5.10）
                        Box(modifier = Modifier.weight(1f).height(rowH))
                        return@forEach
                    }
                    CalendarDayCell(
                        date = date,
                        showLunar = showLunar,
                        isSelected = DateUtil.dayKeyOf(date) == selectedKey,
                        isToday = DateUtil.dayKeyOf(date) == todayKey,
                        hasDiary = flags.contains(DateUtil.dayKeyOf(date)),
                        todayKey = todayKey,
                        dayFontSize = dayFontSize,
                        lunarFontSize = lunarFontSize,
                        circleDiameter = m.circleDiameter.dp,
                        diaryMarkWidth = minOf(20f, m.cellW * 0.5f).dp,
                        enabled = rowAlpha > 0.01f,
                        onClick = { onDayClick(date) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            flowY += m.cellH
        }
    }
}

/**
 * 单个日期格。
 *
 * 视觉规格（`docs/design-spec.md` §5.2）：
 *  - 选中 → `primary` 实心圆 + `onPrimary` 日号；
 *  - 今天 → `primary` 描边圆环；
 *  - 有日记 → 日号下方 2dp 圆角短横线；
 *  - 相邻月 / 未来 → 降透明度（**仍可点**）。
 *
 * 圆的直径由 `metrics.circleDiameter` 派生（`min(cellW−3, 内容高+14, cellH−3)`），
 * 与纯数学层同源；原来写死 40dp，行高一变就会和相邻格相碰。
 */
@Composable
private fun CalendarDayCell(
    date: LocalDate,
    showLunar: Boolean,
    isSelected: Boolean,
    isToday: Boolean,
    hasDiary: Boolean,
    todayKey: String,
    dayFontSize: TextUnit,
    lunarFontSize: TextUnit,
    /** 选中/今天的圆底直径（dp）。由 `metrics.circleDiameter` 派生，与纯数学层同源。 */
    circleDiameter: Dp,
    /** "有日记"下划线的宽度（dp）：`min(20, cellW × 0.5)`（与 iOS 一致）。 */
    diaryMarkWidth: Dp,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val key = DateUtil.dayKeyOf(date)
    val isFuture = key > todayKey

    val dateLabel = DateFormats.dayTitleFor(date)
    val todayLabel = stringResource(R.string.index_today)
    val selectedLabel = stringResource(R.string.index_selected)
    val hasDiaryLabel = stringResource(R.string.index_has_diary)
    val noDiaryLabel = stringResource(R.string.index_no_diary)

    val stateLabel = buildList {
        if (isToday) add(todayLabel)
        if (isSelected) add(selectedLabel)
        add(if (hasDiary) hasDiaryLabel else noDiaryLabel)
    }.joinToString(" · ")

    // 文字**不压 alpha**：M3 的强调层级用语义色表达，不用透明度。
    // 实测 0.35 alpha 的日号在浅色下只有 2.19:1，连"大字 3:1"都不到（WCAG AA / Material 都不允许）；
    // `onSurfaceVariant` 是 M3 的"中等强调"角色，浅色下约 8.6:1。
    val dayColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimary
        isToday -> MaterialTheme.colorScheme.primary
        isFuture -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.colorScheme.onSurface
    }
    val markColor = if (isSelected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary
    val interaction = remember { MutableInteractionSource() }

    Box(
        modifier = modifier
            // 撑满整行：格子高度 = 行高，内容在里面垂直居中。
            // 不写 fillMaxHeight 时 Box 只包住内容（日号 + 农历 ≈ 70dp），
            // 于是内容贴在行顶、下面空一截，分隔线看起来离内容很远。
            .fillMaxHeight()
            // Material 要求交互元素有**状态层/涟漪**；首轮这里是 `indication = null`
            // （日历格子完全没有任何触摸反馈）。做成"以日号圆为直径的圆形涟漪"，
            // 与 M3 date picker 的 40dp 圆形状态层同一种表达。
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = ripple(bounded = false, radius = circleDiameter / 2),
                role = Role.Button,
                onClick = onClick,
            )
            // 日格里只有装饰性内容（日号、农历、标记点），真正的语义全在**这一个**节点上：
            // 用 `clearAndSetSemantics` 把子节点的文本全部清掉，再挂上日期、状态与点击动作
            // （点击动作来自外层 `clickable`，会保留下来）。这样一屏只读到 35 个日期节点，
            // 实测 `uiautomator dump` 里每格是 `Button + clickable="true"`。
            //
            // 状态**同时**写进 contentDescription 与 stateDescription：uiautomator 与部分
            // 朗读器只认 contentDescription，实测 stateDescription 不会被 dump 出来。
            .clearAndSetSemantics {
                contentDescription = "$dateLabel · $stateLabel"
                stateDescription = stateLabel
                selected = isSelected
            },
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Box(
                modifier = Modifier.size(circleDiameter),
                contentAlignment = Alignment.Center,
            ) {
                if (isSelected) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .background(MaterialTheme.colorScheme.primary, CircleShape),
                    )
                } else if (isToday) {
                    Box(
                        modifier = Modifier
                            .matchParentSize()
                            .border(
                                CalendarLayout.TODAY_RING_WIDTH.dp,
                                MaterialTheme.colorScheme.primary,
                                CircleShape,
                            ),
                    )
                }
                Text(
                    text = date.dayOfMonth.toString(),
                    fontSize = dayFontSize,
                    color = dayColor,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
            }
            if (showLunar) {
                Text(
                    text = Lunar.dayLabelOf(date),
                    fontSize = lunarFontSize,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    textAlign = TextAlign.Center,
                )
            }
            if (hasDiary) {
                DiaryMark(color = markColor, width = diaryMarkWidth)
            }
        }
    }
}

/** "有日记"标记：2dp 高、1dp 圆角的短横线（对应 iOS 的 `Radius.hairline`）。 */
@Composable
private fun DiaryMark(color: Color, width: Dp) {
    Box(
        modifier = Modifier
            .padding(top = 1.dp)
            .size(width = width, height = 2.dp)
            .background(color, RoundedCornerShape(Radius.hairline)),
    )
}

/**
 * 星期栏。M3 规范：`labelMedium` + `onSurfaceVariant` + 居中。
 *
 * 与 iOS 一致地走"半速动态字体"（格子尺寸由屏幕决定，星期名不能全速放大，
 * 否则会撑破固定高度的条带）。
 */
@Composable
fun CalendarWeekdayHeader(
    weekStart: String,
    modifier: Modifier = Modifier,
    compact: Boolean = false,
) {
    val fontScale = LocalDensity.current.fontScale
    val labels = remember(weekStart) {
        DateFormats.weekdayInitials(Locale.getDefault(), weekStart)
    }
    val height =
        if (compact) CalendarLayout.COMPACT_WEEKDAY_HEADER_H else CalendarLayout.WEEKDAY_HEADER_H
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(height.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        labels.forEachIndexed { index, label ->
            // 周末列降一档（规范 §5.2）：一周从周一起算时是第 6、7 列，从周日起算时是第 1、7 列。
            val isWeekend = if (weekStart == DateUtil.WEEK_START_SUNDAY) {
                index == 0 || index == 6
            } else {
                index >= 5
            }
            Text(
                text = label,
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                // 周末列同样不压 alpha（0.6 的 onSurfaceVariant 只有 3.16:1，
                // 而星期栏是 11sp 小字，需要 4.5:1）。M3 的 `onSurfaceVariant` 本身
                // 就是"列标题"这一层强调，直接用即可。
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelMedium,
                fontSize = DynamicTypeMetrics.calendarSp(11.dp, fontScale),
                maxLines = 1,
            )
        }
    }
}

/** 日格网格的高度（dp）：`行数 × 行高`。让这条公式只有一个实现。 */
fun calendarGridHeight(rows: Int, cellHeight: Float = CalendarLayout.PORTRAIT_CELL_H): Dp =
    (rows.coerceAtLeast(1) * cellHeight).dp
