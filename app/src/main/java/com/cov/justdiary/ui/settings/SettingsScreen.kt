package com.cov.justdiary.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.DateFormat as AndroidDateFormat
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.cov.justdiary.JustDiaryApplication
import com.cov.justdiary.R
import com.cov.justdiary.data.ImportMode
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.components.DiaryCard
import com.cov.justdiary.ui.components.IconBadge
import com.cov.justdiary.ui.components.PageHeader
import com.cov.justdiary.ui.components.SectionTitle
import com.cov.justdiary.ui.theme.Radius
import com.cov.justdiary.ui.theme.Spacing

/**
 * 设置页。对应 iOS `Views/Settings/SettingsView.swift`。
 *
 * 结构逐卡对齐 iOS：**通用 / 日记规则 / 提醒 / 数据 / 关于** 五张卡片，
 * 行与行的条件显示规则也照搬（`autoLoc` 关掉就不显示"位置权限"行；提醒关掉就不显示时间行；
 * 通知权限只在"开着提醒且未授权"时出现）。
 *
 * ## 与 iOS 的差异（都是平台事实，不是取舍）
 *
 * | iOS | Android |
 * | --- | --- |
 * | `Menu` 弹菜单选值 | [DropdownMenu]（同样带勾选标记） |
 * | 滚轮 `Picker` 选小时 | 滚动列表对话框（文案仍走 `DateFormats.dayStartLabel`） |
 * | 滚轮 `DatePicker` 选提醒时刻 | Material 3 [TimePicker]（跟随设备 12/24 小时制） |
 * | `UIDocumentPicker` + `ShareLink` | SAF：`CreateDocument` 导出 / `OpenDocument` 导入 |
 * | 权限随时可申请 | 权限申请必须回到 Activity，故经 [SettingsViewModel.events] 转一手 |
 * | 主题切换重建整树 | 主题/动态取色由 `MainActivity` 观察同一个 ViewModel 直接生效 |
 *
 * 额外多一行 **动态取色**（Android 专有，Q4 已确认），不进入 `settingsJson`。
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember(context) {
        (context.applicationContext as JustDiaryApplication).container
    }
    // 与 MainActivity 取到的是同一个实例（同一个 ViewModelStore + 默认 key）
    val viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory(container))
    val state by viewModel.state.collectAsState()

    val locationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { result ->
        viewModel.onLocationPermissionResult(result.values.any { it })
    }
    val notificationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        viewModel.onNotificationPermissionResult(granted)
    }
    var pendingExportWithSettings by remember { mutableStateOf(false) }
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument(MIME_BACKUP),
    ) { uri ->
        if (uri != null) viewModel.exportTo(context, uri, pendingExportWithSettings)
    }
    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri != null) viewModel.stageImport(context, uri)
    }

    LaunchedEffect(viewModel) {
        viewModel.refreshStatus()
        viewModel.events.collect { event ->
            when (event) {
                SettingsUiEvent.RequestLocationPermission ->
                    locationLauncher.launch(container.locationService.requiredPermissions())

                SettingsUiEvent.RequestNotificationPermission ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(android.Manifest.permission.POST_NOTIFICATIONS)
                    }

                SettingsUiEvent.OpenAppSettings ->
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.fromParts("package", context.packageName, null),
                        ),
                    )
            }
        }
    }

    val formats = remember(state.settings.appLanguage) {
        DateFormats.forContext(context, state.settings.appLanguage)
    }

    Box(modifier = modifier.fillMaxSize()) {
        SettingsBody(
            state = state,
            formats = formats,
            viewModel = viewModel,
            onExport = { includeSettings ->
                pendingExportWithSettings = includeSettings
                exportLauncher.launch(defaultBackupFileName())
            },
            onImport = { importLauncher.launch(arrayOf("*/*")) },
        )
        state.busy?.let { BusyOverlay(it) }
    }

    state.alert?.let { alert ->
        SettingsAlertDialog(alert = alert, onDismiss = viewModel::consumeAlert)
    }

    if (state.importModePromptVisible) {
        ImportModeDialog(
            onSkip = { viewModel.runImport(ImportMode.SKIP) },
            onOverwrite = { viewModel.runImport(ImportMode.OVERWRITE) },
            onDismiss = viewModel::cancelImportPrompt,
        )
    }
}

// ---------------------------------------------------------------------- 页面主体

@Composable
private fun SettingsBody(
    state: SettingsUiState,
    formats: DateFormats,
    viewModel: SettingsViewModel,
    onExport: (includeSettings: Boolean) -> Unit,
    onImport: () -> Unit,
) {
    var dayStartPickerVisible by remember { mutableStateOf(false) }
    var remindPickerVisible by remember { mutableStateOf(false) }
    val settings = state.settings
    // 列数只随宽度变：1 列（<680dp）→ 2 列（≥680dp）→ 3 列（≥1000dp），与 iOS 一致
    val columns = LocalAdaptiveLayout.current.settingsColumns

    LazyVerticalGrid(
        columns = GridCells.Fixed(columns),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            start = Spacing.page,
            end = Spacing.page,
            top = 12.dp,
            // 底部留出导航条的高度（对应 iOS 的 TabBarClearance）
            bottom = Spacing.page + 72.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(Spacing.card),
        horizontalArrangement = Arrangement.spacedBy(Spacing.card),
    ) {
        item(span = { GridItemSpan(maxLineSpan) }) {
            PageHeader(title = stringResource(R.string.settings_title))
        }

        // ---------------------------------------------------------- 通用
        item {
            SettingsCard {
                SectionTitle(
                    text = stringResource(R.string.settings_section_general),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
                MenuRow(
                    icon = SettingsIcons.language,
                    title = stringResource(R.string.settings_language),
                    sub = stringResource(R.string.settings_language_sub),
                    value = languageLabel(settings.appLanguage),
                    options = listOf(
                        MenuOption(stringResource(R.string.settings_lang_system), settings.appLanguage == AppSettings.LANGUAGE_SYSTEM) { viewModel.setLanguage(AppSettings.LANGUAGE_SYSTEM) },
                        MenuOption(stringResource(R.string.settings_lang_zh), settings.appLanguage == AppSettings.LANGUAGE_ZH) { viewModel.setLanguage(AppSettings.LANGUAGE_ZH) },
                        MenuOption(stringResource(R.string.settings_lang_en), settings.appLanguage == AppSettings.LANGUAGE_EN) { viewModel.setLanguage(AppSettings.LANGUAGE_EN) },
                    ),
                )
                RowDivider()
                MenuRow(
                    icon = SettingsIcons.theme,
                    title = stringResource(R.string.settings_theme),
                    sub = stringResource(R.string.settings_theme_sub),
                    value = themeLabel(settings.themeMode),
                    options = listOf(
                        MenuOption(stringResource(R.string.settings_theme_system), settings.themeMode == AppSettings.THEME_SYSTEM) { viewModel.setTheme(AppSettings.THEME_SYSTEM) },
                        MenuOption(stringResource(R.string.settings_theme_light), settings.themeMode == AppSettings.THEME_LIGHT) { viewModel.setTheme(AppSettings.THEME_LIGHT) },
                        MenuOption(stringResource(R.string.settings_theme_dark), settings.themeMode == AppSettings.THEME_DARK) { viewModel.setTheme(AppSettings.THEME_DARK) },
                    ),
                )
                RowDivider()
                SwitchRow(
                    icon = SettingsIcons.dynamicColor,
                    title = stringResource(R.string.settings_dynamic_color),
                    sub = stringResource(R.string.settings_dynamic_color_sub),
                    checked = settings.dynamicColor,
                    onCheckedChange = viewModel::setDynamicColor,
                )
            }
        }

        // ---------------------------------------------------------- 日记规则
        item {
            SettingsCard {
                SectionTitle(
                    text = stringResource(R.string.settings_section_rules),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
                ValueRow(
                    icon = SettingsIcons.dayStart,
                    title = stringResource(R.string.settings_day_start),
                    sub = stringResource(R.string.settings_day_start_sub),
                    value = formats.dayStartLabel(settings.dayStartHour),
                    onClick = { dayStartPickerVisible = true },
                )
                RowDivider()
                MenuRow(
                    icon = SettingsIcons.weekStart,
                    title = stringResource(R.string.settings_week_start),
                    sub = stringResource(R.string.settings_week_start_sub),
                    value = if (settings.weekStart == AppSettings.WEEK_START_SUNDAY) {
                        stringResource(R.string.settings_week_sunday)
                    } else {
                        stringResource(R.string.settings_week_monday)
                    },
                    options = listOf(
                        MenuOption(stringResource(R.string.settings_week_monday), settings.weekStart != AppSettings.WEEK_START_SUNDAY) { viewModel.setWeekStart(AppSettings.WEEK_START_MONDAY) },
                        MenuOption(stringResource(R.string.settings_week_sunday), settings.weekStart == AppSettings.WEEK_START_SUNDAY) { viewModel.setWeekStart(AppSettings.WEEK_START_SUNDAY) },
                    ),
                )
                RowDivider()
                SwitchRow(
                    icon = SettingsIcons.autoTime,
                    title = stringResource(R.string.settings_auto_time),
                    sub = stringResource(R.string.settings_auto_time_sub),
                    checked = settings.autoTime,
                    onCheckedChange = viewModel::setAutoTime,
                )
                RowDivider()
                SwitchRow(
                    icon = SettingsIcons.autoLoc,
                    title = stringResource(R.string.settings_auto_loc),
                    sub = stringResource(R.string.settings_auto_loc_sub),
                    checked = settings.autoLoc,
                    onCheckedChange = viewModel::setAutoLoc,
                )
                if (settings.autoLoc) {
                    RowDivider()
                    ValueRow(
                        icon = SettingsIcons.locationPermission,
                        title = stringResource(R.string.settings_loc_permission),
                        sub = stringResource(R.string.settings_loc_permission_sub),
                        value = locationPermissionLabel(state.locationPermission),
                        onClick = viewModel::onLocationPermissionRowClick,
                    )
                }
                RowDivider()
                SwitchRow(
                    icon = SettingsIcons.historyEdit,
                    title = stringResource(R.string.settings_history_edit),
                    sub = stringResource(R.string.settings_history_edit_sub),
                    checked = settings.allowHistoryEdit,
                    onCheckedChange = viewModel::setAllowHistoryEdit,
                )
            }
        }

        // ---------------------------------------------------------- 提醒
        item {
            SettingsCard {
                SectionTitle(
                    text = stringResource(R.string.settings_section_remind),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
                SwitchRow(
                    icon = SettingsIcons.reminder,
                    title = stringResource(R.string.settings_remind_enabled),
                    sub = stringResource(R.string.settings_remind_enabled_sub),
                    checked = settings.remindEnabled,
                    onCheckedChange = viewModel::setRemindEnabled,
                )
                if (settings.remindEnabled) {
                    RowDivider()
                    ValueRow(
                        icon = SettingsIcons.remindTime,
                        title = stringResource(R.string.settings_remind_time),
                        sub = stringResource(R.string.settings_remind_time_sub),
                        value = formats.timeOf(settings.remindHour, settings.remindMinute),
                        onClick = { remindPickerVisible = true },
                    )
                }
                if (settings.remindEnabled && !state.notificationsGranted) {
                    RowDivider()
                    ValueRow(
                        icon = SettingsIcons.notificationPermission,
                        title = stringResource(R.string.settings_notif_permission),
                        sub = stringResource(R.string.settings_notif_permission_sub),
                        value = stringResource(R.string.settings_notif_off),
                        onClick = viewModel::onNotificationPermissionRowClick,
                    )
                }
            }
        }

        // ---------------------------------------------------------- 数据
        item {
            SettingsCard {
                SectionTitle(
                    text = stringResource(R.string.settings_section_data),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
                MenuRow(
                    icon = SettingsIcons.export,
                    title = stringResource(R.string.settings_export),
                    sub = stringResource(R.string.settings_export_sub),
                    value = "",
                    options = listOf(
                        MenuOption(stringResource(R.string.settings_export_data_only), selected = false) { onExport(false) },
                        MenuOption(stringResource(R.string.settings_export_data_settings), selected = false) { onExport(true) },
                    ),
                )
                RowDivider()
                ValueRow(
                    icon = SettingsIcons.import,
                    title = stringResource(R.string.settings_import),
                    sub = stringResource(R.string.settings_import_sub),
                    value = "",
                    onClick = onImport,
                )
            }
        }

        // ---------------------------------------------------------- 关于
        item {
            SettingsCard {
                SectionTitle(
                    text = stringResource(R.string.settings_section_about),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                )
                ValueRow(
                    icon = SettingsIcons.about,
                    title = stringResource(R.string.settings_version),
                    sub = null,
                    value = state.appVersion,
                    onClick = {},
                )
            }
        }
    }

    if (dayStartPickerVisible) {
        HourPickerDialog(
            formats = formats,
            initialHour = settings.dayStartHour,
            onDismiss = { dayStartPickerVisible = false },
            onConfirm = { hour ->
                dayStartPickerVisible = false
                viewModel.applyDayStartHour(hour)
            },
        )
    }
    if (remindPickerVisible) {
        ReminderTimeDialog(
            initialHour = settings.remindHour,
            initialMinute = settings.remindMinute,
            onDismiss = { remindPickerVisible = false },
            onConfirm = { hour, minute ->
                remindPickerVisible = false
                viewModel.applyRemindTime(hour, minute)
            },
        )
    }
}

// ---------------------------------------------------------------------- 行组件

/** 卡片容器：内部行自己控制间距，因此内容 padding 为 0。 */
@Composable
private fun SettingsCard(content: @Composable () -> Unit) {
    DiaryCard(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) { content() }
    }
}

/**
 * 行之间的分隔线，左侧让开图标徽章（对应 iOS `RowDivider(horizontalPadding: textInset)`）。
 */
@Composable
private fun RowDivider() {
    HorizontalDivider(
        modifier = Modifier.padding(start = 62.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
    )
}

/** 右侧的取值 + 尖角。 */
@Composable
private fun ValueTrailing(value: String) {
    Text(
        text = value,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        textAlign = TextAlign.End,
        modifier = Modifier.valueWidth(),
    )
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Modifier.valueWidth(): Modifier = this.width(96.dp)

/** 点一下打开菜单的行。 */
@Composable
private fun MenuRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    value: String,
    options: List<MenuOption>,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = true }
                .heightIn(min = 56.dp)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            IconBadge(icon = icon)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                if (sub != null) {
                    Text(
                        text = sub,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            ValueTrailing(value)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.label) },
                    onClick = {
                        expanded = false
                        option.onSelect()
                    },
                    trailingIcon = if (option.selected) {
                        { Icon(Icons.Filled.Check, contentDescription = null) }
                    } else {
                        null
                    },
                )
            }
        }
    }
}

/** 点一下执行动作的行。 */
@Composable
private fun ValueRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    value: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        ValueTrailing(value)
    }
}

/** 开关行。 */
@Composable
private fun SwitchRow(
    icon: ImageVector,
    title: String,
    sub: String?,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        IconBadge(icon = icon)
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            if (sub != null) {
                Text(
                    text = sub,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

// ---------------------------------------------------------------------- 对话框

private data class MenuOption(
    val label: String,
    val selected: Boolean,
    val onSelect: () -> Unit,
)

/** "新一天的开始时间"：24 个整点的滚动列表，文案走 `DateFormats.dayStartLabel`。 */
@Composable
private fun HourPickerDialog(
    formats: DateFormats,
    initialHour: Int,
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit,
) {
    var selected by remember { mutableStateOf(initialHour) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_day_start)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 360.dp)) {
                items(count = 24) { hour ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .selectable(selected == hour, onClick = { selected = hour })
                            .padding(horizontal = 8.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = formats.dayStartLabel(hour),
                            style = MaterialTheme.typography.bodyMedium,
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
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(selected) }) { Text(stringResource(R.string.save)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 提醒时刻：Material 3 [TimePicker]（对应 iOS 的滚轮 `DatePicker`）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReminderTimeDialog(
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
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_remind_time)) },
        text = { TimePicker(state = pickerState) },
        confirmButton = {
            TextButton(onClick = { onConfirm(pickerState.hour, pickerState.minute) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
        },
    )
}

/** 导入方式确认（对应 iOS 的 `confirmationDialog`：这是确认破坏性动作，不是选值）。 */
@Composable
private fun ImportModeDialog(onSkip: () -> Unit, onOverwrite: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_import_mode_title)) },
        text = { Text(stringResource(R.string.settings_import_sub)) },
        confirmButton = {
            TextButton(onClick = onSkip) { Text(stringResource(R.string.settings_import_skip)) }
        },
        dismissButton = {
            Row {
                TextButton(onClick = onOverwrite) {
                    Text(stringResource(R.string.settings_import_overwrite))
                }
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        },
    )
}

/** 导出/导入期间的进度遮罩（对应 iOS 的 `busyText` 遮罩）。 */
@Composable
private fun BusyOverlay(kind: BusyKind) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) {},
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            shape = RoundedCornerShape(Radius.card),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            tonalElevation = 3.dp,
            shadowElevation = 6.dp,
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 32.dp, vertical = 26.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                CircularProgressIndicator()
                Text(
                    text = stringResource(
                        when (kind) {
                            BusyKind.EXPORT -> R.string.settings_export_busy
                            BusyKind.IMPORT -> R.string.settings_import_busy
                        },
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
    }
}

/** 结果提示（对应 iOS 的 `appAlert(item:)`）。 */
@Composable
private fun SettingsAlertDialog(alert: SettingsAlert, onDismiss: () -> Unit) {
    val message = when (alert) {
        is SettingsAlert.DayKeysRecomputed ->
            stringResource(R.string.settings_day_recalc_msg, alert.conflicts)

        SettingsAlert.ExportFailed -> stringResource(R.string.settings_export_failed_msg)
        SettingsAlert.ImportFailed -> stringResource(R.string.settings_import_failed_msg)
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
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.save)) }
        },
    )
}

// ---------------------------------------------------------------------- 文案与图标

@Composable
private fun themeLabel(themeMode: String): String = when (themeMode) {
    AppSettings.THEME_LIGHT -> stringResource(R.string.settings_theme_light)
    AppSettings.THEME_DARK -> stringResource(R.string.settings_theme_dark)
    else -> stringResource(R.string.settings_theme_system)
}

@Composable
private fun languageLabel(appLanguage: String): String = when (appLanguage) {
    // 语言名按 iOS 的做法显示**母语原文**，不随界面语言翻译
    AppSettings.LANGUAGE_ZH -> "中文"
    AppSettings.LANGUAGE_EN -> "English"
    else -> stringResource(R.string.settings_lang_system)
}

@Composable
private fun locationPermissionLabel(permission: LocationPermissionState): String = when (permission) {
    LocationPermissionState.NONE -> stringResource(R.string.settings_loc_none)
    LocationPermissionState.FUZZY -> stringResource(R.string.settings_loc_fuzzy)
    LocationPermissionState.EXACT -> stringResource(R.string.settings_loc_exact)
}

/**
 * 行图标的**临时映射**（M9 换成真正的 Material Symbols 图标，见方案 §M9）。
 *
 * 现在只用 `material-icons-core` 里的图标（约 40 个），因此取的是**语义最近**的一个：
 * 同一张卡片内不重复，避免视觉上难以区分。扩展包 `material-icons-extended` 有 35 MB，
 * 为几个图标把整包打进 APK 不划算（`isMinifyEnabled = false`）。
 */
private object SettingsIcons {
    val language: ImageVector = Icons.Filled.Place
    val theme: ImageVector = Icons.Filled.Star
    val dynamicColor: ImageVector = Icons.Filled.Favorite
    val dayStart: ImageVector = Icons.Filled.Refresh
    val weekStart: ImageVector = Icons.Filled.DateRange
    val autoTime: ImageVector = Icons.Filled.Check
    val autoLoc: ImageVector = Icons.Filled.LocationOn
    val locationPermission: ImageVector = Icons.Filled.Lock
    val historyEdit: ImageVector = Icons.Filled.Edit
    val reminder: ImageVector = Icons.Filled.Notifications
    val remindTime: ImageVector = Icons.Filled.Refresh
    val notificationPermission: ImageVector = Icons.Filled.Lock
    val export: ImageVector = Icons.Filled.Share
    val import: ImageVector = Icons.AutoMirrored.Filled.ExitToApp
    val about: ImageVector = Icons.Filled.Info
}

/** SAF 用的 MIME：`.jdiary` 没有注册类型，用 octet-stream 才能保住扩展名。 */
private const val MIME_BACKUP = "application/octet-stream"

/** 默认文件名，与 iOS 的 `JustDiary_yyyyMMdd_HHmmss.jdiary` 同名规则。 */
private fun defaultBackupFileName(): String {
    val stamp = java.text.SimpleDateFormat("yyyyMMdd_HHmmss", java.util.Locale.US)
        .format(java.util.Date())
    return "JustDiary_$stamp.jdiary"
}
