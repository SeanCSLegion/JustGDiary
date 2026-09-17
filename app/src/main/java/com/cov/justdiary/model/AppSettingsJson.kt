package com.cov.justdiary.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * `settingsJson` 的传输形态 —— **只含 10 个业务设置项**，key 为 snake_case。
 *
 * 为什么单独一个 DTO 而不是直接序列化 [AppSettings]：iOS 的 `AppSettings` 就是这 10 项，
 * 而 `search_index_version` / `content_format_version` 被刻意排除在 `AppSettings` 之外
 * （见 `AppSettings.swift:76-82` 的注释：它们版本化的是**数据**，恢复备份不能重置它们）。
 * 用 DTO 可以把这个约定固化下来。
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
) {
    /** 转成内存中的设置；非法取值会被 [AppSettings.from] 的同一套校验兜住。 */
    fun toSettings(current: AppSettings): AppSettings = current.copy(
        dayStartHour = dayStartHour.coerceIn(0, 23),
        autoTime = autoTime,
        autoLoc = autoLoc,
        allowHistoryEdit = allowHistoryEdit,
        themeMode = if (themeMode in AppSettings.THEME_MODES) themeMode else AppSettings.THEME_SYSTEM,
        appLanguage = if (appLanguage in AppSettings.LANGUAGES) appLanguage else AppSettings.LANGUAGE_SYSTEM,
        weekStart = if (weekStart in AppSettings.WEEK_STARTS) weekStart else AppSettings.WEEK_START_MONDAY,
        remindEnabled = remindEnabled,
        remindHour = remindHour.coerceIn(0, 23),
        remindMinute = remindMinute.coerceIn(0, 59),
        // searchIndexVersion / contentFormatVersion 刻意不动：它们是数据版本，不是偏好
    )

    companion object {
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
        )
    }
}
