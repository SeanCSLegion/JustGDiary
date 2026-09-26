package com.cov.justdiary.ui.components

import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.data.DayKeyInput
import com.cov.justdiary.data.TimeRangeKind
import com.cov.justdiary.ui.theme.Radius
import java.time.LocalDate

/**
 * 自定义时间范围面板（搜索页与足迹页**共用同一个**）。
 *
 * 两处必须是**完全同构**的交互（规范 05 §1.6 / §2.2）：同一批快捷档、同样的起止输入、
 * 同样的动作顺序。此前两个页面各写了一份近似的拷贝 —— 第二次改动时必然分叉，
 * 所以在这里收成一个。
 *
 * 起止两端**带校验**：格式不对或日期不存在时在对应输入框下报错、并禁用「确定」。
 * 不做校验的后果很隐蔽 —— 范围过滤是字符串比较，`2026-9-1` 会算出空区间，
 * 界面上只表现为"没有找到匹配的日记"（见 [DayKeyInput]）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateRangeSheet(
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

    // 两端只可能来自日历选择，用户已经没有"手打错"的路径了。
    // 这里仍走一遍归一化：面板可能被传入**历史脏值**（旧版本存的 `2026-9-1`），
    // 那种值一样会让字符串比较算出空区间。
    val normalized = DayKeyInput.normalizeRange(from, to)

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.padding(horizontal = 24.dp)) {
            Text(
                text = stringResource(R.string.search_time_title),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
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
                    // 结束日不能早于开始日（反之亦然）：从源头上不给"空区间"的机会
                    maxDate = DayKeyInput.parse(to),
                )
                RangeDayField(
                    label = stringResource(R.string.search_time_to),
                    value = to,
                    onValueChange = { to = it },
                    modifier = Modifier.weight(1f),
                    minDate = DayKeyInput.parse(from),
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
                    // 两端都合法才可点：写反了会自动交换，所以不算错
                    enabled = normalized != null,
                    onClick = { normalized?.let { (a, b) -> onApply(a, b) } },
                    modifier = Modifier.padding(start = 8.dp),
                ) { Text(stringResource(R.string.search_time_apply)) }
            }
        }
    }
}

/**
 * 起止日期的**选择**入口（不是输入框）。
 *
 * 用户 2026-09-26 两次要求："日期选择不要是输入日期，而是可以有菜单之类的进行选择"。
 * 手打日期既容易写错（`2026-9-1` 与 `2026-09-01` 字典序不同 → 静默空区间），
 * 也完全没有必要 —— Material 3 自带 [DatePicker]。
 *
 * 现在是"只读展示 + 点开日历"：值仍以 `YYYY-MM-DD` 存在状态里（与查询口径一致），
 * 但用户永远不需要亲手敲它。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RangeDayField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 早于这一天 / 晚于这一天不可选（起止互相约束，避免选出一个空区间）。 */
    minDate: LocalDate? = null,
    maxDate: LocalDate? = null,
) {
    var picking by remember { mutableStateOf(false) }
    val display = DayKeyInput.normalize(value)

    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedCard(
            onClick = { picking = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(Radius.field),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Icon(
                    imageVector = AppIcons.CalendarMonth,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    // 已经归一化过，展示的就是查询真正用的那个字符串
                    text = display ?: stringResource(R.string.search_time_pick),
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (display != null) {
                        MaterialTheme.colorScheme.onSurface
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                )
            }
        }
    }

    if (picking) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = (DayKeyInput.parse(value) ?: LocalDate.now())
                .toEpochMillisUtc(),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean {
                    val date = utcTimeMillis.toLocalDateUtc()
                    if (minDate != null && date < minDate) return false
                    if (maxDate != null && date > maxDate) return false
                    return true
                }
            },
        )
        DatePickerDialog(
            onDismissRequest = { picking = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        state.selectedDateMillis?.let { onValueChange(it.toLocalDateUtc().toString()) }
                        picking = false
                    },
                ) { Text(stringResource(R.string.ok)) }
            },
            dismissButton = {
                TextButton(onClick = { picking = false }) { Text(stringResource(R.string.cancel)) }
            },
        ) { DatePicker(state = state) }
    }
}

/** Material 的 `DatePicker` 用 **UTC 零点**的毫秒表示"哪一天"，这里做双向换算。 */
private fun LocalDate.toEpochMillisUtc(): Long =
    atStartOfDay(java.time.ZoneOffset.UTC).toInstant().toEpochMilli()

private fun Long.toLocalDateUtc(): LocalDate =
    java.time.Instant.ofEpochMilli(this).atZone(java.time.ZoneOffset.UTC).toLocalDate()
