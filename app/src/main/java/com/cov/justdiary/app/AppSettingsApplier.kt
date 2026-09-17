package com.cov.justdiary.app

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.cov.justdiary.data.DiaryRepository
import com.cov.justdiary.model.AppLanguage
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.service.LocationFix
import com.cov.justdiary.service.LocationService
import com.cov.justdiary.service.Log
import com.cov.justdiary.service.RegionBackfiller
import com.cov.justdiary.service.ReminderScheduler
import com.cov.justdiary.service.SettingsEffect
import com.cov.justdiary.service.SettingsEffects
import com.cov.justdiary.service.SettingsStore

/**
 * 设置变更的执行结果。
 *
 * 有两件事这个类**做不了**，必须由界面层接过去：
 * 定位权限申请（要 Activity 的 `requestPermissions` 流程）、以及"重算 day_key 后提示用户"
 * 的那个弹窗。因此不在这里吞掉，而是显式回报给调用方。
 */
data class SettingsApplyOutcome(
    /** `day_key` 因规则变化而**保留原归属日**的日记数（对应 iOS 的重算冲突提示）。 */
    val dayKeyConflicts: Int = 0,
    /** 需要界面层去申请定位权限（`auto_loc` 由关变开）。 */
    val needsLocationPermissionRequest: Boolean = false,
)

/**
 * 把 [AppSettings] 的变化落到平台上。
 *
 * iOS 的 `SettingsViewModel` 每个 setter 里都跟着一串副作用；那份"清单"已经被抽成纯函数
 * [SettingsEffects.effectsOf]（可单测），这里只负责**执行**那些副作用 —— 也就是把
 * 副作用清单翻译成 `AppCompatDelegate` / 仓库 / 提醒调度器的真实调用。
 *
 * | 副作用 | 这里怎么落 |
 * | --- | --- |
 * | [SettingsEffect.ApplyNightMode] | `AppCompatDelegate.setDefaultNightMode` |
 * | [SettingsEffect.SwitchLanguage] | `AppCompatDelegate.setApplicationLocales`（系统会重建 Activity） |
 * | [SettingsEffect.RecomputeDayKeys] | `DiaryRepository.recomputeDayKeys`，冲突数回报给界面 |
 * | [SettingsEffect.BackfillRegions] | [RegionBackfiller]（授权后才有意义） |
 * | [SettingsEffect.RearmReminder] | [ReminderScheduler.rearm] |
 * | [SettingsEffect.RequestLocationPermission] | **不在这里做**，置位 [SettingsApplyOutcome.needsLocationPermissionRequest] |
 * | [SettingsEffect.RefreshUi] | 不需要动作：Compose 的状态变化会自然重组 |
 */
class AppSettingsApplier(
    private val context: Context,
    private val settings: SettingsStore,
    private val repository: DiaryRepository,
    private val locationService: LocationService,
    private val reminderScheduler: ReminderScheduler,
) {

    private val appContext: Context = context.applicationContext

    /**
     * 进程启动时的一次性收尾。
     *
     * 对应 iOS `JustDiaryApp` 的启动序列：
     * 应用外观（深色/语言）→ `DiaryRepository.prepare()`（建表 + 一次性迁移）
     * → 搜索索引 → 提醒排期（`ensureDailyReminder`）。
     */
    suspend fun bootstrap() {
        val current = settings.load()
        applyNightMode(current.themeMode)
        applyLanguage(current.appLanguage)
        runCatching { repository.prepare() }
            .onFailure { Log.app.e("prepare 失败：${it.message}") }
        runCatching { repository.ensureSearchIndex() }
            .onFailure { Log.app.e("搜索索引准备失败：${it.message}") }
        reminderScheduler.ensureChannel()
        runCatching { reminderScheduler.rearm() }
            .onFailure { Log.app.e("提醒排期失败：${it.message}") }
    }

    /**
     * 回到前台。对应 iOS `scenePhase == .active` 时的 `ensureDailyReminder()`：
     * 时区/日期可能已经变了，每次回前台都重排一次（`rearm` 本身是幂等的）。
     */
    suspend fun onForeground() {
        runCatching { reminderScheduler.rearm() }
            .onFailure { Log.app.w("回前台重排提醒失败：${it.message}") }
    }

    /**
     * 保存设置并执行相应副作用。
     *
     * 调用方传入**改之前**的 [old]（与改之后的 [new]），差异由 [SettingsEffects] 判定 ——
     * 这样 `commit` 不会因为"哪个 setter 该跟着做什么"而背离 iOS 的行为。
     */
    suspend fun commit(old: AppSettings, new: AppSettings): SettingsApplyOutcome {
        settings.save(new)
        var conflicts = 0
        var needsLocationPermission = false

        for (effect in SettingsEffects.effectsOf(old, new)) {
            when (effect) {
                SettingsEffect.ApplyNightMode -> applyNightMode(new.themeMode)
                SettingsEffect.SwitchLanguage -> applyLanguage(new.appLanguage)
                SettingsEffect.RecomputeDayKeys ->
                    conflicts = runCatching { repository.recomputeDayKeys(new.dayStartHour) }
                        .onFailure { Log.app.e("重算 day_key 失败：${it.message}") }
                        .getOrDefault(0)

                SettingsEffect.RequestLocationPermission -> needsLocationPermission = true
                SettingsEffect.BackfillRegions -> backfillRegions(new.appLanguage)
                SettingsEffect.RearmReminder -> runCatching { reminderScheduler.rearm() }
                    .onFailure { Log.app.w("重排提醒失败：${it.message}") }

                SettingsEffect.RefreshUi -> Unit
            }
        }
        settings.invalidate()
        return SettingsApplyOutcome(
            dayKeyConflicts = conflicts,
            needsLocationPermissionRequest = needsLocationPermission,
        )
    }

    /**
     * 回填历史片段的地区列。
     *
     * 没有授权就直接跳过：iOS 也是在 `requestPermission()` 之后**立刻**发起回填（存在竞态，
     * 首轮多半拿不到授权而空跑）；这里改成等授权到手再有意义地跑，避免把 30 条全记进
     * "放弃重试"名单。
     */
    private suspend fun backfillRegions(appLanguage: String) {
        if (!locationService.isAuthorized()) {
            Log.location.w("未授权定位，跳过历史地区回填")
            return
        }
        runCatching {
            RegionBackfiller.run(repository) { latitude, longitude ->
                locationService.reverseGeocode(LocationFix(latitude, longitude), appLanguage)
            }
        }.onFailure { Log.location.e("地区回填失败：${it.message}") }
    }

    private fun applyNightMode(themeMode: String) {
        val mode = when (SettingsEffects.nightModeFor(themeMode)) {
            SettingsEffects.NightMode.Light -> AppCompatDelegate.MODE_NIGHT_NO
            SettingsEffects.NightMode.Dark -> AppCompatDelegate.MODE_NIGHT_YES
            SettingsEffects.NightMode.FollowSystem -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
        }
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    /**
     * 应用内语言。
     *
     * minSdk 33 起 `AppCompatDelegate.setApplicationLocales` 走平台的
     * `LocaleManager`（配合 `AndroidManifest` 的 `android:localeConfig`），
     * 不需要 AppCompat 的 `autoStoreLocales` 存储服务；"跟随系统"用空 locale 列表表示。
     */
    private fun applyLanguage(appLanguage: String) {
        val tag = AppLanguage.localeTag(appLanguage)
        val locales = if (tag == null) {
            LocaleListCompat.getEmptyLocaleList()
        } else {
            LocaleListCompat.forLanguageTags(tag)
        }
        AppCompatDelegate.setApplicationLocales(locales)
    }

    /** 版本号（设置页"关于"卡片）。iOS 侧是 `SettingsStore.appVersion`。 */
    fun appVersion(): String = runCatching {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName.orEmpty()
    }.getOrDefault("")
}
