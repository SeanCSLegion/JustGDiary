package com.cov.justdiary.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `settingsJson` 的传输形态 —— **只含 12 个业务设置项**，key 为 snake_case。
 *
 * 为什么单独一个 DTO 而不是直接序列化 [AppSettings]：iOS 的 `AppSettings` 就是这 12 项，
 * 而 `search_index_version` / `content_format_version` 被刻意排除在 `AppSettings` 之外
 * （见 `AppSettings.swift:76-82` 的注释：它们版本化的是**数据**，恢复备份不能重置它们）。
 * 用 DTO 可以把这个约定固化下来。
 *
 * 两个 `loc_precision` 键与 iOS 同名同义（`AppSettings.swift` 的 `share_loc_precision` /
 * `default_loc_precision`），因此两端备份可以互相恢复；Android 专有的 `dynamic_color`
 * 不在其中。
 */
@Serializable
data class AppSettingsJson(
    @SerialName("day_start_hour") val dayStartHour: Int = AppSettings.DEFAULT_DAY_START_HOUR,
    @SerialName("auto_time") val autoTime: Boolean = true,
    @SerialName("auto_loc") val autoLoc: Boolean = true,
    @SerialName("allow_history_edit") val allowHistoryEdit: Boolean = false,
    @SerialName("theme_mode") val themeMode: String = AppSettings.THEME_SYSTEM,
    @SerialName("app_language") val appLanguage: String = AppSettings.LANGUAGE_SYSTEM,
    @SerialName("week_start") val weekStart: String = AppSettings.WEEK_START_MONDAY,
    @SerialName("remind_enabled") val remindEnabled: Boolean = true,
    @SerialName("remind_hour") val remindHour: Int = AppSettings.DEFAULT_REMIND_HOUR,
    @SerialName("remind_minute") val remindMinute: Int = AppSettings.DEFAULT_REMIND_MINUTE,
    @SerialName("share_loc_precision") val shareLocPrecision: String = LocPrecision.district,
    @SerialName("default_loc_precision") val defaultLocPrecision: String = LocPrecision.auto,
) {
    /**
     * 转成内存中的设置；非法取值会被 `AppSettings.from` 的同一套校验兜住。
     *
     * @param presentKeys 备份 JSON 里**实际出现**的键。旧备份（10 键）里没有
     *   `share_loc_precision` / `default_loc_precision`；这些缺失键必须保留 [current] 的值，
     *   不能用 DTO 的默认值把用户当前设置覆盖掉。
     */
    fun toSettings(
        current: AppSettings,
        presentKeys: Set<String> = BUSINESS_KEYS,
    ): AppSettings = current.copy(
        dayStartHour = if (KEY_DAY_START_HOUR in presentKeys) dayStartHour.coerceIn(0, 23) else current.dayStartHour,
        autoTime = if (KEY_AUTO_TIME in presentKeys) autoTime else current.autoTime,
        autoLoc = if (KEY_AUTO_LOC in presentKeys) autoLoc else current.autoLoc,
        allowHistoryEdit = if (KEY_ALLOW_HISTORY_EDIT in presentKeys) allowHistoryEdit else current.allowHistoryEdit,
        themeMode = if (KEY_THEME_MODE in presentKeys) {
            if (themeMode in AppSettings.THEME_MODES) themeMode else AppSettings.THEME_SYSTEM
        } else {
            current.themeMode
        },
        appLanguage = if (KEY_APP_LANGUAGE in presentKeys) {
            if (appLanguage in AppSettings.LANGUAGES) appLanguage else AppSettings.LANGUAGE_SYSTEM
        } else {
            current.appLanguage
        },
        weekStart = if (KEY_WEEK_START in presentKeys) {
            if (weekStart in AppSettings.WEEK_STARTS) weekStart else AppSettings.WEEK_START_MONDAY
        } else {
            current.weekStart
        },
        remindEnabled = if (KEY_REMIND_ENABLED in presentKeys) remindEnabled else current.remindEnabled,
        remindHour = if (KEY_REMIND_HOUR in presentKeys) remindHour.coerceIn(0, 23) else current.remindHour,
        remindMinute = if (KEY_REMIND_MINUTE in presentKeys) remindMinute.coerceIn(0, 59) else current.remindMinute,
        // 与 `AppSettings.from` 同一套取值域：比区县更细的上限要收回区县（隐藏则原样保留）
        shareLocPrecision = if (KEY_SHARE_LOC_PRECISION in presentKeys) {
            if (shareLocPrecision in AppSettings.SHARE_LOC_PRECISIONS) shareLocPrecision else LocPrecision.district
        } else {
            current.shareLocPrecision
        },
        defaultLocPrecision = if (KEY_DEFAULT_LOC_PRECISION in presentKeys) {
            if (defaultLocPrecision in AppSettings.DEFAULT_LOC_PRECISIONS) defaultLocPrecision else LocPrecision.auto
        } else {
            current.defaultLocPrecision
        },
        // searchIndexVersion / contentFormatVersion 刻意不动：它们是数据版本，不是偏好
    )

    companion object {
        const val KEY_DAY_START_HOUR = "day_start_hour"
        const val KEY_AUTO_TIME = "auto_time"
        const val KEY_AUTO_LOC = "auto_loc"
        const val KEY_ALLOW_HISTORY_EDIT = "allow_history_edit"
        const val KEY_THEME_MODE = "theme_mode"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_WEEK_START = "week_start"
        const val KEY_REMIND_ENABLED = "remind_enabled"
        const val KEY_REMIND_HOUR = "remind_hour"
        const val KEY_REMIND_MINUTE = "remind_minute"
        const val KEY_SHARE_LOC_PRECISION = "share_loc_precision"
        const val KEY_DEFAULT_LOC_PRECISION = "default_loc_precision"

        /** `settingsJson` 里全部业务键；缺键恢复时用这个集合与备份 JSON 的实际键求差。 */
        val BUSINESS_KEYS: Set<String> = setOf(
            KEY_DAY_START_HOUR,
            KEY_AUTO_TIME,
            KEY_AUTO_LOC,
            KEY_ALLOW_HISTORY_EDIT,
            KEY_THEME_MODE,
            KEY_APP_LANGUAGE,
            KEY_WEEK_START,
            KEY_REMIND_ENABLED,
            KEY_REMIND_HOUR,
            KEY_REMIND_MINUTE,
            KEY_SHARE_LOC_PRECISION,
            KEY_DEFAULT_LOC_PRECISION,
        )

        fun from(settings: AppSettings): AppSettingsJson = AppSettingsJson(
            dayStartHour = settings.dayStartHour,
            autoTime = settings.autoTime,
            autoLoc = settings.autoLoc,
            allowHistoryEdit = settings.allowHistoryEdit,
            themeMode = settings.themeMode,
            appLanguage = settings.appLanguage,
            weekStart = settings.weekStart,
            remindEnabled = settings.remindEnabled,
            remindHour = settings.remindHour,
            remindMinute = settings.remindMinute,
            shareLocPrecision = settings.shareLocPrecision,
            defaultLocPrecision = settings.defaultLocPrecision,
        )
    }
}
