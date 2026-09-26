package com.cov.justdiary.ui.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.data.SearchFilterItem
import com.cov.justdiary.data.SearchUiState
import com.cov.justdiary.data.TimeRangeKind
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.LocFilter
import com.cov.justdiary.model.LocOption
import com.cov.justdiary.model.SearchResultItem
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.SearchUtil
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.components.CountBadge
import com.cov.justdiary.ui.components.DateRangeSheet
import com.cov.justdiary.ui.components.DiaryCard
import com.cov.justdiary.ui.components.EmptyState
import com.cov.justdiary.ui.components.InputPill
import com.cov.justdiary.ui.components.PageHeader
import com.cov.justdiary.ui.components.SkeletonRows
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing
import java.time.Instant
import java.time.LocalDate

/**
 * 搜索页。对应 iOS `Views/Search/SearchView.swift`，规范见
 * [`docs/android-design/05-search-footprint-settings.md`](../../../../../docs/android-design/05-search-footprint-settings.md) §1。
 *
 * 竖屏结构：页头（+ 计数徽标）→ 搜索框 → 筛选卡（时间范围 / 地点两组）→
 * 条件摘要行（可逐条撤销）→ 结果列表。
 * 平板横屏：左栏 340dp 固定放筛选，右栏放结果（筛选常驻可见，不需要再开面板）。
 *
 * ## 与 iOS 一致的关键行为
 *
 *  - 输入 250ms 防抖后查询；回车把当前词提交为一枚**可单独点掉的胶囊**，多词之间是"与"；
 *  - 命中片段用 `primaryContainer` 底色高亮；
 *  - 结果分页 200 + "加载更多"；
 *  - 三态空状态：索引重建中 / 有条件无结果 / 无条件。
 *
 * ## 本轮修掉的三处 iOS 缺陷
 *
 *  1. **加载态可见**（§1.5 规则 2）：iOS 的加载分支是死代码，搜索进行中列表空白。这里给
 *     顶部 `LinearProgressIndicator` + 骨架行。
 *  2. **撤销条件使在途请求失效**（§1.5 规则 3）：`SearchViewModel.runSearchNow` 每次都
 *     `searchJob?.cancel()`，旧结果不会再写回。
 *  3. **「索引重建中」与「无结果」严格区分**：只由 `indexing` 决定，与 `total` 无关。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    filterItems: List<SearchFilterItem>,
    modifier: Modifier = Modifier,
    /** 应用内语言：结果行的日期 / 时刻格式跟随它，而不是系统语言。 */
    appLanguage: String = AppSettings.LANGUAGE_SYSTEM,
    onKeywordChange: (String) -> Unit = {},
    onCommitKeyword: () -> Unit = {},
    onRemoveKeyword: (String) -> Unit = {},
    onClearAll: () -> Unit = {},
    onTimeKindChange: (TimeRangeKind) -> Unit = {},
    onNoLocationChange: (Boolean) -> Unit = {},
    onLoadMore: () -> Unit = {},
    /** 查询失败后的「重试」（重新跑一次当前条件）。 */
    onRetry: () -> Unit = {},
    onOpenDiary: (String) -> Unit = {},
    /** 自定义时间范围（`search_time_apply`）：两端都是 `YYYY-MM-DD`。 */
    onApplyCustomRange: (from: String, to: String) -> Unit = { _, _ -> },
    /** 地点选择器的候选（打开时由 `SearchViewModel.loadLocationOptions` 拉取）。 */
    locOptions: List<LocOption> = emptyList(),
    noLocCount: Int = 0,
    locLoading: Boolean = false,
    locSearch: String = "",
    onLocSearchChange: (String) -> Unit = {},
    onOpenLocPicker: () -> Unit = {},
    onSelectLocation: (country: String, region1: String) -> Unit = { _, _ -> },
    onResetLocation: () -> Unit = {},
) {
    var showLocPicker by remember { mutableStateOf(false) }
    var showCustomRange by remember { mutableStateOf(false) }
    var searchFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current

    // 结果行无障碍文案里的日期/时刻：**必须**走 `DateFormats` 的语义槽位，
    // 不能自己拼 —— 日期要跟随应用内语言，小时制要跟随设备。
    val context = LocalContext.current
    // ⚠️ 用**应用内语言**而不是系统语言：模板字符串走 `context.getString`（受
    // `setApplicationLocales` 影响），月份/星期名如果取系统语言就会混搭出「9月 8」这种
    // 中英夹心；而且 `remember(context)` 在应用内切语言时不会失效。
    val formats = remember(appLanguage) { DateFormats.forContext(context, appLanguage) }
    val split = LocalAdaptiveLayout.current.splitsMasterDetail

    if (showLocPicker) {
        LocationPickerSheet(
            options = locOptions,
            noLocCount = noLocCount,
            loading = locLoading,
            keyword = locSearch,
            selected = state.locFilter,
            onKeywordChange = onLocSearchChange,
            onPick = { country, region -> onSelectLocation(country, region); showLocPicker = false },
            onPickNoLoc = { onNoLocationChange(true); showLocPicker = false },
            onPickAll = { onResetLocation(); showLocPicker = false },
            onDismiss = { showLocPicker = false },
        )
    }
    if (showCustomRange) {
        DateRangeSheet(
            initialFrom = state.customRange?.from,
            initialTo = state.customRange?.to,
            onQuickPick = { kind -> onTimeKindChange(kind); showCustomRange = false },
            onApply = { from, to -> onApplyCustomRange(from, to); showCustomRange = false },
            onClear = { onTimeKindChange(TimeRangeKind.All); showCustomRange = false },
            onDismiss = { showCustomRange = false },
        )
    }

    val countLabel: String? = if (state.total > 0) resultCountLabel(state.total) else null

    /** 筛选卡：搜索框之外的唯一筛选入口（两组 FilterChip）。 */
    @Composable
    fun filterPanel(modifier: Modifier = Modifier) {
        DiaryCard(modifier = modifier, contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 16.dp)) {
            Column {
                FilterGroupLabel(LABEL_TIME_RANGE)
                TimeFilterRow(
                    state = state,
                    onTimeKindChange = onTimeKindChange,
                    onOpenCustomRange = { showCustomRange = true },
                )
                FilterGroupLabel(LABEL_LOCATION, topPadding = 12.dp)
                LocationFilterRow(
                    state = state,
                    onNoLocationChange = onNoLocationChange,
                    onResetLocation = onResetLocation,
                    onOpenLocPicker = {
                        onLocSearchChange("")
                        onOpenLocPicker()
                        showLocPicker = true
                    },
                )
            }
        }
    }

    // `semantics {}` 的 lambda 不是 @Composable，占位文案要先取出来
    val placeholderLabel = PLACEHOLDER

    @Composable
    fun searchField(modifier: Modifier = Modifier) {
        Row(
            modifier = modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            OutlinedTextField(
                value = state.keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier
                    .weight(1f)
                    .onFocusChanged { searchFocused = it.isFocused }
                    .semantics { contentDescription = placeholderLabel },
                singleLine = true,
                shape = CircleShape,
                textStyle = MaterialTheme.typography.bodyLarge,
                placeholder = { Text(PLACEHOLDER) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                trailingIcon = {
                    if (state.keyword.isNotEmpty()) {
                        IconButton(onClick = { onKeywordChange("") }) {
                            Icon(Icons.Filled.Clear, contentDescription = CLEAR_LABEL)
                        }
                    }
                },
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { onCommitKeyword() }),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    imeAction = androidx.compose.ui.text.input.ImeAction.Search,
                ),
            )
            // 聚焦时右侧**追加**「取消」（规范 §1.2）：清空输入并收起键盘。
            // 没聚焦时不占位 —— iOS 那边也是靠出现/消失来提示"现在可以退出搜索"。
            if (searchFocused) {
                TextButton(
                    onClick = {
                        onKeywordChange("")
                        focusManager.clearFocus()
                    },
                ) { Text(CANCEL_LABEL) }
            }
        }
    }

    /** 生效条件摘要：**每一条都能单独撤销**，最右是「清除全部」。 */
    @Composable
    fun summaryRow(modifier: Modifier = Modifier) {
        if (filterItems.isEmpty()) return
        Row(
            modifier = modifier
                .fillMaxWidth()
                .heightIn(min = 44.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier
                    .weight(1f)
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                filterItems.forEach { item ->
                    InputPill(
                        text = item.label,
                        onRemove = {
                            when (item.kind) {
                                // 未提交的输入：清空输入框（防抖会自己重查）
                                SearchFilterItem.Kind.KeywordInput -> onKeywordChange("")
                                SearchFilterItem.Kind.Keyword -> onRemoveKeyword(item.label)
                                SearchFilterItem.Kind.Time -> onTimeKindChange(TimeRangeKind.All)
                                SearchFilterItem.Kind.Location -> {
                                    onNoLocationChange(false)
                                    onResetLocation()
                                }
                            }
                        },
                    )
                }
            }
            IconButton(onClick = onClearAll) {
                Icon(Icons.Filled.Clear, contentDescription = CLEAR_ALL_LABEL)
            }
        }
    }

    /** 结果区（空态 / 加载 / 列表）。 */
    @Composable
    fun results(modifier: Modifier = Modifier) {
        Box(modifier = modifier.fillMaxSize()) {
            when {
                // ① 索引**真的**在重建：只有这时才是"准备中"，与"无结果"严格区分
                state.indexNotReady -> Column(
                    modifier = Modifier.fillMaxSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                ) {
                    Text(
                        text = PREPARING_LABEL,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    LinearProgressIndicator(
                        modifier = Modifier
                            .padding(top = 12.dp)
                            .width(160.dp),
                    )
                }

                // ② 搜索进行中：进度条 + 骨架行（iOS 在这里是**空白**）
                state.loading && state.items.isEmpty() -> Column(modifier = Modifier.fillMaxSize()) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    SkeletonRows()
                }

                // ③ 出错：**不能**显示成"没有找到"
                // （查库失败与真的没有是两件事；混在一起会让用户以为自己没写过）
                state.hasError -> EmptyState(
                    icon = Icons.Filled.Warning,
                    text = ERROR_LABEL,
                    hint = state.error,
                    actionLabel = RETRY_LABEL,
                    onAction = onRetry,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                // ④ 未输入条件：引导态（不是"没结果"）
                state.isEmpty && !state.hasAnyCondition -> EmptyState(
                    icon = Icons.Filled.Search,
                    text = START_TITLE,
                    hint = START_HINT,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                // ⑤ 有条件但无结果：必须给一个"清除全部条件"的出口
                state.isEmpty -> EmptyState(
                    icon = Icons.Filled.Search,
                    text = NO_RESULT_LABEL,
                    actionLabel = CLEAR_ALL_LABEL,
                    onAction = onClearAll,
                    modifier = Modifier.align(Alignment.TopCenter),
                )

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 4.dp, bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (state.loading) {
                        item { LinearProgressIndicator(modifier = Modifier.fillMaxWidth()) }
                    }
                    items(state.items, key = { it.id }) { result ->
                        SearchResultRow(
                            result = result,
                            formats = formats,
                            onClick = { onOpenDiary(result.dayKey) },
                        )
                    }
                    if (state.hasMore) {
                        item {
                            TextButton(
                                onClick = onLoadMore,
                                modifier = Modifier.fillMaxWidth(),
                            ) { Text(LOAD_MORE_LABEL) }
                        }
                    }
                }
            }
        }
    }

    if (split) {
        // 平板横屏：左栏固定 340dp 放筛选（常驻可见），右栏结果列表
        Row(modifier = modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .width(340.dp)
                    .fillMaxHeight()
                    .padding(horizontal = Spacing.page, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                PageHeader(title = TITLE, trailing = { countLabel?.let { CountBadge(it) } })
                searchField()
                filterPanel(modifier = Modifier.fillMaxWidth())
                // 分栏时**不显示**条件摘要行（规范 §1.4/§1.8）：左栏的 chip 组已经表达了全部条件
            }
            HorizontalDivider(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(1.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            Box(modifier = Modifier.weight(1f).padding(horizontal = Spacing.page, vertical = 8.dp)) {
                results()
            }
        }
    } else {
        Column(modifier = modifier.fillMaxSize()) {
            // 页头自带 16dp 内边距，因此它不套在外层的内边距里（曾经用负 padding 抵消，
            // 那是非法的 —— Compose 的 padding 不接受负值）
            PageHeader(title = TITLE, trailing = { countLabel?.let { CountBadge(it) } })
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.page),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                searchField()
                filterPanel()
                summaryRow()
            }
            results(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.page, vertical = 12.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------- 筛选控件

@Composable
private fun FilterGroupLabel(text: String, topPadding: androidx.compose.ui.unit.Dp = 0.dp) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = topPadding, bottom = 8.dp),
    )
}

/** 时间档位的胶囊行（「自定义」打开 ModalBottomSheet，不再是一个点不动的空档）。 */
@Composable
private fun TimeFilterRow(
    state: SearchUiState,
    onTimeKindChange: (TimeRangeKind) -> Unit,
    onOpenCustomRange: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 单行横滑（用户反馈：放在 scrollview 里左右滑动，**不要做成多排**）
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        timeRangeOptions().forEach { (kind, label) ->
            FilterChip(
                selected = state.timeKind == kind,
                onClick = { if (kind == TimeRangeKind.Custom) onOpenCustomRange() else onTimeKindChange(kind) },
                label = { Text(label) },
                leadingIcon = if (state.timeKind == kind) {
                    { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                } else {
                    null
                },
            )
        }
    }
}

/** 地点条件的胶囊行。 */
@Composable
private fun LocationFilterRow(
    state: SearchUiState,
    onNoLocationChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    onResetLocation: () -> Unit = {},
    onOpenLocPicker: () -> Unit = {},
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(
            selected = state.locFilter.country.isEmpty() && !state.locFilter.noLoc,
            onClick = {
                onNoLocationChange(false)
                onResetLocation()
            },
            label = { Text(ALL_LOC_LABEL) },
            leadingIcon = if (state.locFilter.country.isEmpty() && !state.locFilter.noLoc) {
                { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
            } else {
                null
            },
        )
        if (state.locFilter.country.isNotEmpty()) {
            FilterChip(
                selected = true,
                onClick = onResetLocation,
                label = {
                    Text(
                        listOf(state.locFilter.country, state.locFilter.region1)
                            .filter { it.isNotEmpty() }
                            .joinToString(" · "),
                    )
                },
                leadingIcon = { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) },
            )
        } else {
            FilterChip(
                selected = state.locFilter.noLoc,
                onClick = { if (state.locFilter.noLoc) onNoLocationChange(false) else onOpenLocPicker() },
                label = { Text(if (state.locFilter.noLoc) NO_LOCATION_LABEL_ else PICK_LOC_LABEL) },
                leadingIcon = when {
                    state.locFilter.noLoc -> {
                        { Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    }
                    else -> {
                        { Icon(Icons.Filled.Place, contentDescription = null, modifier = Modifier.size(18.dp)) }
                    }
                },
            )
        }
    }
}

// ---------------------------------------------------------------------- 两个 ModalBottomSheet

/** 自定义时间范围：三个快捷档 + 起止日期。 */
/** 地点选择器：从 `AlertDialog` 改成 `ModalBottomSheet`（规范 §1.6）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationPickerSheet(
    options: List<LocOption>,
    noLocCount: Int,
    loading: Boolean,
    keyword: String,
    selected: LocFilter,
    onKeywordChange: (String) -> Unit,
    onPick: (country: String, region1: String) -> Unit,
    onPickNoLoc: () -> Unit,
    onPickAll: () -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    // `options` 已经是**按当前关键词查过库**的结果（`SearchViewModel.setLocSearch` →
    // `loadLocationOptions(keyword)` → SQL LIKE）。不要在客户端再过滤一遍：
    // iOS 就是 Top20 + 弹窗内过滤，榜外的地点永远搜不到（README §6 缺陷 7）。
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(LOC_TITLE, style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                value = keyword,
                onValueChange = onKeywordChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp),
                singleLine = true,
                shape = CircleShape,
                placeholder = { Text(LOC_SEARCH_PLACEHOLDER) },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
            )
            Text(
                text = LOC_SORT_HINT,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp),
            )
            if (loading) {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                )
                return@Column
            }
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 360.dp)
                    .padding(top = 8.dp),
            ) {
                item {
                    LocOptionRow(
                        label = LOC_ALL,
                        count = null,
                        selected = selected.country.isEmpty() && !selected.noLoc,
                        onClick = onPickAll,
                    )
                }
                item {
                    LocOptionRow(
                        label = NO_LOCATION_LABEL_,
                        count = noLocCount,
                        selected = selected.noLoc,
                        onClick = onPickNoLoc,
                    )
                }
                if (options.isEmpty()) {
                    item {
                        Text(
                            text = LOC_EMPTY,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                }
                items(options, key = { it.country + "\u0001" + it.region1 }) { option ->
                    LocOptionRow(
                        label = if (option.region1.isEmpty()) {
                            "${option.country} · $LOC_COUNTRY_ONLY"
                        } else {
                            "${option.country} · ${option.region1}"
                        },
                        count = option.count,
                        selected = selected.country == option.country && selected.region1 == option.region1,
                        onClick = { onPick(option.country, option.region1) },
                    )
                }
                item { Box(modifier = Modifier.height(24.dp)) }
            }
        }
    }
}

@Composable
private fun LocOptionRow(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.field))
            .clickable(onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (count != null) {
            Text(
                text = count.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (selected) {
            Icon(
                imageVector = Icons.Filled.Check,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 6.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------- 结果行

@Composable
private fun SearchResultRow(
    result: SearchResultItem,
    formats: DateFormats,
    onClick: () -> Unit,
) {
    val date = remember(result.dayKey) { runCatching { LocalDate.parse(result.dayKey) }.getOrNull() }
    // 规范 §1.7：日期徽标用**本地化**日期（原来是 `2026-09-20` 原文）
    val dateLabel = date?.let { formats.dateOnly(it) } ?: result.dayKey
    val timeLabel = remember(result.updatedUtc, formats) {
        formats.timeOf(Instant.ofEpochMilli(result.updatedUtc))
    }
    val description = remember(result) {
        val body = (if (result.snippet.isNotEmpty()) result.snippet else result.summary)
            .replace(SearchUtil.HIGHLIGHT_OPEN, "")
            .replace(SearchUtil.HIGHLIGHT_CLOSE, "")
            .trim()
        buildList {
            add(dateLabel)
            if (body.isNotEmpty()) add(body)
            add(timeLabel)
        }.joinToString(" · ")
    }

    DiaryCard(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp),
        onClick = onClick,
        // 整行只留**一条**语义：清掉子节点各自的文本。
        // ⚠️ 必须走 `contentModifier`（在 clickable **之内**）——放在外层 modifier 上
        // 会把 clickable 的 onClick 与 Role 一并清掉，TalkBack 能读但按不动。
        contentModifier = Modifier.clearAndSetSemantics { contentDescription = description },
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.card),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    CountBadge(dateLabel, leading = Icons.Filled.DateRange)
                    Text(
                        text = timeLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = result.summary,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 8.dp),
                )
                if (result.snippet.isNotEmpty()) {
                    Text(
                        text = highlightSnippet(result.snippet),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * 把 `<hl>…</hl>` 标记渲染成高亮文本。
 *
 * 规范 §1.7：命中片段只加 `primaryContainer` **底色**，不加粗、不变色。
 * 页内搜索（日记页）用的是同一套，两处必须一致。
 */
@Composable
private fun highlightSnippet(snippet: String): AnnotatedString {
    if (!snippet.contains(SearchUtil.HIGHLIGHT_OPEN)) return AnnotatedString(snippet)
    val background = MaterialTheme.colorScheme.primaryContainer
    val segments = SearchUtil.highlightSnippet(snippet)
    return buildAnnotatedString {
        segments.forEach { segment ->
            if (segment.hit) {
                // 只加底色：规范 §1.7 明确「不用加粗、不用变色」——
                // 改字色会和"链接/可点"的视觉语义打架
                withStyle(SpanStyle(background = background)) { append(segment.text) }
            } else {
                append(segment.text)
            }
        }
    }
}

@Composable
private fun resultCountLabel(total: Int): String =
    stringResource(R.string.search_result_count, total)

/** 时间四档的标签（`@Composable`：文案跟随应用内语言）。 */
@Composable
private fun timeRangeOptions(): List<Pair<TimeRangeKind, String>> = listOf(
    TimeRangeKind.All to stringResource(R.string.search_all_time),
    TimeRangeKind.ThisWeek to stringResource(R.string.search_time_week),
    TimeRangeKind.ThisMonth to stringResource(R.string.search_time_month),
    TimeRangeKind.ThisYear to stringResource(R.string.search_time_year),
    TimeRangeKind.Custom to stringResource(R.string.search_time_custom),
)

private val ALL_LOC_LABEL: String
    @Composable get() = stringResource(R.string.search_all_loc)
private val PICK_LOC_LABEL: String
    @Composable get() = stringResource(R.string.search_loc_picker)
private val LOC_TITLE: String
    @Composable get() = stringResource(R.string.search_loc_title)
private val LOC_SEARCH_PLACEHOLDER: String
    @Composable get() = stringResource(R.string.search_loc_search)
private val LOC_SORT_HINT: String
    @Composable get() = stringResource(R.string.search_loc_sort_hint)
private val LOC_ALL: String
    @Composable get() = stringResource(R.string.search_all_loc)
private val LOC_COUNTRY_ONLY: String
    @Composable get() = stringResource(R.string.search_loc_country_only)
private val LOC_EMPTY: String
    @Composable get() = stringResource(R.string.search_loc_empty)
private val TITLE: String
    @Composable get() = stringResource(R.string.search_title)
private val PLACEHOLDER: String
    @Composable get() = stringResource(R.string.search_placeholder)
private val CLEAR_LABEL: String
    @Composable get() = stringResource(R.string.search_time_clear)
private val CLEAR_ALL_LABEL: String
    @Composable get() = stringResource(R.string.search_clear_all)
private val ERROR_LABEL: String
    @Composable get() = stringResource(R.string.search_error)
private val RETRY_LABEL: String
    @Composable get() = stringResource(R.string.search_retry)
private val NO_LOCATION_LABEL_: String
    @Composable get() = stringResource(R.string.search_loc_no_loc)
private val PREPARING_LABEL: String
    @Composable get() = stringResource(R.string.search_preparing)
private val START_TITLE: String
    @Composable get() = stringResource(R.string.search_start_title)
private val START_HINT: String
    @Composable get() = stringResource(R.string.search_start_hint)
private val NO_RESULT_LABEL: String
    @Composable get() = stringResource(R.string.search_no_result)
private val LOAD_MORE_LABEL: String
    @Composable get() = stringResource(R.string.search_load_more)
private val SHEET_TIME_TITLE: String
    @Composable get() = stringResource(R.string.search_time_title)
private val LABEL_TIME_RANGE: String
    @Composable get() = stringResource(R.string.search_time_label)
private val LABEL_LOCATION: String
    @Composable get() = stringResource(R.string.search_loc_label)
private val LABEL_RANGE_FROM: String
    @Composable get() = stringResource(R.string.search_time_from)
private val LABEL_RANGE_TO: String
    @Composable get() = stringResource(R.string.search_time_to)
private val APPLY_LABEL: String
    @Composable get() = stringResource(R.string.search_time_apply)
private val CANCEL_LABEL: String
    @Composable get() = stringResource(R.string.cancel)
