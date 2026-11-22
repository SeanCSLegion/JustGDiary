package com.cov.justdiary.ui.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.cov.justdiary.JustDiaryApplication
import com.cov.justdiary.R
import com.cov.justdiary.data.ImportMode
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.LocPrecision
import com.cov.justdiary.service.DateFormats
import com.cov.justdiary.service.GitHubReleaseUpdater
import com.cov.justdiary.service.ReleaseDownloadWorker
import com.cov.justdiary.ui.adaptive.LocalAdaptiveLayout
import com.cov.justdiary.ui.components.PageHeader
import com.cov.justdiary.ui.components.SectionTitle
import com.cov.justdiary.ui.components.locPrecisionLabel
import com.cov.justdiary.ui.theme.Spacing
import kotlinx.coroutines.launch
import java.io.File

private sealed interface UpdaterUiState {
    data object Idle : UpdaterUiState
    data object Checking : UpdaterUiState
    data object Current : UpdaterUiState
    data class Downloading(val percent: Int = 0) : UpdaterUiState
    data class Available(val release: GitHubReleaseUpdater.Release) : UpdaterUiState
    data class Ready(val file: File, val release: GitHubReleaseUpdater.Release) : UpdaterUiState
    data class Failed(val message: String) : UpdaterUiState
}

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
    val scope = rememberCoroutineScope()
    val updater = remember(context) { GitHubReleaseUpdater(context.applicationContext) }
    var updaterState by remember { mutableStateOf<UpdaterUiState>(UpdaterUiState.Idle) }
    var terminalWorkId by remember { mutableStateOf<String?>(null) }
    fun dismissDownloadResult() {
        terminalWorkId?.let { context.getSharedPreferences("release_download", android.content.Context.MODE_PRIVATE)
            .edit().putString("dismissed_work_id", it).apply() }
        terminalWorkId = null
        updaterState = UpdaterUiState.Idle
    }
    var pendingInstallerFile by remember { mutableStateOf<File?>(null) }
    fun launchInstaller(file: File) {
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(updater.installerUri(file), "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
    }
    val unknownSourcesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) {
        val file = pendingInstallerFile
        pendingInstallerFile = null
        if (file != null && context.packageManager.canRequestPackageInstalls()) {
            runCatching { launchInstaller(file) }
                .onFailure { updaterState = UpdaterUiState.Failed(it.message ?: "Could not open Android installer") }
        } else if (file != null) {
            updaterState = UpdaterUiState.Failed(context.getString(R.string.update_install_permission_required))
        }
    }

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

    LaunchedEffect(context, updater) {
        WorkManager.getInstance(context).getWorkInfosForUniqueWorkFlow(ReleaseDownloadWorker.UNIQUE_WORK).collect { infos ->
            val work = infos.firstOrNull() ?: return@collect
            val dismissedWorkId = context.getSharedPreferences("release_download", android.content.Context.MODE_PRIVATE)
                .getString("dismissed_work_id", null)
            if (work.id.toString() == dismissedWorkId && work.state.isFinished) return@collect
            when (work.state) {
                WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED -> {
                    val size = work.progress.getLong(ReleaseDownloadWorker.KEY_SIZE, 0L)
                    val downloaded = work.progress.getLong(ReleaseDownloadWorker.KEY_DOWNLOADED, 0L)
                    val percent = if (size > 0) (downloaded * 100 / size).toInt().coerceIn(0, 100) else 0
                    updaterState = UpdaterUiState.Downloading(percent)
                }
                WorkInfo.State.SUCCEEDED -> {
                    terminalWorkId = work.id.toString()
                    val path = work.outputData.getString(ReleaseDownloadWorker.KEY_FILE) ?: return@collect
                    val release = GitHubReleaseUpdater.Release(
                        tag = work.outputData.getString(ReleaseDownloadWorker.KEY_TAG).orEmpty(),
                        versionName = work.outputData.getString(ReleaseDownloadWorker.KEY_VERSION).orEmpty(),
                        notes = work.outputData.getString(ReleaseDownloadWorker.KEY_NOTES).orEmpty(),
                        downloadUrl = work.outputData.getString(ReleaseDownloadWorker.KEY_URL).orEmpty(),
                        expectedSize = work.outputData.getLong(ReleaseDownloadWorker.KEY_SIZE, 0L),
                        sha256 = work.outputData.getString(ReleaseDownloadWorker.KEY_SHA256),
                    )
                    updaterState = UpdaterUiState.Ready(File(path), release)
                }
                WorkInfo.State.FAILED -> {
                    terminalWorkId = work.id.toString()
                    updaterState = UpdaterUiState.Failed(
                        work.outputData.getString(ReleaseDownloadWorker.KEY_ERROR) ?: "APK verification failed",
                    )
                }
                WorkInfo.State.CANCELLED -> if (updaterState is UpdaterUiState.Downloading) updaterState = UpdaterUiState.Idle
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
            updateEnabled = updaterState !is UpdaterUiState.Checking && updaterState !is UpdaterUiState.Downloading &&
                updaterState !is UpdaterUiState.Available && updaterState !is UpdaterUiState.Ready,
            updateStatus = when (updaterState) {
                UpdaterUiState.Checking -> stringResource(R.string.update_checking)
                is UpdaterUiState.Downloading -> stringResource(R.string.update_progress, (updaterState as UpdaterUiState.Downloading).percent)
                UpdaterUiState.Current -> stringResource(R.string.update_current)
                is UpdaterUiState.Available, is UpdaterUiState.Ready -> stringResource(R.string.update_available)
                is UpdaterUiState.Failed -> stringResource(R.string.update_failed)
                UpdaterUiState.Idle -> ""
            },
            onCheckUpdate = {
                updaterState = UpdaterUiState.Checking
                scope.launch {
                    updaterState = runCatching { updater.check() }
                        .fold(
                            onSuccess = { result ->
                                when (result) {
                                    GitHubReleaseUpdater.CheckResult.UpToDate -> UpdaterUiState.Current
                                    is GitHubReleaseUpdater.CheckResult.Available -> UpdaterUiState.Available(result.release)
                                }
                            },
                            onFailure = { UpdaterUiState.Failed(it.message ?: "Update check failed") },
                        )
                }
            },
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

    when (val update = updaterState) {
        is UpdaterUiState.Available -> AlertDialog(
            onDismissRequest = { updaterState = UpdaterUiState.Idle },
            title = { Text(stringResource(R.string.update_available_title, update.release.versionName)) },
            text = {
                Text(
                    if (update.release.notes.isBlank()) stringResource(R.string.update_release_notes_empty)
                    else update.release.notes,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    updaterState = UpdaterUiState.Downloading()
                    val request = OneTimeWorkRequestBuilder<ReleaseDownloadWorker>()
                        .setInputData(ReleaseDownloadWorker.input(update.release))
                        .build()
                    WorkManager.getInstance(context).enqueueUniqueWork(
                        ReleaseDownloadWorker.UNIQUE_WORK, ExistingWorkPolicy.KEEP, request,
                    )
                }) { Text(stringResource(R.string.update_download_install)) }
            },
            dismissButton = { TextButton(onClick = { updaterState = UpdaterUiState.Idle }) { Text(stringResource(R.string.cancel)) } },
        )
        is UpdaterUiState.Ready -> AlertDialog(
            onDismissRequest = ::dismissDownloadResult,
            title = { Text(stringResource(R.string.update_ready_title)) },
            text = { Text(stringResource(R.string.update_ready_message, update.release.versionName)) },
            confirmButton = { TextButton(onClick = {
                dismissDownloadResult()
                if (context.packageManager.canRequestPackageInstalls()) {
                    runCatching { launchInstaller(update.file) }
                        .onFailure { updaterState = UpdaterUiState.Failed(it.message ?: "Could not open Android installer") }
                } else {
                    pendingInstallerFile = update.file
                    unknownSourcesLauncher.launch(
                        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.fromParts("package", context.packageName, null)),
                    )
                }
            }) { Text(stringResource(R.string.update_open_installer)) } },
            dismissButton = { TextButton(onClick = ::dismissDownloadResult) { Text(stringResource(R.string.cancel)) } },
        )
        is UpdaterUiState.Failed -> AlertDialog(
            onDismissRequest = ::dismissDownloadResult,
            title = { Text(stringResource(R.string.update_failed_title)) },
            text = { Text(update.message) },
            confirmButton = { TextButton(onClick = ::dismissDownloadResult) { Text(stringResource(R.string.ok)) } },
        )
        else -> Unit
    }

    // 「新一天的开始时间」的二次确认（规范 §3.4）：非空 = 有待确认的改动。
    // Android **先问再改** —— iOS 是先改完、有冲突才提示，用户没有反悔的机会。
    state.pendingDayStartHour?.let { hour ->
        AlertDialog(
            onDismissRequest = viewModel::cancelDayStartHour,
            title = { Text(stringResource(R.string.settings_day_start_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_day_start_confirm_msg,
                        state.pendingDayStartMovedBlocks,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmDayStartHour) {
                    Text(stringResource(R.string.settings_day_start_confirm_ok))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelDayStartHour) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }

    // P0-3：导入的换日时刻与当前不同 → 先问是否重算现有日记。
    state.pendingImportDayStartHour?.let { hour ->
        AlertDialog(
            onDismissRequest = viewModel::cancelImportDayStart,
            title = { Text(stringResource(R.string.settings_import_day_start_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.settings_import_day_start_msg,
                        hour,
                        state.settings.dayStartHour,
                        state.pendingImportDayStartMovedBlocks,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = viewModel::confirmImportDayStart) {
                    Text(stringResource(R.string.settings_import_day_start_apply))
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::cancelImportDayStart) {
                    Text(stringResource(R.string.settings_import_day_start_keep))
                }
            },
        )
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
    updateEnabled: Boolean,
    updateStatus: String,
    onCheckUpdate: () -> Unit,
    onExport: (includeSettings: Boolean) -> Unit,
    onImport: () -> Unit,
) {
    var dayStartPickerVisible by remember { mutableStateOf(false) }
    var remindPickerVisible by remember { mutableStateOf(false) }
    val settings = state.settings
    // 手机设置维持单列顺序，横屏仍可滚动浏览。
    val columns = LocalAdaptiveLayout.current.settingsColumns

    // ⚠️ 页头放在网格**外面**：原来它是网格里一个跨列 item，网格的 16dp contentPadding
    // 与 PageHeader 自己的 16dp 内边距叠成了 32dp，再加上网格的 12dp 顶边距 ——
    // 实测「设置」标题在 32dp/72dp，而「搜索」「足迹」在 16dp/60dp（用户反馈：顶部不一致）。
    Column(modifier = Modifier.fillMaxSize()) {
        PageHeader(title = stringResource(R.string.settings_title))
        LazyVerticalGrid(
            columns = GridCells.Fixed(columns),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = Spacing.page,
                end = Spacing.page,
                top = 0.dp,
                // 底栏在 RootScreen 的 Scaffold 里、**参与布局**（不是 iOS 那种浮条），
                // 内容已经被 innerPadding 抬到它之上，这里只需要一点呼吸空间（规范 §4）。
                bottom = 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(Spacing.card),
            horizontalArrangement = Arrangement.spacedBy(Spacing.card),
        ) {
        // ---------------------------------------------------------- 通用
        item {
            SettingsCard {
                SectionTitle(
                    text = stringResource(R.string.settings_section_general),
                    modifier = Modifier.padding(horizontal = 16.dp),
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
                    modifier = Modifier.padding(horizontal = 16.dp),
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
                // 分享长图里的地点上限。选项**只有**隐藏 / 区县 / 城市 / 省份：
                // 精确地点与街道不在 `LocPrecision.shareable` 里，用户选不到（iOS 同此）。
                // 选项由 `shareable` 派生，加一级就自动出现，不必手写四遍。
                MenuRow(
                    icon = SettingsIcons.shareLoc,
                    title = stringResource(R.string.settings_share_loc),
                    sub = stringResource(R.string.settings_share_loc_sub) + " " + stringResource(R.string.settings_share_loc_limit_sub),
                    value = locPrecisionLabel(settings.shareLocPrecision),
                    options = buildList {
                        add(
                            MenuOption(
                                label = stringResource(R.string.settings_share_loc_hidden),
                                selected = settings.shareLocPrecision == LocPrecision.none,
                            ) { viewModel.setShareLocPrecision(LocPrecision.none) },
                        )
                        LocPrecision.shareable.forEach { level ->
                            add(
                                MenuOption(
                                    label = locPrecisionLabel(level),
                                    selected = settings.shareLocPrecision == level,
                                ) { viewModel.setShareLocPrecision(level) },
                            )
                        }
                    },
                )
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
                    modifier = Modifier.padding(horizontal = 16.dp),
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
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                // 存储占用；孤儿图片由启动时后台清理。
                val storage = state.storage
                ValueRow(
                    icon = SettingsIcons.storage,
                    title = stringResource(R.string.settings_storage),
                    sub = stringResource(
                        R.string.settings_storage_sub,
                        formatBytes(storage.databaseBytes),
                        formatBytes(storage.imagesBytes),
                        formatBytes(storage.backupBytes),
                    ),
                    value = stringResource(R.string.settings_storage_total, formatBytes(storage.totalBytes)),
                    enabled = false,
                )
                RowDivider()
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
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
                ValueRow(
                    icon = SettingsIcons.about,
                    title = stringResource(R.string.settings_version),
                    sub = null,
                    value = state.appVersion,
                    enabled = updateEnabled,
                    onClick = onCheckUpdate,
                    actionLabel = stringResource(R.string.update_check),
                    status = updateStatus,
                )
            }
        }
    }
    }

    if (dayStartPickerVisible) {
        HourPickerSheet(
            formats = formats,
            initialHour = settings.dayStartHour,
            onDismiss = { dayStartPickerVisible = false },
            onConfirm = { hour ->
                dayStartPickerVisible = false
                // 真正写库之前先算一遍影响面（规范 §3.4），有影响会弹确认框
                viewModel.requestDayStartHour(hour)
            },
        )
    }
    if (remindPickerVisible) {
        ReminderTimeSheet(
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
