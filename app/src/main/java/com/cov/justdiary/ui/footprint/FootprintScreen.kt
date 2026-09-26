package com.cov.justdiary.ui.footprint

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.cov.justdiary.data.FootprintNode
import com.cov.justdiary.data.FootprintViewState
import com.cov.justdiary.data.FootprintYearStat
import com.cov.justdiary.data.TimeRangeKind
import com.cov.justdiary.data.YEAR_FILTER_ALL
import com.cov.justdiary.service.DateFormats
import java.time.LocalDate
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.components.AppIcons
import com.cov.justdiary.ui.components.DiaryCard
import com.cov.justdiary.ui.components.EmptyState
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
    /** 时间范围四档（`TimeRangeKind`）；与年份是**交集**（规范 §2.2）。 */
    timeKind: TimeRangeKind = TimeRangeKind.All,
    customFrom: String? = null,
    customTo: String? = null,
    onTimeKindChange: (TimeRangeKind) -> Unit = {},
    onApplyCustomRange: (from: String, to: String) -> Unit = { _, _ -> },
    onClearTimeFilter: () -> Unit = {},
    onDayClick: (String) -> Unit = {},
    /** 展开的树节点（由 `FootprintViewModel` 持有：筛选变化时要剪掉已消失的节点）。 */
    expanded: Set<String> = emptySet(),
    onToggleExpand: (String) -> Unit = {},
) {
    var showTimeSheet by remember { mutableStateOf(false) }
    if (showTimeSheet) {
        FootprintTimeSheet(
            initialFrom = customFrom,
            initialTo = customTo,
            onQuickPick = { kind -> onTimeKindChange(kind); showTimeSheet = false },
            onApply = { from, to -> onApplyCustomRange(from, to); showTimeSheet = false },
            onClear = { onClearTimeFilter(); showTimeSheet = false },
            onDismiss = { showTimeSheet = false },
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = TITLE,
            // 摘要放页头右侧一行（规范 §2.1）：`locatedCount` 随筛选变、`unlocated` 是全量
            trailing = {
                Text(
                    text = summaryLabel(state.locatedCount, state.unlocatedCount),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // 限宽 + 允许折两行：这一行在 1.5× 字号下会到 ~310dp，
                    // 不限宽就会把左边的标题挤成一个字（实测 1.3× 已贴着边缘）
                    maxLines = 2,
                    textAlign = TextAlign.End,
                    modifier = Modifier.widthIn(max = 260.dp),
                )
            },
        )

        // 年份胶囊：**单行横滑**（用户反馈：要可滑动，不要做成多排）。
        // 候选项只列"当前时间筛选下真的有内容"的年份，且「自定义」是无条件保底项 ——
        // 所以即便把「自定义」推到屏幕外，它也不是唯一入口，右侧的横滑提示由内容自身给出。
        LazyRow(
            modifier = Modifier.fillMaxWidth(),
            contentPadding = PaddingValues(horizontal = Spacing.page),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // 年份芯片的选中态只看 yearFilter（规范 §2.2 的修正）：
            // iOS 那边还额外要求 timeKind == .all，于是"先选年份、再选自定义区间"之后
            // 年份芯片看起来没选中，但两层过滤其实都在生效。
            // 年份这一排是**单一选择**：点年份就把自定义时间范围清掉（并回到"全部"），
            // 避免出现「全部」和「自定义」同时打勾、而实际生效的是两者交集那种看不懂的状态。
            item {
                val on = yearFilter == YEAR_FILTER_ALL && timeKind != TimeRangeKind.Custom
                FilterChip(
                    selected = on,
                    onClick = {
                        onYearFilterChange(YEAR_FILTER_ALL)
                        onClearTimeFilter()
                    },
                    label = { Text(ALL_LABEL) },
                    leadingIcon = yearChipIcon(on),
                )
            }
            items(state.yearOptions, key = { it }) { year ->
                val on = yearFilter == year && timeKind != TimeRangeKind.Custom
                FilterChip(
                    selected = on,
                    onClick = {
                        onYearFilterChange(year)
                        onClearTimeFilter()
                    },
                    label = { Text(year) },
                    leadingIcon = yearChipIcon(on),
                )
            }
            // 保底项：任何时候都在，且永远可点。
            // 文案**固定**为「自定义」—— 具体日期只在面板里给（用户反馈：
            // 芯片后面跟着一串 2026-01-01 – 2026-01-02 太啰嗦，芯片条以年份为准）。
            item {
                val on = timeKind == TimeRangeKind.Custom
                FilterChip(
                    selected = on,
                    onClick = { showTimeSheet = true },
                    label = { Text(CUSTOM_LABEL) },
                    leadingIcon = yearChipIcon(on),
                )
            }
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
                if (state.yearly.size >= 2) {
                    TrendCard(
                        yearly = state.yearly,
                        modifier = Modifier
                            .weight(1f)
                            .height(LocalAdaptiveLayout.current.footprintBlockHeight.dp),
                    )
                }
                PlaceTreeCard(
                    nodes = state.nodes,
                    expanded = expanded,
                    onToggle = onToggleExpand,
                    onDayClick = onDayClick,
                    // 清单栏固定 300dp（规范 §2.7）
                    modifier = Modifier.width(300.dp),
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = Spacing.page,
                    end = Spacing.page,
                    bottom = 24.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(Spacing.card),
            ) {
                // 统计卡**常驻**（规范 §2.6：没有地点记录时统计卡照常显示 0，
                // 只有清单卡换成空态）。原来整页被空态取代 —— 选了个空年份时
                // 连统计和趋势都消失，用户以为页面坏了。
                item { StatsCard(state) }
                // 只有 ≥ 2 个年份时才画趋势（规范 §2.4，与 iOS 一致）：
                // 一根柱子说明不了"趋势"，而且它没有自己的空态，所以是"整张卡不出现"
                if (state.yearly.size >= 2) {
                    item {
                        TrendCard(
                            yearly = state.yearly,
                            height = LocalAdaptiveLayout.current.trendHeight.dp,
                        )
                    }
                }
                item {
                    if (state.nodes.isEmpty()) {
                        DiaryCard {
                            EmptyState(
                                icon = Icons.Filled.Place,
                                text = EMPTY_LABEL,
                            )
                        }
                    } else {
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
}

/**
 * 年份芯片的前导勾选图标（选中才出现）。
 *
 * `FilterChip` 的 `leadingIcon` 是个可空 lambda，写成函数是为了在四处调用点保持一致。
 */
@Composable
private fun yearChipIcon(selected: Boolean): (@Composable () -> Unit)? = if (selected) {
    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
} else {
    null
}

/** 自定义区间的胶囊文案：`9月1日 – 9月26日`。 */
@Composable
private fun rangeLabel(from: String, to: String): String {
    val a = runCatching { LocalDate.parse(from) }.getOrNull()
    val b = runCatching { LocalDate.parse(to) }.getOrNull()
    return if (a != null && b != null) {
        stringResource(R.string.date_range, DateFormats.dateOnlyFor(a), DateFormats.dateOnlyFor(b))
    } else {
        "$from – $to"
    }
}

/**
 * 自定义时间范围面板：与搜索页的 `CustomRangeSheet` **同构**（规范 §2.2）。
 *
 * 两处必须是同一个交互：用户在搜索页学会的"三个快捷档 + 起止日期 + 清除/确定"
 * 在足迹页要能直接用。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FootprintTimeSheet(
    initialFrom: String?,
    initialTo: String?,
    onQuickPick: (TimeRangeKind) -> Unit,
    onApply: (from: String, to: String) -> Unit,
    onClear: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val today = remember { LocalDate.now() }
    val defaultFrom = remember(today) { today.withDayOfMonth(1) }
    var from by remember { mutableStateOf(initialFrom ?: defaultFrom.toString()) }
    var to by remember { mutableStateOf(initialTo ?: today.toString()) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.search_time_title),
                style = MaterialTheme.typography.titleMedium,
            )
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = false,
                    onClick = { onQuickPick(TimeRangeKind.ThisWeek) },
                    label = { Text(stringResource(R.string.search_time_week)) },
                )
                FilterChip(
                    selected = false,
                    onClick = { onQuickPick(TimeRangeKind.ThisMonth) },
                    label = { Text(stringResource(R.string.search_time_month)) },
                )
                FilterChip(
                    selected = false,
                    onClick = { onQuickPick(TimeRangeKind.ThisYear) },
                    label = { Text(stringResource(R.string.search_time_year)) },
                )
            }
            Row(
                modifier = Modifier.padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                RangeDayField(
                    label = stringResource(R.string.search_time_from),
                    value = from,
                    onValueChange = { from = it },
                    modifier = Modifier.weight(1f),
                )
                RangeDayField(
                    label = stringResource(R.string.search_time_to),
                    value = to,
                    onValueChange = { to = it },
                    modifier = Modifier.weight(1f),
                )
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 20.dp, bottom = 24.dp),
                horizontalArrangement = Arrangement.End,
            ) {
                TextButton(onClick = onClear) { Text(stringResource(R.string.search_time_clear)) }
                Button(
                    onClick = { onApply(from, to) },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.search_time_apply)) }
            }
        }
    }
}

@Composable
private fun RangeDayField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            shape = RoundedCornerShape(Radius.field),
            textStyle = MaterialTheme.typography.bodyMedium,
            placeholder = { Text("2026-09-01") },
        )
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
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        // 方案 §八「统计卡」：整格**合并成一个语义节点**读作「省市 7」，不许拆成两个 Text。
        // 数值与标签仍各自绘制（视觉与字号完全不变），只是把子节点的文本语义清掉，
        // 换成一条「标签 + 数值」的描述，避免 TalkBack 读出「7」再读「省市」两条互不相干的节点。
        modifier = Modifier.clearAndSetSemantics {
            contentDescription = "$label $value"
        },
    ) {
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
    // 语义 lambda 不是 @Composable，标题要先在组合里取出来（`TREND_TITLE` 是 composable getter）。
    val chartLabel = TREND_TITLE

    val maxDays = (yearly.maxOfOrNull { it.days } ?: 1).coerceAtLeast(1)
    val axisStyle = TextStyle(
        fontSize = DynamicTypeMetrics.effectiveSp(TypeSize.caption, density.fontScale),
        color = labelColor,
    )

    Canvas(
        modifier = modifier
            // 方案 §八「趋势卡」：柱状图是自绘 Canvas，drawText 画出来的年份/数值**没有语义**，
            // 必须补无障碍描述。整张图只挂**一个**节点，读图表标题（§十一：统计数字、图表
            // 不只靠颜色表达）；不逐柱暴露数值，否则 TalkBack 会在卡片里刷一长串数字。
            .semantics { contentDescription = chartLabel },
    ) {
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
    val stateLabel = if (isExpanded) EXPAND_LABEL else COLLAPSE_LABEL
    Column(modifier = Modifier.fillMaxWidth()) {
        // 叶子节点（市）没有点击动作，索性不挂 `clickable`：这样它只有一条静态语义，
        // 不会被读成按钮，也不会带上多余的 disabled 状态（方案 §八：叶子节点无 chevron）。
        val rowModifier = if (node.isLeaf) {
            Modifier.fillMaxWidth()
        } else {
            Modifier
                .fillMaxWidth()
                // `role = Role.Button`：可展开行要带按钮特质（方案 §九「结果行」同一口径，
                // §十一 触控目标与语义）。点击动作来自这层 `clickable`，下面的
                // `clearAndSetSemantics` 只清子节点，不会把它清掉（与 CalendarDayGrid 同理）。
                .clickable(role = Role.Button) { onToggle(node.id) }
        }
        Row(
            modifier = rowModifier
                // 触控目标 ≥48dp（规范 01 §7）；一级 46dp、二级及以下 40dp 是设计值，
                // 但 48 是 Material 的硬要求，所以统一兜到 48
                .heightIn(min = 48.dp)
                .padding(start = (depth * INDENT_STEP).dp, top = 6.dp, bottom = 6.dp)
                // 方案 §八「地点清单」：名称 + 计数合并为**一个**节点读作「中国 9」，
                // 而不是「中国」「9」两条。可展开行再用 stateDescription 暴露展开/收起
                // （§十一 状态不只靠颜色/图形表达），叶子行不设状态、没有按钮角色。
                .clearAndSetSemantics {
                    contentDescription = "${node.name} ${node.count}"
                    if (!node.isLeaf) {
                        stateDescription = stateLabel
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.card),
        ) {
            // 树节点用 18dp 的裸图标，不用 40dp 徽标：三级树里每级都挂一个圆形徽标
            // 会让清单看起来像"一堆设置行"，也把每行的视觉重量拉高了一倍（规范 §2.5）。
            Icon(
                // 一级是国家（globe），下面两级是具体地点（place）
                imageVector = if (depth == 0) AppIcons.Globe else Icons.Filled.Place,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = node.name,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                text = node.count.toString(),
                // 规范 §2.5：计数 12sp + 等宽数字（tabular），否则逐行数字对不齐
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.widthIn(min = 24.dp),
                textAlign = TextAlign.End,
            )
            if (!node.isLeaf) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
                    // 展开/收起已由整行的 stateDescription 表达，chevron 是纯装饰
                    // （方案 §十一：装饰性图标显式 `null`），否则会与状态重复朗读。
                    contentDescription = null,
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
private val CUSTOM_LABEL: String
    @Composable get() = stringResource(R.string.search_time_custom)
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

/** 横屏并排版式的度量（规范 §2.7）。 */
private val INDENT_STEP = 16
