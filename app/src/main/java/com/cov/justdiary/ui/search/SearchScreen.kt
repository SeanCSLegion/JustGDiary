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
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.cov.justdiary.R
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import com.cov.justdiary.model.LocFilter
import com.cov.justdiary.model.SearchResultItem
import com.cov.justdiary.service.SearchUtil
import com.cov.justdiary.ui.components.EmptyState
import com.cov.justdiary.ui.components.PageHeader
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing
import androidx.compose.material.icons.filled.Search

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
 * 地点筛选目前提供"全部 / 未记录"两档；国家+一级地区的完整选择器在 M7 接仓库后补
 * （模型层 [LocFilter] 与国家/地区选项查询在 M1/M6 已完成）。
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
) {
    Column(modifier = modifier.fillMaxSize()) {
        PageHeader(
            title = TITLE,
            subtitle = if (state.indexNotReady) null else resultCountLabel(state.total),
        )

        // 搜索框
        OutlinedTextField(
            value = state.keyword,
            onValueChange = onKeywordChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.page),
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
                )
            }
        } else {
            TimeFilterRow(state = state, onTimeKindChange = onTimeKindChange)
            LocationFilterRow(state = state, onNoLocationChange = onNoLocationChange)
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
            state.indexNotReady -> Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.size(18.dp))
                    Text(PREPARING_LABEL, style = MaterialTheme.typography.bodySmall)
                }
            }

            state.isEmpty -> EmptyState(
                icon = Icons.Filled.Search,
                text = if (state.keyword.isBlank() && state.keywords.isEmpty()) {
                    START_TITLE
                } else {
                    NO_RESULT_LABEL
                },
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
) {
    Row(
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.page),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        FilterChip(
            selected = state.locFilter.noLoc,
            onClick = { onNoLocationChange(!state.locFilter.noLoc) },
            label = { Text(NO_LOCATION_LABEL_) },
        )
        if (state.locFilter.country.isNotEmpty()) {
            FilterChip(
                selected = true,
                onClick = { onNoLocationChange(false) },
                label = {
                    Text(
                        listOf(state.locFilter.country, state.locFilter.region1)
                            .filter { it.isNotEmpty() }
                            .joinToString(" · "),
                    )
                },
                trailingIcon = { Icon(Icons.Filled.Clear, contentDescription = REMOVE_LABEL) },
            )
        }
    }
}

@Composable
private fun SearchResultRow(result: SearchResultItem, keyword: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(Radius.field))
            .clickable(onClick = onClick)
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .padding(Spacing.card),
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
private val NO_RESULT_LABEL: String
    @Composable get() = stringResource(R.string.search_no_result)
private val LOAD_MORE_LABEL: String
    @Composable get() = stringResource(R.string.search_load_more)
