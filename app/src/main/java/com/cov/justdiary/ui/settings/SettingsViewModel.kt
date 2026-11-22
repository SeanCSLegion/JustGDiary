package com.cov.justdiary.ui.settings

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.cov.justdiary.app.AppContainer
import com.cov.justdiary.data.BackupStats
import com.cov.justdiary.data.ImportMode
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.service.Log
import com.cov.justdiary.service.StorageUsage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 设置页的界面状态。
 *
 * 文案**不在这里解析**：这里只给结构化结果（枚举 / 数字），由 Composable 用
 * `stringResource` 渲染。这样 ViewModel 与资源解耦，也能在 JVM 上断言状态迁移。
 */
data class SettingsUiState(
    val settings: AppSettings = AppSettings(),
    val locationPermission: LocationPermissionState = LocationPermissionState.NONE,
    val notificationsGranted: Boolean = false,
    /** 正在进行的耗时操作（导出 / 导入），非空时界面盖一层进度。 */
    val busy: BusyKind? = null,
    /** 一次性的结果提示；Composable 弹出后调用 [SettingsViewModel.consumeAlert]。 */
    val alert: SettingsAlert? = null,
    /** 导入方式确认对话框是否展开（对应 iOS 的 `confirmationDialog`）。 */
    val importModePromptVisible: Boolean = false,
    /** 已选好、等待用户选导入方式的备份文件。 */
    val pendingImport: File? = null,
    /**
     * 已选定、等待用户确认的「新一天的开始时间」小时。
     *
     * 规范 §3.4：Android **先问再改** —— 非空时界面弹确认框，用户点「继续」才写库。
     */
    val pendingDayStartHour: Int? = null,
    /** 上面那个待确认改动会影响的日记篇数。 */
    val pendingDayStartMovedBlocks: Int = 0,
    /**
     * 导入备份带来的 `day_start_hour` 与当前不同，等待用户确认是否重算。
     *
     * 非空时界面弹确认框；确认后按备份时刻重算，取消则保留当前设置。
     */
    val pendingImportDayStartHour: Int? = null,
    val pendingImportDayStartMovedBlocks: Int = 0,
    /** 等待用户确认换日时刻期间暂存的导入统计，确认/取消后用来显示成功框。 */
    val pendingImportDayStartStats: BackupStats? = null,
    /** 版本号（"关于"卡片）。 */
    val appVersion: String = "",
    /** P2-5：数据库 / 图片 / 备份占用。 */
    val storage: StorageUsage = StorageUsage(),
    /**
     * 启动序列失败（开库 / 迁移）。非空时界面上盖一层显式错误态，而不是展示"空日记本"。
     *
     * 文案由 Composable 用资源渲染；这里只收一个技术细节（异常 message）供诊断。
     */
    val startupError: String? = null,
)

/** 定位授权档位（文案：`settings_loc_none` / `_fuzzy` / `_exact`）。 */
enum class LocationPermissionState { NONE, FUZZY, EXACT }

/** 耗时操作类型（文案：`settings_export_busy` / `settings_import_busy`）。 */
enum class BusyKind { EXPORT, IMPORT }

/** 结果提示（标题/正文由 Composable 用资源拼）。 */
sealed interface SettingsAlert {
    /** `settings_day_recalc_title` + `settings_day_recalc_msg`，参数为保留原归属日的篇数。 */
    data class DayKeysRecomputed(val conflicts: Int) : SettingsAlert

    data object ExportFailed : SettingsAlert

    data object ImportFailed : SettingsAlert

    /** `settings_import_success_title` + `settings_import_success_msg`（+ 恢复设置那一行）。 */
    data class ImportSucceeded(val stats: BackupStats) : SettingsAlert

}

/** 需要 Activity 参与的请求（权限、跳系统设置）。 */
sealed interface SettingsUiEvent {
    data object RequestLocationPermission : SettingsUiEvent

    data object RequestNotificationPermission : SettingsUiEvent

    data object OpenAppSettings : SettingsUiEvent
}

/**
 * 设置页的 ViewModel。对应 iOS `ViewModels/SettingsViewModel.swift`。
 *
 * ## 与 iOS 的两处结构性差异（都是 Android 的平台事实）
 *
 * 1. **权限申请必须回到 Activity**：iOS 的 `CLLocationManager.requestWhenInUseAuthorization()`
 *    可以在任意地方调；Android 必须走 `ActivityResultLauncher`。因此这里只发
 *    [SettingsUiEvent]，由 Composable 持有 launcher 执行，结果再回到 [onLocationPermissionResult] /
 *    [onNotificationPermissionResult]。
 * 2. **文件选择/保存用 SAF**：iOS 用 `ShareLink` + `UIDocumentPicker`；Android 用
 *    `ACTION_CREATE_DOCUMENT` / `ACTION_OPEN_DOCUMENT`，拿到 `content://` Uri 后
 *    由 [exportTo] / [stageImport] 落盘处理。
 *
 * 所有写设置的动作都走 [com.cov.justdiary.app.AppSettingsApplier.commit]，
 * 因此"改了设置要连带做什么"始终由纯函数 `SettingsEffects.effectsOf` 决定，不散在各处。
 */
class SettingsViewModel(
    private val container: AppContainer,
) : ViewModel() {

    private val applier = container.appSettingsApplier
    private val settings = container.settings
    private val repository = container.repository
    private val locationService = container.locationService

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    private val eventChannel = Channel<SettingsUiEvent>(Channel.BUFFERED)
    val events = eventChannel.receiveAsFlow()

    /**
     * P2-6b：设置写入串行化。
     *
     * 原来每个 setter 在启动协程前就 `settings.load()` 出 old，再整体 `save(new)`；
     * 快速连点两个开关时，两次 transform 可能都基于同一份旧值，最后一个覆盖前一个。
     * 现在把"读 old → transform → commit"整段放进同一把锁，后一个一定看到前一个的结果。
     */
    private val writeMutex = Mutex()

    init {
        _state.value = _state.value.copy(
            settings = settings.load(),
            appVersion = applier.appVersion(),
        )
    }

    /**
     * 进程启动序列（开库 / 迁移 / 索引 / 排提醒）。由 `MainActivity` 调一次。
     *
     * P1-16：开库 / 迁移失败不再静默吞掉 —— 写入 [SettingsUiState.startupError]，
     * `MainActivity` 显示"数据读取失败 + 重试"，避免用户看到空日记本后误操作。
     */
    suspend fun bootstrap() {
        val result = runCatching { applier.bootstrap() }
        val error = result.exceptionOrNull()
        if (error != null) {
            Log.app.e("启动序列失败：${error.message}", error)
            _state.value = _state.value.copy(
                startupError = error.message ?: error::class.java.simpleName,
            )
            return
        }
        _state.value = _state.value.copy(startupError = null)
        refreshSettings()
        refreshStatus()
    }

    /** 回到前台：重排提醒 + 刷新权限状态。对应 iOS 的 `didBecomeActive` 分支。 */
    suspend fun onForeground() {
        applier.onForeground()
        refreshStatus()
    }

    /** 重新从存储读设置（对应 iOS `refreshSettings()`）。 */
    fun refreshSettings() {
        _state.value = _state.value.copy(settings = settings.load())
    }

    /** 重新探测定位/通知权限状态（对应 iOS `refreshStatus()`）。 */
    suspend fun refreshStatus() {
        val permission = if (!locationService.isAuthorized()) {
            LocationPermissionState.NONE
        } else if (locationService.isPrecise()) {
            LocationPermissionState.EXACT
        } else {
            LocationPermissionState.FUZZY
        }
        val granted = container.reminderScheduler.canPostNotifications()
        val storage = runCatching {
            withContext(Dispatchers.IO) {
                StorageUsage.measure(container.appContext, repository.imagesDir)
            }
        }.getOrDefault(StorageUsage())
        _state.value = _state.value.copy(
            locationPermission = permission,
            notificationsGranted = granted,
            storage = storage,
        )
    }

    // ------------------------------------------------------------------ 各设置项

    fun setTheme(mode: String) = update(transform = { it.copy(themeMode = mode) })

    fun setLanguage(language: String) = update(transform = { it.copy(appLanguage = language) })

    fun setWeekStart(value: String) = update(transform = { it.copy(weekStart = value) })

    fun setAutoTime(value: Boolean) = update(transform = { it.copy(autoTime = value) })

    fun setDynamicColor(value: Boolean) = update(transform = { it.copy(dynamicColor = value) })

    fun setAllowHistoryEdit(value: Boolean) = update(transform = { it.copy(allowHistoryEdit = value) })

    /**
     * 分享长图里的地点上限（`share_loc_precision`）。
     *
     * **不需要**新的 [com.cov.justdiary.service.SettingsEffect]：这个值只在生成分享图时读一次，
     * 不改变任何常驻界面的外观，因此不值得广播一次刷新。
     */
    fun setShareLocPrecision(value: String) = update(transform = { it.copy(shareLocPrecision = value) })

    /**
     * `auto_loc`。由关变开的副作用（申请权限 + 回填历史地区）由
     * `SettingsEffects` 决定，其中"申请权限"会以 [SettingsUiEvent.RequestLocationPermission] 冒出来。
     */
    fun setAutoLoc(value: Boolean) = update(transform = { it.copy(autoLoc = value) })

    /**
     * 「新一天的开始时间」（规范 §3.4）。
     *
     * **先算再问再改**：先只算不写地求出"会改变归属日的日记篇数"；
     *  - 0 篇 → 直接应用（对用户没有可见影响，不该多问一句）；
     *  - N 篇 → 记下待确认的小时与篇数，由界面弹确认框；用户点「继续」才走 [confirmDayStartHour]。
     *
     * iOS 的对应路径是"先改完、有冲突才提示"，用户没有反悔的机会。
     */
    fun requestDayStartHour(hour: Int) {
        val clamped = hour.coerceIn(0, 23)
        if (clamped == _state.value.settings.dayStartHour) return
        viewModelScope.launch {
            val plan = runCatching { repository.previewDayKeyChanges(clamped) }.getOrNull()
            if (plan == null || plan.movedBlocks == 0) {
                applyDayStartHour(clamped)
                return@launch
            }
            _state.value = _state.value.copy(
                pendingDayStartHour = clamped,
                pendingDayStartMovedBlocks = plan.movedBlocks,
            )
        }
    }

    /** 用户在确认框里点了「继续」。 */
    fun confirmDayStartHour() {
        val hour = _state.value.pendingDayStartHour ?: return
        _state.value = _state.value.copy(pendingDayStartHour = null, pendingDayStartMovedBlocks = 0)
        applyDayStartHour(hour)
    }

    /** 用户在确认框里点了「取消」。 */
    fun cancelDayStartHour() {
        _state.value = _state.value.copy(pendingDayStartHour = null, pendingDayStartMovedBlocks = 0)
    }

    /**
     * 真正应用：写设置 → 重算 `day_key` → 冲突数非零时补一条说明。
     */
    private fun applyDayStartHour(hour: Int) = update(
        transform = { it.copy(dayStartHour = hour.coerceIn(0, 23)) },
        onOutcome = { outcome ->
            if (outcome.dayKeyConflicts > 0) {
                _state.value = _state.value.copy(
                    alert = SettingsAlert.DayKeysRecomputed(outcome.dayKeyConflicts),
                )
            }
        },
    )

    fun setRemindEnabled(value: Boolean) {
        val wasEnabled = _state.value.settings.remindEnabled
        update(
            transform = { it.copy(remindEnabled = value) },
            onOutcome = {
                if (value && !wasEnabled && !_state.value.notificationsGranted) {
                    eventChannel.trySend(SettingsUiEvent.RequestNotificationPermission)
                }
            },
        )
    }

    fun applyRemindTime(hour: Int, minute: Int) = update(
        transform = {
            it.copy(remindHour = hour.coerceIn(0, 23), remindMinute = minute.coerceIn(0, 59))
        },
    )

    /** 点"位置权限"行：没授权就申请，否则跳系统设置（对应 iOS `handleLocPermission()`）。 */
    fun onLocationPermissionRowClick() {
        if (_state.value.locationPermission == LocationPermissionState.NONE) {
            eventChannel.trySend(SettingsUiEvent.RequestLocationPermission)
        } else {
            eventChannel.trySend(SettingsUiEvent.OpenAppSettings)
        }
    }

    /** 点"通知权限"行：直接申请（拒绝过之后用户在系统设置里改，不再反复弹）。 */
    fun onNotificationPermissionRowClick() {
        eventChannel.trySend(SettingsUiEvent.RequestNotificationPermission)
    }

    fun onLocationPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            refreshStatus()
            if (!granted) {
                Log.location.w("用户未授予定位权限")
            } else if (_state.value.settings.autoLoc) {
                // 授权到手后补跑一次回填（`commit` 里那条因为当时没授权被跳过了）
                runCatching {
                    com.cov.justdiary.service.RegionBackfiller.run(repository) { latitude, longitude ->
                        locationService.reverseGeocode(
                            com.cov.justdiary.service.LocationFix(latitude, longitude),
                            settings.load().appLanguage,
                        )
                    }
                }.onFailure { Log.location.e("回填地区失败：${it.message}") }
            }
        }
    }

    fun onNotificationPermissionResult(granted: Boolean) {
        viewModelScope.launch {
            refreshStatus()
            if (granted) {
                container.reminderScheduler.rearm()
            }
        }
    }

    // ------------------------------------------------------------------ 备份

    /**
     * 导出备份到用户选定的位置。
     *
     * iOS 是"先生成到临时目录，再 `ShareLink` 分享"；Android 走 SAF 的"创建文档"，
     * 因此这里生成后直接写进目标 Uri（少一次用户操作，行为等价）。
     */
    fun exportTo(context: Context, target: Uri, includeSettings: Boolean) {
        if (_state.value.busy != null) return
        _state.value = _state.value.copy(busy = BusyKind.EXPORT)
        viewModelScope.launch {
            val result = runCatching {
                val source = container.backupService.exportBackup(includeSettings)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(target, "wt")?.use { out ->
                        source.inputStream().use { it.copyTo(out) }
                    } ?: error("无法写入所选位置")
                }
                source.delete()
            }
            _state.value = _state.value.copy(
                busy = null,
                alert = if (result.isFailure) SettingsAlert.ExportFailed else _state.value.alert,
            )
            result.onFailure { Log.backup.e("导出失败：${it.message}") }
        }
    }

    /** 用户从 SAF 选完文件：先把内容拷进缓存，再弹"导入方式"。 */
    fun stageImport(context: Context, source: Uri) {
        if (_state.value.busy != null) return
        viewModelScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val target = File(context.cacheDir, "jdiary_import_${System.currentTimeMillis()}.jdiary")
                    context.contentResolver.openInputStream(source)?.use { input ->
                        target.outputStream().use { input.copyTo(it) }
                    } ?: error("无法读取所选文件")
                    target
                }.getOrNull()
            }
            if (file == null) {
                _state.value = _state.value.copy(alert = SettingsAlert.ImportFailed)
                return@launch
            }
            _state.value = _state.value.copy(pendingImport = file, importModePromptVisible = true)
        }
    }

    fun cancelImportPrompt() {
        _state.value = _state.value.copy(importModePromptVisible = false, pendingImport = null)
    }

    /** 执行导入（对应 iOS `runImport(mode:)`）。 */
    fun runImport(mode: ImportMode) {
        val file = _state.value.pendingImport
        _state.value = _state.value.copy(importModePromptVisible = false, pendingImport = null)
        if (file == null || _state.value.busy != null) return
        val old = settings.load()
        _state.value = _state.value.copy(busy = BusyKind.IMPORT)
        viewModelScope.launch {
            val result = runCatching { container.backupService.importBackup(file, mode) }
            file.delete()
            result.onSuccess { stats ->
                runCatching {
                    val protected = withContext(Dispatchers.IO) { container.editorDrafts.referencedImageSources() }
                    repository.cleanupOrphanImages(protected)
                }.onFailure { Log.db.w("恢复后清理孤儿图片失败：${it.message}") }
                val restored = stats.restoredSettings
                if (restored == null) {
                    container.reminderScheduler.rearm()
                    _state.value = _state.value.copy(
                        busy = null,
                        settings = settings.load(),
                        alert = SettingsAlert.ImportSucceeded(stats),
                    )
                    return@onSuccess
                }

                val dayStartChanged = restored.dayStartHour != old.dayStartHour
                // 先只采用“除了换日时刻之外”的设置；换日时刻要等用户确认后再重算。
                val adopted = if (dayStartChanged) {
                    restored.copy(dayStartHour = old.dayStartHour)
                } else {
                    restored
                }
                val outcome = runCatching { applier.applyImported(old, adopted) }
                    .onFailure { Log.app.e("导入后应用设置失败：${it.message}") }
                    .getOrNull()
                if (outcome?.needsLocationPermissionRequest == true) {
                    eventChannel.trySend(SettingsUiEvent.RequestLocationPermission)
                }
                _state.value = _state.value.copy(settings = settings.load())

                if (dayStartChanged) {
                    val moved = runCatching {
                        repository.previewDayKeyChanges(restored.dayStartHour)
                    }.getOrNull()?.movedBlocks ?: 0
                    if (moved > 0) {
                        // 等用户在对话框里选“重算 / 保留当前”。
                        _state.value = _state.value.copy(
                            busy = null,
                            pendingImportDayStartHour = restored.dayStartHour,
                            pendingImportDayStartMovedBlocks = moved,
                            pendingImportDayStartStats = stats,
                            alert = null,
                        )
                        return@onSuccess
                    }
                    // 没有片段会被移动：直接采用备份的换日时刻，不需要再问。
                    runCatching { applier.commit(settings.load(), restored) }
                        .onFailure { Log.app.e("导入后应用换日时刻失败：${it.message}") }
                }

                _state.value = _state.value.copy(
                    busy = null,
                    settings = settings.load(),
                    alert = SettingsAlert.ImportSucceeded(stats),
                )
            }
            result.onFailure { error ->
                Log.backup.e("导入失败：${error.message}")
                _state.value = _state.value.copy(
                    busy = null,
                    alert = SettingsAlert.ImportFailed,
                )
            }
        }
    }

    /** 用户确认按备份的换日时刻重算现有日记。 */
    fun confirmImportDayStart() {
        val hour = _state.value.pendingImportDayStartHour ?: return
        val stats = _state.value.pendingImportDayStartStats ?: return
        val current = settings.load()
        val target = current.copy(dayStartHour = hour.coerceIn(0, 23))
        _state.value = _state.value.copy(
            pendingImportDayStartHour = null,
            pendingImportDayStartMovedBlocks = 0,
            pendingImportDayStartStats = null,
        )
        viewModelScope.launch {
            runCatching { applier.commit(current, target) }
                .onFailure { Log.app.e("导入后重算 day_key 失败：${it.message}") }
            _state.value = _state.value.copy(
                settings = settings.load(),
                alert = SettingsAlert.ImportSucceeded(stats),
            )
        }
    }

    /** 用户选择保留当前换日时刻；导入的数据与其它设置已经生效。 */
    fun cancelImportDayStart() {
        val stats = _state.value.pendingImportDayStartStats ?: return
        _state.value = _state.value.copy(
            pendingImportDayStartHour = null,
            pendingImportDayStartMovedBlocks = 0,
            pendingImportDayStartStats = null,
            alert = SettingsAlert.ImportSucceeded(stats),
        )
    }

    fun consumeAlert() {
        _state.value = _state.value.copy(alert = null)
    }

    // ------------------------------------------------------------------ 内部

    /**
     * 保存一项设置并执行副作用。所有 setter 都收敛到这里，保证：
     * 旧值一定是从**存储里读到的当前值**（而不是界面可能过期的副本）。
     */
    private fun update(
        transform: (AppSettings) -> AppSettings,
        onOutcome: (com.cov.justdiary.app.SettingsApplyOutcome) -> Unit = {},
    ) {
        viewModelScope.launch {
            writeMutex.withLock {
                val old = settings.load()
                val new = transform(old)
                if (new == old) {
                    _state.value = _state.value.copy(settings = old)
                    return@withLock
                }
                _state.value = _state.value.copy(settings = new)
                val outcome = runCatching { applier.commit(old, new) }
                    .onFailure { Log.app.e("应用设置失败：${it.message}") }
                    .getOrDefault(com.cov.justdiary.app.SettingsApplyOutcome())
                _state.value = _state.value.copy(settings = settings.load())
                if (outcome.needsLocationPermissionRequest) {
                    eventChannel.trySend(SettingsUiEvent.RequestLocationPermission)
                }
                onOutcome(outcome)
            }
        }
    }

    companion object {
        fun factory(container: AppContainer): ViewModelProvider.Factory = viewModelFactory {
            initializer { SettingsViewModel(container) }
        }
    }
}
