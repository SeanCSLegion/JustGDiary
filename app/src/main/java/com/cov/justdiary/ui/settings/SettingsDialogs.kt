package com.cov.justdiary.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import android.text.format.DateFormat as AndroidDateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.cov.justdiary.R
import com.cov.justdiary.service.DateFormats

/**
 * 设置页的对话框与底部弹层：起始时刻、提醒时刻、导入模式、忙碌遮罩与错误提示。
 *
 * 从 [SettingsScreen] 拆出；全部是受控组件，状态由主文件持有。
 */

/**
 * "新一天的开始时间"：24 个整点的滚动列表，文案走 `DateFormats.dayStartLabel`。
 *
 * ⚠️ 容器是 **`ModalBottomSheet`** 而不是 `AlertDialog`：本项目的硬约束是
 * "参数编辑一律用底部面板"（`README` 硬约束 3 / 06 §2.3）。选值不是确认破坏性动作，
 * 用对话框会把"选择"与"确认"混成同一层级。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HourPickerSheet(
    formats: DateFormats,
    initialHour: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selected by remember { mutableStateOf(initialHour) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp)) {
            Text(
                text = stringResource(R.string.settings_day_start),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(count = 24) { hour ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected == hour, onClick = { selected = hour })
                            .heightIn(min = 48.dp)
                            .padding(horizontal = 24.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formats.dayStartLabel(hour),
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (selected == hour) {
                            Icon(
                                imageVector = Icons.Filled.Check,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }
            // 底部动作区：取消在左、保存在右（与 06 §2.3 的动作顺序一致）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.save)) }
            }
        }
    }
}

/**
 * 提醒时刻：Material 3 [TimePicker]（对应 iOS 的滚轮 `DatePicker`）。
 *
 * 容器同样是 `ModalBottomSheet`（硬约束 3）—— Material 的 `TimePicker` 本身
 * 就是按"面板里的一个控件"设计的（宽 328dp、无自己的确认键）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderTimeSheet(
    initialHour: Int,
    initialMinute: Int,
    onDismiss: () -> Unit,
    onConfirm: (hour: Int, minute: Int) -> Unit,
) {
    val context = LocalContext.current
    val pickerState = rememberTimePickerState(
        initialHour = initialHour,
        initialMinute = initialMinute,
        is24Hour = AndroidDateFormat.is24HourFormat(context),
    )
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.settings_remind_time),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 24.dp, end = 24.dp, bottom = 8.dp),
            )
            TimePicker(state = pickerState)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) {
                    Text(stringResource(R.string.save))
                }
            }
        }
    }
}

/** 导入方式确认（对应 iOS 的 `confirmationDialog`：这是确认破坏性动作，不是选值）。 */
@Composable
internal fun ImportModeDialog(onSkip: () -> Unit, onOverwrite: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_import_mode_title)) },
        text = {
            // 面板正文要说清"两种选择各自的后果"。
            // 原来正文直接复用了行内副标题（「从其他设备恢复日记」）—— 那是**入口**的说明，
            // 放在这里等于什么都没说，而用户正要做一个会覆盖数据的决定。
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.settings_import_mode_msg))
                Text(
                    text = stringResource(R.string.settings_import_skip_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    text = stringResource(R.string.settings_import_overwrite_hint),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        },
        // 动作顺序：取消 → 跳过（保留本机）→ **覆盖（破坏性，最右、error 色）**（规范 06 §2.2）。
        // 原来「跳过」占着确认位、被排到最右，破坏性的「覆盖」挤在中间。
        confirmButton = {
            TextButton(
                onClick = onOverwrite,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error,
                ),
            ) {
                Text(stringResource(R.string.settings_import_overwrite))
            }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
                TextButton(onClick = onSkip) { Text(stringResource(R.string.settings_import_skip)) }
            }
        },
    )
}

/**
 * 导出 / 导入期间的进度遮罩。
 *
 * 用共享的 [com.cov.justdiary.ui.components.BusyOverlay]：带 scrim、**不可取消**
 * （导入中途取消会留下半写状态），文案按动作区分。
 */
@Composable
internal fun BusyOverlay(kind: BusyKind) {
    com.cov.justdiary.ui.components.BusyOverlay(
        text = stringResource(
            when (kind) {
                BusyKind.EXPORT -> R.string.settings_export_busy
                BusyKind.IMPORT -> R.string.settings_import_busy
            },
        ),
        visible = true,
    )
}

/** 结果提示（对应 iOS 的 `appAlert(item:)`）。 */
@Composable
internal fun SettingsAlertDialog(alert: SettingsAlert, onDismiss: () -> Unit) {
    val message = when (alert) {
        is SettingsAlert.DayKeysRecomputed ->
            stringResource(R.string.settings_day_recalc_msg, alert.conflicts)

        SettingsAlert.ExportFailed -> stringResource(R.string.settings_export_failed_msg)
        SettingsAlert.ImportFailed -> stringResource(R.string.settings_import_failed_msg)
        is SettingsAlert.StorageCleaned ->
            stringResource(R.string.settings_storage_cleaned, alert.deleted)

        SettingsAlert.StorageCleanFailed -> stringResource(R.string.settings_storage_clean_failed_msg)

        is SettingsAlert.ImportSucceeded -> {
            val stats = alert.stats
            buildString {
                append(
                    stringResource(
                        R.string.settings_import_success_msg,
                        stats.importedDays,
                        stats.skippedDays,
                        stats.overwrittenDays,
                        stats.importedBlocks,
                        stats.importedImages,
                    ),
                )
                if (stats.settingsRestored) {
                    append('\n')
                    append(stringResource(R.string.settings_settings_restored))
                }
            }
        }
    }
    val title = when (alert) {
        is SettingsAlert.DayKeysRecomputed -> stringResource(R.string.settings_day_recalc_title)
        SettingsAlert.ExportFailed -> stringResource(R.string.settings_export_failed_title)
        SettingsAlert.ImportFailed -> stringResource(R.string.settings_import_failed_title)
        is SettingsAlert.ImportSucceeded -> stringResource(R.string.settings_import_success_title)
        is SettingsAlert.StorageCleaned -> stringResource(R.string.settings_storage_cleaned_title)
        SettingsAlert.StorageCleanFailed -> stringResource(R.string.settings_storage_clean_failed_title)
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            // 单键信息框的确认键是「好」，不是「保存」/「取消」
            // （06 §2.2；iOS 侧这条曾经写成「取消」，是明确记录过的缺陷）
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.ok)) }
        },
    )
}
