package com.cov.justdiary.data

import com.cov.justdiary.model.AppSettings
import com.cov.justdiary.model.AppSettingsJson
import com.cov.justdiary.model.LocPrecision
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 备份清单与设置 JSON 的跨端兼容测试。
 *
 * 备份包不像 `content_json` 那样要求逐字节一致（iOS 用无选项的 `JSONEncoder()`，
 * 键序不保证），但**字段名与类型必须能互相解析**，而且 `settingsJson` 的键集合
 * 是硬契约：iOS 的 `AppSettings` 只有 10 个业务项，`search_index_version` /
 * `content_format_version` 被刻意排除（它们是数据版本，恢复备份不能重置）。
 */
class BackupModelsTest {

    private val json = Json {
        encodeDefaults = true
        explicitNulls = false
        ignoreUnknownKeys = true
    }

    /** 按 iOS `BackupManifest` 的形状手写的样本（字段名与顺序都照抄）。 */
    private val iosManifest = """
        {"formatVersion":1,"appVersion":"1.0","exportedAt":1760000000000,"includeSettings":true,
         "diaryCount":16,"blockCount":17,"imageCount":2,
         "images":[{"src":"images/img_1.jpg","file":"images/img_1.jpg"},
                   {"src":"images/a.jpg","file":"images/a_1.jpg"}],
         "settingsJson":"{\"day_start_hour\":4,\"auto_time\":true,\"auto_loc\":false,\"allow_history_edit\":false,\"theme_mode\":\"dark\",\"app_language\":\"zh\",\"week_start\":\"sunday\",\"remind_enabled\":false,\"remind_hour\":8,\"remind_minute\":30}"}
    """.trimIndent()

    @Test
    fun `ios shaped manifest parses`() {
        val manifest = json.decodeFromString(BackupManifest.serializer(), iosManifest)

        assertEquals(1, manifest.formatVersion)
        assertEquals("1.0", manifest.appVersion)
        assertEquals(1_760_000_000_000L, manifest.exportedAt)
        assertTrue(manifest.includeSettings)
        assertEquals(16, manifest.diaryCount)
        assertEquals(17, manifest.blockCount)
        assertEquals(2, manifest.imageCount)
        assertEquals(2, manifest.images.size)
        assertEquals("images/a.jpg", manifest.images[1].src)
        assertEquals("images/a_1.jpg", manifest.images[1].file)
        assertTrue(manifest.settingsJson.isNotEmpty())
    }

    @Test
    fun `manifest round trips through kotlin`() {
        val original = json.decodeFromString(BackupManifest.serializer(), iosManifest)
        val encoded = json.encodeToString(BackupManifest.serializer(), original)
        val decoded = json.decodeFromString(BackupManifest.serializer(), encoded)
        assertEquals(original, decoded)
    }

    @Test
    fun `manifest encodes exactly the ios field names`() {
        val encoded = json.encodeToString(
            BackupManifest.serializer(),
            BackupManifest(
                formatVersion = 1,
                appVersion = "1.0",
                exportedAt = 1,
                includeSettings = false,
                diaryCount = 1,
                blockCount = 1,
                imageCount = 0,
                images = emptyList(),
                settingsJson = "",
            ),
        )
        val keys = (json.parseToJsonElement(encoded) as JsonObject).keys
        assertEquals(
            setOf(
                "formatVersion", "appVersion", "exportedAt", "includeSettings",
                "diaryCount", "blockCount", "imageCount", "images", "settingsJson",
            ),
            keys,
        )
    }

    @Test
    fun `settings json carries exactly the twelve business keys`() {
        val settings = AppSettings(
            dayStartHour = 5,
            autoTime = false,
            autoLoc = false,
            allowHistoryEdit = true,
            themeMode = AppSettings.THEME_DARK,
            appLanguage = AppSettings.LANGUAGE_ZH,
            weekStart = AppSettings.WEEK_START_SUNDAY,
            remindEnabled = false,
            remindHour = 8,
            remindMinute = 30,
            shareLocPrecision = "province",
            defaultLocPrecision = "city",
            // 这两个是数据版本，不是偏好 —— 绝不能进入 settingsJson
            searchIndexVersion = 3,
            contentFormatVersion = 2,
        )

        val encoded = json.encodeToString(AppSettingsJson.serializer(), AppSettingsJson.from(settings))
        val keys = (json.parseToJsonElement(encoded) as JsonObject).keys

        assertEquals(
            setOf(
                "day_start_hour", "auto_time", "auto_loc", "allow_history_edit",
                "theme_mode", "app_language", "week_start",
                "remind_enabled", "remind_hour", "remind_minute",
                // 与 iOS 同名同义的两个位置精度偏好（两端备份互认）
                "share_loc_precision", "default_loc_precision",
            ),
            keys,
        )
        assertFalse("数据版本不能进 settingsJson", encoded.contains("search_index_version"))
        assertFalse("数据版本不能进 settingsJson", encoded.contains("content_format_version"))
    }

    @Test
    fun `settings json round trips and preserves data versions`() {
        val current = AppSettings(searchIndexVersion = 3, contentFormatVersion = 2, dayStartHour = 4)
        val fromBackup = json.decodeFromString(
            AppSettingsJson.serializer(),
            """{"day_start_hour":7,"auto_time":false,"auto_loc":true,"allow_history_edit":true,
                "theme_mode":"dark","app_language":"en","week_start":"sunday",
                "remind_enabled":false,"remind_hour":6,"remind_minute":15}""",
        )

        val restored = fromBackup.toSettings(current)
        assertEquals(7, restored.dayStartHour)
        assertFalse(restored.autoTime)
        assertTrue(restored.allowHistoryEdit)
        assertEquals(AppSettings.THEME_DARK, restored.themeMode)
        assertEquals(AppSettings.LANGUAGE_EN, restored.appLanguage)
        assertEquals(AppSettings.WEEK_START_SUNDAY, restored.weekStart)
        assertFalse(restored.remindEnabled)
        assertEquals(6, restored.remindHour)
        assertEquals(15, restored.remindMinute)
        // 数据版本必须保持不变
        assertEquals(3, restored.searchIndexVersion)
        assertEquals(2, restored.contentFormatVersion)
    }

    @Test
    fun `invalid settings values fall back to defaults`() {
        val fallback = json.decodeFromString(
            AppSettingsJson.serializer(),
            """{"theme_mode":"neon","app_language":"fr","week_start":"friday","day_start_hour":99}""",
        ).toSettings(AppSettings())

        assertEquals(AppSettings.THEME_SYSTEM, fallback.themeMode)
        assertEquals(AppSettings.LANGUAGE_SYSTEM, fallback.appLanguage)
        assertEquals(AppSettings.WEEK_START_MONDAY, fallback.weekStart)
        assertEquals(23, fallback.dayStartHour)
    }

    /**
     * 导入备份时，位置精度这两个偏好也要**只往安全一侧**收：
     * 上限比区县更细（或不是梯子上的值）→ 收到区县；`none`（隐藏）原样保留；
     * 记忆精度不认识 → 收回 `auto`。
     */
    @Test
    fun `an imported share precision can only be clamped coarser`() {
        fun imported(raw: String) = json.decodeFromString(
            AppSettingsJson.serializer(),
            """{"share_loc_precision":"$raw","default_loc_precision":"$raw"}""",
        ).toSettings(AppSettings())

        assertEquals(LocPrecision.district, imported("exact").shareLocPrecision)
        assertEquals(LocPrecision.district, imported("street").shareLocPrecision)
        assertEquals(LocPrecision.district, imported("坏值").shareLocPrecision)
        assertEquals(LocPrecision.none, imported("none").shareLocPrecision)
        assertEquals(LocPrecision.province, imported("province").shareLocPrecision)

        // 记忆精度是"梯子 + auto"，所以 exact 是合法值，只有不在梯子上的才收回 auto
        assertEquals(LocPrecision.exact, imported("exact").defaultLocPrecision)
        assertEquals(LocPrecision.auto, imported("坏值").defaultLocPrecision)
        assertEquals(LocPrecision.city, imported("city").defaultLocPrecision)
        // `none` 不是梯子上的级别（编辑器的菜单也从不提供它）→ 收回 auto
        assertEquals(LocPrecision.auto, imported("none").defaultLocPrecision)
    }

    @Test
    fun `unknown settings keys are ignored`() {
        val parsed = json.decodeFromString(
            AppSettingsJson.serializer(),
            """{"day_start_hour":4,"future_key":"whatever"}""",
        )
        assertEquals(4, parsed.dayStartHour)
    }

    @Test
    fun `import mode maps the ios wire values`() {
        assertEquals(ImportMode.SKIP, ImportMode.fromWire("skip"))
        assertEquals(ImportMode.OVERWRITE, ImportMode.fromWire("overwrite"))
        assertEquals("其它值都视为覆盖（与 iOS 一致）", ImportMode.OVERWRITE, ImportMode.fromWire(""))
    }

    @Test
    fun `format version constant matches ios`() {
        assertEquals(1, BackupManifest.FORMAT_VERSION)
        assertNotNull(BackupStats())
        assertEquals(0, BackupStats().importedDays)
        assertFalse(BackupStats().settingsRestored)
    }
}
