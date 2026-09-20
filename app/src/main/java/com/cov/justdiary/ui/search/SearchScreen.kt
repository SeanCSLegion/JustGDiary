package com.cov.justdiary.ui.search

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Place
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.cov.justdiary.model.LocOption
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.res.stringResource
import com.cov.justdiary.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.cov.justdiary.data.SearchFilterItem
import com.cov.justdiary.data.SearchUiState
import com.cov.justdiary.data.TimeRangeKind
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.LocFilter
import com.cov.justdiary.model.SearchResultItem
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.SearchUtil
import com.cov.justdiary.ui.components.EmptyState
import com.cov.justdiary.ui.components.PageHeader
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing
import androidx.compose.material.icons.filled.Search
import java.time.Instant
import java.time.LocalDate

/**
 * 搜索页。对应 iOS `Views/Search/SearchView.swift`（582 行）+ `SearchViewModel`。
 *
 * 结构：页头（含结果数）→ 搜索框 → **关键词胶囊行** → 时间/地点筛选 → 生效条件摘要 → 结果列表。
 *
 * 与 iOS 一致的关键行为：
 *  - 输入 250ms 防抖后查询（防抖在调用方；这里只暴露 `onKeywordChange`）；
 *  - 回车把当前词提交为一枚**可单独点掉的胶囊**，多词之间是"与"；
 *  - 命中片段用 `primary` 前景 + `primaryContainer` 背景高亮；
 *  - 结果分页 `limit 200` + "加载更多"；
 *  - 三态空状态：索引中 / 有条件无结果 / 无条件。
 *
 * 地点筛选：**未记录 / 国家 + 一级地区**。完整选择器是一个对话框，
 * 选项来自 `DiaryRepository.getLocationOptions`（按条数降序、带本地搜索、标注"仅国家"），
 * 与 iOS 的 `showLocSheet` 等价。
 */
@Composable
fun SearchScreen(
    state: SearchUiState,
    filterItems: List<SearchFilterItem>,
    modifier: Modifier = Modifier,
    onKeywordChange: (String) -> Unit = {},
    onCommitKeyword: () -> Unit = {},
    onRemoveKeyword: (String) -> Unit = {},
    onClearAll: () -> Unit = {},
    onTimeKindChange: (TimeRangeKind) -> Unit = {},
    onNoLocationChange: (Boolean) -> Unit = {},
    onLoadMore: () -> Unit = {},
    onOpenDiary: (String) -> Unit = {},
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

    // 结果行无障碍文案里的日期/时刻：**必须**走 `DateFormats` 的语义槽位（design-system §4），
    // 不能自己拼 —— 日期要跟随应用内语言，小时制要跟随设备。应用内语言由
    // `AppCompatDelegate.setApplicationLocales` 生效，因此按"跟随系统"装配即可拿到当前 Locale。
    val context = LocalContext.current
    val formats = remember(context) { DateFormats.forContext(context, AppSettings.LANGUAGE_SYSTEM) }

    // `Modifier.semantics {}` 的 lambda 不是 @Composable，文案得先取出来再用。
    val placeholderLabel = PLACEHOLDER
    if (showLocPicker) {
        LocationPickerDialog(
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

    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = TITLE,
            // 还没查过（total < 0）或索引在重建时不显示计数
            subtitle = if (state.total < 0) null else resultCountLabel(state.total),
        )

        // 搜索框
        OutlinedTextField(
            value = state.keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page)
                // 规范 §9.2：搜索框"现在无焦点时缺语义" —— `placeholder` 只在聚焦/为空时才渲染，
                // 未聚焦时这一格在无障碍树里没有可读的名字。这里显式把占位文案挂成节点的
                // 内容描述，TalkBack 扫过时能听到"搜索日记内容…"。
                //
                // 说明：Compose 1.12（BOM 2026.09.00）的 `Role` 只有 Button/Checkbox/Switch/
                // RadioButton/Tab/Image/DropdownList/ValuePicker，**没有** search 档位，硬套
                // 任何一个都会说谎；搜索语义由下方 `imeAction = ImeAction.Search` 承担，
                // 节点本身仍是 `EditText`（`OutlinedTextField` 自带 EditableText 语义）。
                .semantics { contentDescription = placeholderLabel },
            singleLine = true,
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

        // 关键词胶囊行（每个可单独点掉）
        if (state.keywords.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.page, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                state.keywords.forEach { word ->
                    InputChip(
                        selected = true,
                        onClick = { onRemoveKeyword(word) },
                        label = { Text(word) },
                        trailingIcon = { Icon(Icons.Filled.Clear, contentDescription = REMOVE_LABEL) },
                    )
                }
                TextButton(onClick = onClearAll) { Text(CLEAR_ALL_LABEL) }
            }
        }

        // 时间 / 地点两组条件：横屏左右并排（省约 60dp 高度），竖屏上下堆叠
        if (LocalAdaptiveLayout.current.filtersSideBySide) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.page),
                horizontalArrangement = Arrangement.spacedBy(Spacing.page),
            ) {
                TimeFilterRow(
                    state = state,
                    onTimeKindChange = onTimeKindChange,
                    modifier = Modifier.weight(1f),
                )
                LocationFilterRow(
                    state = state,
                    onNoLocationChange = onNoLocationChange,
                    modifier = Modifier.weight(1f),
                    onResetLocation = onResetLocation,
                    onOpenLocPicker = {
                        onLocSearchChange("")
                        onOpenLocPicker()
                        showLocPicker = true
                    },
                )
            }
        } else {
            TimeFilterRow(state = state, onTimeKindChange = onTimeKindChange)
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

        // 生效条件摘要
        if (filterItems.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = Spacing.page, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                filterItems.forEach { item ->
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(Radius.field))
                            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        when {
            // ① 索引**真的**在重建（规范 §9.5）：只有这时才转圈
            state.indexNotReady -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(PREPARING_LABEL, style = MaterialTheme.typography.bodySmall)
                }
            }

            // ② 未输入条件：引导态（不是"没结果"）
            state.isEmpty && !state.hasAnyCondition -> EmptyState(
                icon = Icons.Filled.Search,
                text = START_TITLE,
                hint = START_HINT,
                modifier = Modifier.weight(1f, fill = false),
            )

            // ③ 有条件但无结果：必须给一个"清除全部条件"的出口，否则只能一颗颗点掉胶囊
            state.isEmpty -> EmptyState(
                icon = Icons.Filled.Search,
                text = NO_RESULT_LABEL,
                actionLabel = CLEAR_ALL_LABEL,
                onAction = onClearAll,
                modifier = Modifier.weight(1f, fill = false),
            )

            else -> LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(
                    start = Spacing.page,
                    end = Spacing.page,
                    top = 4.dp,
                    bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                items(state.items, key = { it.id }) { result ->
                    SearchResultRow(
                        result = result,
                        keyword = state.keywords.joinToString(" "),
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

/** 时间档位的胶囊行（可横向滚动）。 */
/** 地点选择器：本地搜索 + "未记录地点" + 按条数降序的国家/地区列表。 */
@Composable
private fun LocationPickerDialog(
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
    val trimmed = keyword.trim().lowercase()
    val filtered = if (trimmed.isEmpty()) {
        options
    } else {
        options.filter {
            it.country.lowercase().contains(trimmed) || it.region1.lowercase().contains(trimmed)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(LOC_TITLE) },
        text = {
            Column {
                OutlinedTextField(
                    value = keyword,
                    onValueChange = onKeywordChange,
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    placeholder = { Text(LOC_SEARCH_PLACEHOLDER) },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                )
                Text(
                    text = LOC_SORT_HINT,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
                if (loading) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp))
                        Text(PREPARING_LABEL, style = MaterialTheme.typography.bodySmall)
                    }
                    return@Column
                }
                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
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
                    if (filtered.isEmpty()) {
                        item {
                            Text(
                                text = LOC_EMPTY,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 12.dp),
                            )
                        }
                    }
                    items(filtered, key = { it.country + "\u0001" + it.region1 }) { option ->
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
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(CANCEL_LABEL) }
        },
    )
}

@Composable
private fun LocOptionRow(label: String, count: Int?, selected: Boolean, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
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

@Composable
private fun TimeFilterRow(
    state: SearchUiState,
    onTimeKindChange: (TimeRangeKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        timeRangeOptions().forEach { (kind, label) ->
            FilterChip(
                selected = state.timeKind == kind,
                onClick = { onTimeKindChange(kind) },
                label = { Text(label) },
            )
        }
    }
}

/** 地点条件的胶囊行。完整选择器（国家 + 一级地区）接仓库的地点候选，见 `SearchViewModel`。 */
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
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = state.locFilter.country.isEmpty() && !state.locFilter.noLoc,
            onClick = {
                onNoLocationChange(false)
                onResetLocation()
            },
            label = { Text(ALL_LOC_LABEL) },
        )
        FilterChip(
            selected = state.locFilter.noLoc,
            onClick = { onNoLocationChange(true) },
            label = { Text(NO_LOCATION_LABEL_) },
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
                trailingIcon = { Icon(Icons.Filled.Clear, contentDescription = REMOVE_LABEL) },
            )
        } else if (!state.locFilter.noLoc) {
            FilterChip(
                selected = false,
                onClick = onOpenLocPicker,
                label = { Text(PICK_LOC_LABEL) },
                leadingIcon = { Icon(Icons.Filled.Place, contentDescription = null) },
            )
        }
    }
}

@Composable
private fun SearchResultRow(
    result: SearchResultItem,
    keyword: String,
    formats: DateFormats,
    onClick: () -> Unit,
) {
    // 规范 §9.6：整行只给**一个**语义节点，描述 = 日期 · 片段 · 时刻（`9月20日 · 六点半就醒了… · 8:23`）。
    // 片段里带 `<hl>…</hl>` 高亮标记，读屏不该把标签本身念出来，先剥掉。
    // 时刻用 `updatedUtc`（结果行上唯一可用的时间戳），走 `DateFormats` 槽位。
    val description = remember(result, formats) {
        val date = runCatching { LocalDate.parse(result.dayKey) }.getOrNull()
        val dateLabel = date?.let { formats.dateOnly(it) } ?: result.dayKey
        val body = (if (result.snippet.isNotEmpty()) result.snippet else result.summary)
            .replace(SearchUtil.HIGHLIGHT_OPEN, "")
            .replace(SearchUtil.HIGHLIGHT_CLOSE, "")
            .trim()
        val timeLabel = formats.timeOf(Instant.ofEpochMilli(result.updatedUtc))
        buildList {
            add(dateLabel)
            if (body.isNotEmpty()) add(body)
            add(timeLabel)
        }.joinToString(" · ")
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            // §11：列表行的触控目标 ≥ 48dp。内容本身约 60dp，这里只是兜底，视觉密度不变。
            .heightIn(min = 48.dp)
            .clip(RoundedCornerShape(Radius.field))
            // §9.6：原来是 `onTapGesture` 式处理，没有按钮特质；给出 Role.Button 后
            // TalkBack/uiautomator 都能识别成可点击的按钮。涟漪仍用 `clickable` 的默认实现。
            .clickable(
                role = Role.Button,
                onClick = onClick,
            )
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.card)
            // §9.6 + §11：清掉子节点（日期徽章、摘要、片段、chevron）各自的文本，整行只留
            // 这一条描述。点击动作与 Role 来自**外层** `clickable`，`clearAndSetSemantics`
            // 只清它下游的语义，因此会保留（同一写法见 `CalendarDayGrid`，已上机 dump 验证）。
            .clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(Spacing.card),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(Radius.badge))
                        .background(MaterialTheme.colorScheme.primaryContainer)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                ) {
                    Text(
                        text = result.dayKey,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
            }
            Text(
                text = result.summary,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (result.snippet.isNotEmpty()) {
                Text(
                    text = highlightSnippet(result.snippet),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
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

/**
 * 把 `<hl>…</hl>` 标记渲染成高亮文本。
 *
 * 与 iOS 一致：命中片段的**前景用 `primary`、背景用 `primaryContainer`**。
 */
@Composable
private fun highlightSnippet(snippet: String): AnnotatedString {
    if (!snippet.contains(SearchUtil.HIGHLIGHT_OPEN)) return AnnotatedString(snippet)
    val background = MaterialTheme.colorScheme.primaryContainer
    val foreground = MaterialTheme.colorScheme.primary
    val segments = SearchUtil.highlightSnippet(snippet)
    return buildAnnotatedString {
        segments.forEach { segment ->
            if (segment.hit) {
                withStyle(SpanStyle(background = background, color = foreground)) { append(segment.text) }
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
private val CANCEL_LABEL: String
    @Composable get() = stringResource(R.string.cancel)
private val TITLE: String
    @Composable get() = stringResource(R.string.search_title)
private val PLACEHOLDER: String
    @Composable get() = stringResource(R.string.search_placeholder)
private val CLEAR_LABEL: String
    @Composable get() = stringResource(R.string.search_time_clear)
private val CLEAR_ALL_LABEL: String
    @Composable get() = stringResource(R.string.search_clear_all)
private val REMOVE_LABEL: String
    @Composable get() = stringResource(R.string.a11y_remove)
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
