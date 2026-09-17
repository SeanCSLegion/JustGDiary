package com.cov.justdiary.service

import android.content.Context
import android.content.SharedPreferences
import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.SettingsKeys
import com.cov.justdiary.model.SettingsRaw

/**
 * 设置存储。对应 iOS `SettingsStore`（`UserDefaults(suiteName: "group.com.cov.justdiary")`）。
 *
 * Android 没有 App Group，同包名同进程直接用一个私有 `SharedPreferences` 即可
 * （见方案 §4.7「App Group 无 Android 对应」）。**key 名与 iOS 完全一致**。
 *
 * 业务设置（10 个）走 [load]/[save]；三个内部状态（索引版本、内容格式版本、反地理编码跳过名单）
 * 有各自的读写入口，不参与 [save] 的整体覆盖 —— 与 iOS 一致。
 */
class SettingsStore(context: Context) : SettingsRaw {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    @Volatile
    private var cache: AppSettings? = null

    /** 读取全部设置（带内存缓存）。 */
    fun load(): AppSettings {
        cache?.let { return it }
        val loaded = AppSettings.from(this)
        cache = loaded
        return loaded
    }

    /** 写回 10 个业务设置项（不影响三个内部状态 key）。 */
    fun save(settings: AppSettings) {
        prefs.edit()
            .putInt(SettingsKeys.DAY_START_HOUR, settings.dayStartHour)
            .putBoolean(SettingsKeys.AUTO_TIME, settings.autoTime)
            .putBoolean(SettingsKeys.AUTO_LOC, settings.autoLoc)
            .putBoolean(SettingsKeys.ALLOW_HISTORY_EDIT, settings.allowHistoryEdit)
            .putString(SettingsKeys.THEME_MODE, settings.themeMode)
            .putString(SettingsKeys.APP_LANGUAGE, settings.appLanguage)
            .putString(SettingsKeys.WEEK_START, settings.weekStart)
            .putBoolean(SettingsKeys.REMIND_ENABLED, settings.remindEnabled)
            .putInt(SettingsKeys.REMIND_HOUR, settings.remindHour)
            .putInt(SettingsKeys.REMIND_MINUTE, settings.remindMinute)
            // Android 专有：动态取色
            .putBoolean(SettingsKeys.DYNAMIC_COLOR, settings.dynamicColor)
            .apply()
        cache = settings
    }

    fun invalidate() {
        cache = null
    }

    /** FTS 索引格式版本；与 [AppSettings.SEARCH_INDEX_VERSION_REQUIRED] 比较。 */
    var searchIndexVersion: Int
        get() = prefs.getInt(SettingsKeys.SEARCH_INDEX_VERSION, 0)
        set(value) {
            prefs.edit().putInt(SettingsKeys.SEARCH_INDEX_VERSION, value).apply()
            invalidate()
        }

    /** `content_json` 存储格式版本；一次性迁移的标记。 */
    var contentFormatVersion: Int
        get() = prefs.getInt(SettingsKeys.CONTENT_FORMAT_VERSION, 0)
        set(value) {
            prefs.edit().putInt(SettingsKeys.CONTENT_FORMAT_VERSION, value).apply()
            invalidate()
        }

    /** 反地理编码失败、不再重试的 block id。 */
    fun regionGeocodeSkip(): Set<Long> =
        prefs.getStringSet(SettingsKeys.REGION_GEOCODE_SKIP_V2, emptySet())
            .orEmpty()
            .mapNotNull { it.toLongOrNull() }
            .toSet()

    /** 追加失败 id；上限 2000（与 iOS 一致，是**任意**截断而不是 LRU）。 */
    fun markRegionGeocodeSkipped(ids: Collection<Long>) {
        if (ids.isEmpty()) return
        val merged = regionGeocodeSkip() + ids
        val capped = merged.take(MAX_GEOCODE_SKIP)
        prefs.edit()
            .putStringSet(SettingsKeys.REGION_GEOCODE_SKIP_V2, capped.map { it.toString() }.toSet())
            .apply()
    }

    // ------------------------------------------------------------------ SettingsRaw

    override fun int(key: String, fallback: Int): Int = prefs.getInt(key, fallback)

    override fun bool(key: String, fallback: Boolean): Boolean = prefs.getBoolean(key, fallback)

    override fun string(key: String, fallback: String): String =
        prefs.getString(key, fallback) ?: fallback

    companion object {
        const val PREFS_NAME: String = "just_diary_settings"
        const val MAX_GEOCODE_SKIP: Int = 2000
    }
}
