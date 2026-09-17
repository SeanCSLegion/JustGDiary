package com.cov.justdiary.model

import com.cov.justdiary.service.Log
import java.util.Locale

/**
 * 设置项的存储 key 与取值域。移植自 iOS `JustDiary/Models/AppSettings.swift`。
 *
 * **key 名与 iOS 完全一致**，这样备份里的 `settingsJson` 可以在两端互相恢复
 * （见 `docs/android-migration-plan.md` §4.7.2）。
 */
object SettingsKeys {
    const val DAY_START_HOUR = "day_start_hour"
    const val AUTO_TIME = "auto_time"
    const val AUTO_LOC = "auto_loc"
    const val ALLOW_HISTORY_EDIT = "allow_history_edit"
    const val THEME_MODE = "theme_mode"
    const val APP_LANGUAGE = "app_language"
    const val WEEK_START = "week_start"
    const val REMIND_ENABLED = "remind_enabled"
    const val REMIND_HOUR = "remind_hour"
    const val REMIND_MINUTE = "remind_minute"

    /** 以下三个不是"偏好"，而是数据版本/内部状态（iOS 亦然）。 */
    const val SEARCH_INDEX_VERSION = "search_index_version"
    const val CONTENT_FORMAT_VERSION = "content_format_version"
    const val REGION_GEOCODE_SKIP_V2 = "region_geocode_skip_v2"
}

/** 读取原始设置的抽象，便于在不依赖 Android 的前提下测试取值校验。 */
interface SettingsRaw {
    fun int(key: String, fallback: Int): Int
    fun bool(key: String, fallback: Boolean): Boolean
    fun string(key: String, fallback: String): String
}

/**
 * 全部设置项的取值快照。
 *
 * iOS 侧 `SettingsStore.load()` 会逐 key 读取并**校验非法值后回退默认值**；
 * 这里把校验放在纯函数 [from]，`SettingsStore` 只负责读写。
 */
data class AppSettings(
    /** 新一天的开始时间（本地小时）。小于它的小时归前一天。 */
    val dayStartHour: Int = DEFAULT_DAY_START_HOUR,
    /** 自动插入时间。⚠️ iOS 侧这个开关**没有任何读取点**（是 bug，见方案 Q5）；Android 让它真实生效。 */
    val autoTime: Boolean = true,
    val autoLoc: Boolean = true,
    val allowHistoryEdit: Boolean = false,
    val themeMode: String = THEME_SYSTEM,
    val appLanguage: String = LANGUAGE_SYSTEM,
    val weekStart: String = WEEK_START_MONDAY,
    val remindEnabled: Boolean = true,
    val remindHour: Int = DEFAULT_REMIND_HOUR,
    val remindMinute: Int = DEFAULT_REMIND_MINUTE,
    /** FTS 索引格式版本；与 [SEARCH_INDEX_VERSION_REQUIRED] 比较决定是否重建。 */
    val searchIndexVersion: Int = 0,
    /** `content_json` 存储格式版本；与 `ContentDocument.CURRENT_VERSION` 比较决定是否一次性迁移。 */
    val contentFormatVersion: Int = 0,
) {
    companion object {
        const val DEFAULT_DAY_START_HOUR = 4
        const val DEFAULT_REMIND_HOUR = 21
        const val DEFAULT_REMIND_MINUTE = 0

        const val THEME_LIGHT = "light"
        const val THEME_DARK = "dark"
        const val THEME_SYSTEM = "system"

        const val LANGUAGE_SYSTEM = "system"
        const val LANGUAGE_ZH = "zh"
        const val LANGUAGE_EN = "en"

        const val WEEK_START_MONDAY = "monday"
        const val WEEK_START_SUNDAY = "sunday"

        /** 索引格式版本；与 iOS `DiaryRepository.searchIndexVersion = 3` 一致。 */
        const val SEARCH_INDEX_VERSION_REQUIRED = 3

        val THEME_MODES: Set<String> = setOf(THEME_LIGHT, THEME_DARK, THEME_SYSTEM)
        val LANGUAGES: Set<String> = setOf(LANGUAGE_SYSTEM, LANGUAGE_ZH, LANGUAGE_EN)
        val WEEK_STARTS: Set<String> = setOf(WEEK_START_MONDAY, WEEK_START_SUNDAY)

        /** 逐 key 读取并校验；非法值记录一条警告后回退默认值（与 iOS 行为一致）。 */
        fun from(raw: SettingsRaw): AppSettings {
            val defaults = AppSettings()
            return AppSettings(
                dayStartHour = raw.int(SettingsKeys.DAY_START_HOUR, defaults.dayStartHour)
                    .coerceIn(0, 23),
                autoTime = raw.bool(SettingsKeys.AUTO_TIME, defaults.autoTime),
                autoLoc = raw.bool(SettingsKeys.AUTO_LOC, defaults.autoLoc),
                allowHistoryEdit = raw.bool(SettingsKeys.ALLOW_HISTORY_EDIT, defaults.allowHistoryEdit),
                themeMode = raw.string(SettingsKeys.THEME_MODE, defaults.themeMode)
                    .validated(THEME_MODES, defaults.themeMode, SettingsKeys.THEME_MODE),
                appLanguage = raw.string(SettingsKeys.APP_LANGUAGE, defaults.appLanguage)
                    .validated(LANGUAGES, defaults.appLanguage, SettingsKeys.APP_LANGUAGE),
                weekStart = raw.string(SettingsKeys.WEEK_START, defaults.weekStart)
                    .validated(WEEK_STARTS, defaults.weekStart, SettingsKeys.WEEK_START),
                remindEnabled = raw.bool(SettingsKeys.REMIND_ENABLED, defaults.remindEnabled),
                remindHour = raw.int(SettingsKeys.REMIND_HOUR, defaults.remindHour).coerceIn(0, 23),
                remindMinute = raw.int(SettingsKeys.REMIND_MINUTE, defaults.remindMinute).coerceIn(0, 59),
                searchIndexVersion = raw.int(SettingsKeys.SEARCH_INDEX_VERSION, 0),
                contentFormatVersion = raw.int(SettingsKeys.CONTENT_FORMAT_VERSION, 0),
            )
        }

        private fun String.validated(allowed: Set<String>, fallback: String, key: String): String {
            if (this in allowed) return this
            Log.app.w("设置项 $key 的值非法（$this），回退默认值 $fallback")
            return fallback
        }
    }

    /** 是否需要重建 FTS 索引（与 iOS `isSearchIndexReady` 反向）。 */
    val needsSearchIndexRebuild: Boolean
        get() = searchIndexVersion < SEARCH_INDEX_VERSION_REQUIRED

    /** 是否需要把库里的 v1 内容一次性升级为 v2。 */
    val needsContentMigration: Boolean
        get() = contentFormatVersion < ContentDocument.CURRENT_VERSION
}

/** 应用内语言 → Android 资源 locale 的映射（iOS 用 `zh-Hans`，Android 资源目录是 `values-zh-rCN`）。 */
object AppLanguage {
    /** 返回 null 表示"跟随系统"。 */
    fun localeTag(appLanguage: String): String? = when (appLanguage) {
        AppSettings.LANGUAGE_ZH -> "zh-CN"
        AppSettings.LANGUAGE_EN -> "en"
        else -> null
    }

    /** 归一当前生效的语言，用于格式化与文案回退。 */
    fun resolve(appLanguage: String, systemTag: String): String = when (appLanguage) {
        AppSettings.LANGUAGE_ZH -> "zh-CN"
        AppSettings.LANGUAGE_EN -> "en"
        else -> if (systemTag.lowercase(Locale.ROOT).startsWith("zh")) "zh-CN" else "en"
    }
}
