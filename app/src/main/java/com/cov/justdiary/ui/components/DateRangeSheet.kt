package com.cov.justdiary.ui.components

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

    // 只有"用户动过的那一端"才提示错误：刚打开就满屏红字很吓人
    var touchedFrom by remember { mutableStateOf(false) }
    var touchedTo by remember { mutableStateOf(false) }
    val fromError = touchedFrom && DayKeyInput.normalize(from) == null
    val toError = touchedTo && DayKeyInput.normalize(to) == null
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
                    isError = fromError,
                    onValueChange = { from = it; touchedFrom = true },
                    modifier = Modifier.weight(1f),
                )
                RangeDayField(
                    label = stringResource(R.string.search_time_to),
                    value = to,
                    isError = toError,
                    onValueChange = { to = it; touchedTo = true },
                    modifier = Modifier.weight(1f),
                )
            }
            if (fromError || toError) {
                Text(
                    text = stringResource(R.string.search_time_invalid),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
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

/** 起止日期输入框（`YYYY-MM-DD`，带错误态）。 */
@Composable
private fun RangeDayField(
    label: String,
    value: String,
    isError: Boolean,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 6.dp),
        )
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier
                .fillMaxWidth()
                .width(160.dp),
            singleLine = true,
            isError = isError,
            shape = RoundedCornerShape(Radius.field),
            textStyle = MaterialTheme.typography.bodyLarge,
            placeholder = { Text(DayKeyInput.PLACEHOLDER) },
            // 数字键盘：省得为了打减号切键盘（`-` 仍由 `normalize` 接受）
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
        )
    }
}
