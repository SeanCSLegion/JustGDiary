package com.cov.justdiary.ui.footprint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cov.justdiary.R
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cov.justdiary.data.FootprintNode
import com.cov.justdiary.data.FootprintViewState
import com.cov.justdiary.data.FootprintYearStat
import com.cov.justdiary.data.YEAR_FILTER_ALL
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.components.DiaryCard
import com.cov.justdiary.ui.components.EmptyState
import com.cov.justdiary.ui.components.IconBadge
import com.cov.justdiary.ui.components.PageHeader
import com.cov.justdiary.ui.components.SectionTitle
import com.cov.justdiary.ui.theme.DynamicTypeMetrics
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing
import com.cov.justdiary.ui.theme.TypeSize

/**
 * 足迹页。对应 iOS `Views/Footprint/FootprintView.swift` + `FootprintViewModel`。
 *
 * 结构（与 iOS 一致）：页头 + 「已标注 N 处 · 未记录 M 篇」→ 年份胶囊 →
 * 5 格统计卡 → 年度记录天数柱状图 → 地点清单（国家→省→市，可展开）。
 *
 * 两个与 iOS 一致的行为细节：
 *  - **"未记录"的计数不受筛选影响**（由 [com.cov.justdiary.data.FootprintViewStateBuilder] 保证）；
 *  - 年份胶囊来自**未筛选**的已定位记录，因此选中某一年后其它年份仍在。
 *
 * 图表是**自绘 Compose Canvas**（方案 §11.2：全 app 只有这一张柱状图，不值得引图表库）。
 */
@Composable
fun FootprintScreen(
    state: FootprintViewState,
    yearFilter: String,
    modifier: Modifier = Modifier,
    landscape: Boolean = false,
    onYearFilterChange: (String) -> Unit = {},
    onDayClick: (String) -> Unit = {},
    /** 展开的树节点（由 `FootprintViewModel` 持有：筛选变化时要剪掉已消失的节点）。 */
    expanded: Set<String> = emptySet(),
    onToggleExpand: (String) -> Unit = {},
) {

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = TITLE,
            subtitle = summaryLabel(state.locatedCount, state.unlocatedCount),
        )

        // 年份胶囊
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = Spacing.page),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            FilterChip(
                selected = yearFilter == YEAR_FILTER_ALL,
                onClick = { onYearFilterChange(YEAR_FILTER_ALL) },
                label = { Text(ALL_LABEL) },
            )
            state.yearOptions.forEach { year ->
                FilterChip(
                    selected = yearFilter == year,
                    onClick = { onYearFilterChange(year) },
                    label = { Text(year) },
                )
            }
        }

        if (state.locatedCount == 0) {
            EmptyState(
                icon = Icons.Filled.Place,
                text = EMPTY_LABEL,
                modifier = Modifier.weight(1f, fill = false),
            )
            return@Column
        }

        if (landscape) {
            // 横屏：统计行 → 「趋势图 | 地点清单」并排（方案 §9.2）
            StatsCard(state)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.page, vertical = Spacing.card),
                horizontalArrangement = Arrangement.spacedBy(Spacing.card),
            ) {
                TrendCard(
                    yearly = state.yearly,
                    modifier = Modifier
                        .weight(1f)
                        .height(LocalAdaptiveLayout.current.footprintBlockHeight.dp),
                )
                PlaceTreeCard(
                    nodes = state.nodes,
                    expanded = expanded,
                    onToggle = onToggleExpand,
                    onDayClick = onDayClick,
                    // 清单宽：iOS `min(320, max(240, contentWidth × 0.4))`
                    modifier = Modifier.width(LocalAdaptiveLayout.current.footprintListWidth.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.page,
                    end = Spacing.page,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.card),
            ) {
                item { StatsCard(state) }
                item {
                    TrendCard(
                        yearly = state.yearly,
                        height = LocalAdaptiveLayout.current.trendHeight.dp,
                    )
                }
                item {
                    PlaceTreeCard(
                        nodes = state.nodes,
                        expanded = expanded,
                        onToggle = onToggleExpand,
                        onDayClick = onDayClick,
                    )
                }
            }
        }
    }
}

/** 5 格统计卡：省市 / 城市 / 国家 / 片段 / 天数。 */
@Composable
private fun StatsCard(state: FootprintViewState) {
    DiaryCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatCell(state.stats.provinces.size, STAT_PROVINCES)
            StatCell(state.stats.cities.size, STAT_CITIES)
            StatCell(state.stats.countries.size, STAT_COUNTRIES)
            StatCell(state.stats.blocks, STAT_BLOCKS)
            StatCell(state.stats.days.size, STAT_DAYS)
        }
    }
}

@Composable
private fun StatCell(value: Int, label: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value.toString(),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 年度记录天数柱状图（自绘）。 */
@Composable
private fun TrendCard(
    yearly: List<FootprintYearStat>,
    modifier: Modifier = Modifier,
    /** 图表高度：竖屏 132dp、宽屏 200dp（并排时由外层给块高）。 */
    height: androidx.compose.ui.unit.Dp = TREND_HEIGHT,
) {
    DiaryCard(modifier = modifier) {
        Column {
            SectionTitle(TREND_TITLE)
            if (yearly.isEmpty()) {
                Text(
                    text = NO_TREND_LABEL,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                YearBarChart(
                    yearly = yearly,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(height),
                )
            }
        }
    }
}

/**
 * 年度柱状图。
 *
 * 与 iOS 的 `Chart { BarMark(...) }` 对齐的三件事：单色柱 + 4dp 圆角、
 * 左侧 Y 轴网格线与数值、X 轴为年份。规格来自设计稿：
 * 高度竖屏 132dp / 宽屏 200dp，柱色为品牌色。
 */
@Composable
fun YearBarChart(
    yearly: List<FootprintYearStat>,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val barColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant

    val maxDays = (yearly.maxOfOrNull { it.days } ?: 1).coerceAtLeast(1)
    val axisStyle = TextStyle(
        fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.caption, density.fontScale),
        color = labelColor,
    )

    Canvas(modifier = modifier) {
        val axisWidth = AXIS_WIDTH.toPx()
        val labelHeight = AXIS_LABEL_HEIGHT.toPx()
        val chartHeight = size.height - labelHeight
        val chartWidth = size.width - axisWidth
        val slot = chartWidth / yearly.size
        val barWidth = (slot * BAR_WIDTH_RATIO).coerceAtMost(BAR_MAX_WIDTH.toPx())

        // Y 轴网格与数值：0 / 中值 / 最大值（对应设计稿的 0-3-6）
        val mid = (maxDays + 1) / 2
        listOf(0, mid, maxDays).distinct().forEach { value ->
            val y = chartHeight - (value.toFloat() / maxDays) * chartHeight
            drawLine(
                color = gridColor,
                start = Offset(axisWidth, y),
                end = Offset(size.width, y),
                strokeWidth = 1.dp.toPx(),
            )
            val layout = measurer.measure(value.toString(), axisStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(axisWidth - layout.size.width - 4.dp.toPx(), y - layout.size.height / 2f),
            )
        }

        // 柱
        yearly.forEachIndexed { index, stat ->
            val barHeight = (stat.days.toFloat() / maxDays) * chartHeight
            val left = axisWidth + slot * index + (slot - barWidth) / 2f
            drawRoundRect(
                color = barColor,
                topLeft = Offset(left, chartHeight - barHeight),
                size = Size(barWidth, barHeight),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(Radius.bar.toPx()),
            )
            // 年份标签
            val layout = measurer.measure(stat.year, axisStyle)
            drawText(
                textLayoutResult = layout,
                topLeft = Offset(left + barWidth / 2f - layout.size.width / 2f, chartHeight + 4.dp.toPx()),
            )
            // 柱顶数值
            val valueLayout = measurer.measure(stat.days.toString(), axisStyle)
            drawText(
                textLayoutResult = valueLayout,
                topLeft = Offset(
                    left + barWidth / 2f - valueLayout.size.width / 2f,
                    chartHeight - barHeight - valueLayout.size.height - 2.dp.toPx(),
                ),
            )
        }
    }
}

/** 地点清单：国家 → 省 → 市，可展开。 */
@Composable
private fun PlaceTreeCard(
    nodes: List<FootprintNode>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onDayClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    DiaryCard(modifier = modifier) {
        Column {
            SectionTitle(PLACES_TITLE)
            nodes.forEach { node ->
                PlaceRow(
                    node = node,
                    depth = 0,
                    expanded = expanded,
                    onToggle = onToggle,
                    onDayClick = onDayClick,
                )
            }
        }
    }
}

@Composable
private fun PlaceRow(
    node: FootprintNode,
    depth: Int,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
    onDayClick: (String) -> Unit,
) {
    val isExpanded = node.id in expanded
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(enabled = !node.isLeaf) { onToggle(node.id) }
                .padding(start = (depth * INDENT_STEP).dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.card),
        ) {
            IconBadge(
                icon = Icons.Filled.Place,
                contentDescription = null,
                size = BADGE_SIZE,
            )
            Text(
                text = node.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = node.count.toString(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!node.isLeaf) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    contentDescription = if (isExpanded) COLLAPSE_LABEL else EXPAND_LABEL,
                    modifier = Modifier.size(20.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (isExpanded) {
            node.children.forEach { child ->
                PlaceRow(
                    node = child,
                    depth = depth + 1,
                    expanded = expanded,
                    onToggle = onToggle,
                    onDayClick = onDayClick,
                )
            }
        }
    }
}

@Composable
private fun summaryLabel(located: Int, unlocated: Int): String =
    stringResource(R.string.footprint_summary, located, unlocated)

private val TITLE: String
    @Composable get() = stringResource(R.string.footprint_title)
private val ALL_LABEL: String
    @Composable get() = stringResource(R.string.footprint_time_all)
private val EMPTY_LABEL: String
    @Composable get() = stringResource(R.string.footprint_empty)
private val TREND_TITLE: String
    @Composable get() = stringResource(R.string.footprint_trend_title)
private val NO_TREND_LABEL: String
    @Composable get() = stringResource(R.string.footprint_trend_empty)
private val PLACES_TITLE: String
    @Composable get() = stringResource(R.string.footprint_list_title)
private val STAT_PROVINCES: String
    @Composable get() = stringResource(R.string.footprint_stat_provinces)
private val STAT_CITIES: String
    @Composable get() = stringResource(R.string.footprint_stat_cities)
private val STAT_COUNTRIES: String
    @Composable get() = stringResource(R.string.footprint_stat_countries)
private val STAT_BLOCKS: String
    @Composable get() = stringResource(R.string.footprint_stat_blocks)
private val STAT_DAYS: String
    @Composable get() = stringResource(R.string.footprint_stat_days)
private val EXPAND_LABEL: String
    @Composable get() = stringResource(R.string.a11y_expand)
private val COLLAPSE_LABEL: String
    @Composable get() = stringResource(R.string.a11y_collapse)

/** 图表度量（与设计稿一致）。 */
private val TREND_HEIGHT = 132.dp
private val AXIS_WIDTH = 22.dp
private val AXIS_LABEL_HEIGHT = 18.dp
private const val BAR_WIDTH_RATIO = 0.55f
private val BAR_MAX_WIDTH = 48.dp

/** 横屏并排版式的度量（方案 §9.2）。 */
private val INDENT_STEP = 16
private val BADGE_SIZE = 32.dp
