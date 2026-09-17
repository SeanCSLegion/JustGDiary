package com.cov.justdiary.service

import com.cov.justdiary.model.AppSettings

/**
 * 设置项的**副作用**清单。
 *
 * iOS 的 `SettingsViewModel` 里每个 setter 都跟着一串副作用（改完设置要重算什么、要申请什么权限）。
 * 散在 setter 里有两个问题：一是"改了什么会连带发生什么"看不全，二是没法测。
 * 这里把它收敛成一个纯函数：给定新旧设置，算出**需要执行哪些副作用**。
 *
 * 副作用的清单逐条来自 iOS 的调用点（`SettingsViewModel.swift:38-133`）：
 *
 * | 设置项 | 副作用 |
 * | --- | --- |
 * | `day_start_hour` | 重算全部 `day_key`（可能冲突，需要提示）+ 刷新界面 |
 * | `theme_mode` | 应用深浅色 + 清空日历绘制缓存 + 刷新界面 |
 * | `app_language` | 切应用内语言（Activity 重建）+ 重排提醒（通知文案要跟着语言）+ 刷新界面 |
 * | `week_start` | 刷新界面（周起始影响日历与搜索的"本周"） |
 * | `auto_loc` 开 | 申请定位权限 + 回填历史片段的地区列 |
 * | `remind_enabled` / `remind_hour` / `remind_minute` | 重排提醒 |
 * | `dynamic_color` | 刷新界面（颜色方案变了） |
 */
enum class SettingsEffect {
    /** 重算全部 `day_key`（`DiaryRepository.recomputeDayKeys`）。 */
    RecomputeDayKeys,

    /** 申请定位权限（`LocationService.requestPermission`）。 */
    RequestLocationPermission,

    /** 回填历史片段的地区列（`DiaryRepository.backfillBlockRegions`）。 */
    BackfillRegions,

    /** 应用深浅色（`AppCompatDelegate.setDefaultNightMode`）。 */
    ApplyNightMode,

    /** 切换应用内语言（`AppCompatDelegate.setApplicationLocales`，会重建 Activity）。 */
    SwitchLanguage,

    /** 重排每日提醒（时区/语言/时间变了都要重排 —— 与 iOS 的 `rearm()` 调用点一致）。 */
    RearmReminder,

    /** 清空绘制缓存并刷新界面（主题/动态取色/周起始等变化后重绘）。 */
    RefreshUi,
}

object SettingsEffects {

    /**
     * 计算从 [old] 到 [new] 需要执行的副作用（顺序即建议的执行顺序）。
     *
     * 注意：`RequestLocationPermission` 与 `BackfillRegions` **只在 `auto_loc` 由关变开**时触发
     * （与 iOS 的 `setAutoLoc` 一致）；关掉自动定位只是改配置，不需要动数据。
     */
    fun effectsOf(old: AppSettings, new: AppSettings): List<SettingsEffect> {
        val effects = mutableListOf<SettingsEffect>()

        if (old.dayStartHour != new.dayStartHour) {
            effects.add(SettingsEffect.RecomputeDayKeys)
        }
        if (old.themeMode != new.themeMode) {
            effects.add(SettingsEffect.ApplyNightMode)
        }
        if (old.appLanguage != new.appLanguage) {
            effects.add(SettingsEffect.SwitchLanguage)
            // 通知文案要跟着语言走
            effects.add(SettingsEffect.RearmReminder)
        }
        val autoLocTurnedOn = !old.autoLoc && new.autoLoc
        if (autoLocTurnedOn) {
            effects.add(SettingsEffect.RequestLocationPermission)
            effects.add(SettingsEffect.BackfillRegions)
        }
        val reminderChanged = old.remindEnabled != new.remindEnabled ||
            old.remindHour != new.remindHour ||
            old.remindMinute != new.remindMinute
        if (reminderChanged) {
            effects.add(SettingsEffect.RearmReminder)
        }

        // 任何影响到界面取值的变化都刷新一次（周起始、动态取色、深浅色、语言…）
        if (affectsUi(old, new)) {
            effects.add(SettingsEffect.RefreshUi)
        }
        return effects
    }

    /** 是否需要"清绘制缓存 + 刷新界面"。 */
    fun affectsUi(old: AppSettings, new: AppSettings): Boolean =
        old.themeMode != new.themeMode ||
            old.dynamicColor != new.dynamicColor ||
            old.weekStart != new.weekStart ||
            old.appLanguage != new.appLanguage ||
            old.dayStartHour != new.dayStartHour ||
            old.allowHistoryEdit != new.allowHistoryEdit

    /** 语言 → 系统 locale 标签（`system` 返回 null 表示跟随系统）。 */
    fun localeTagFor(appLanguage: String): String? = com.cov.justdiary.model.AppLanguage.localeTag(appLanguage)

    /** 深浅色 → `AppCompatDelegate` 的模式常量（由调用方传入实际常量，这里只做分类）。 */
    fun nightModeFor(themeMode: String): NightMode = when (themeMode) {
        AppSettings.THEME_LIGHT -> NightMode.Light
        AppSettings.THEME_DARK -> NightMode.Dark
        else -> NightMode.FollowSystem
    }

    enum class NightMode { Light, Dark, FollowSystem }
}
