package com.cov.justdiary.service

import android.content.Context
import android.text.format.DateFormat as AndroidDateFormat
import com.cov.justdiary.R
import com.cov.justdiary.model.AppLanguage
import com.cov.justdiary.model.DateUtil
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

/**
 * 日期/时间的**语义槽位**格式化。移植自 iOS `Services/L10n.swift:141-235`。
 *
 * ## 为什么要有这一层
 *
 * 同一类信息只允许一种写法（`docs/design-system.md` §4）：改造前 iOS 侧出现过
 * "时间写死 24 小时制"、"周标题用 ` – ` 而日标题用空格"、"区间用 `~`" 这些不一致。
 * 因此所有日期文案都必须走这里的槽位。
 *
 * ## 两条关键规则
 *
 * 1. **模板来自资源**（`date_formats.xml`，key 与 iOS 的 String Catalog 同名），
 *    月名/星期名由 `java.time` 按**应用内语言**生成 —— 不硬编码任何日期名；
 * 2. **小时制跟设备、AM/PM 跟应用语言**：设备设成 12 小时制时，中文界面显示
 *    "下午9:00"（AM/PM 符号取中文），英文界面显示 "9:00 PM"。
 *
 * 本类**不依赖 Android**（模板通过 [template] 注入、小时制通过 [uses24Hour] 注入），
 * 因此可以在 JVM 上精确断言中英两种输出；[forContext] 是 Android 侧的装配入口。
 */
class DateFormats(
    private val locale: Locale,
    private val uses24Hour: Boolean,
    private val template: (key: String) -> String,
) {

    // ------------------------------------------------------------------ 片段

    /** 年（`date_year`）：zh `2026年` / en `2026`。 */
    fun year(value: Int): String = format(template(KEY_YEAR), value)

    /** 月（紧凑，`shortMonthSymbols`）：zh `9月` / en `Sep`。 */
    fun monthName(date: LocalDate): String = date.month.getDisplayName(TextStyle.SHORT, locale)

    /** 月（大标题，`MMMM`）：zh `九月` / en `September`。 */
    fun monthFull(date: LocalDate): String = date.month.getDisplayName(TextStyle.FULL, locale)

    /** 星期（短）：zh `周二` / en `Tue`。 */
    fun weekdayShort(date: LocalDate): String = date.dayOfWeek.getDisplayName(TextStyle.SHORT, locale)

    /** 年 + 月（`date_month_title`）：zh `2026年 九月` / en `September 2026`。 */
    fun monthTitle(value: Int, date: LocalDate): String =
        format(template(KEY_MONTH_TITLE), value, monthFull(date))

    /** 月 + 日（`date_day_only`）：zh `9月15日` / en `Sep 15`。 */
    fun dateOnly(date: LocalDate): String =
        format(template(KEY_DAY_ONLY), monthName(date), date.dayOfMonth)

    /** 月 + 日 + 星期（`date_day_title`）：zh `9月15日 周二` / en `Tue, Sep 15`。 */
    fun dayTitle(date: LocalDate): String =
        format(template(KEY_DAY_TITLE), monthName(date), date.dayOfMonth, weekdayShort(date))

    /** 年 + 月 + 日 + 星期（`index_week_head`）：zh `2026年9月15日 周二` / en `Tue, Sep 15, 2026`。 */
    fun weekHeaderTitle(date: LocalDate): String =
        format(template(KEY_WEEK_HEAD), date.year, monthName(date), date.dayOfMonth, weekdayShort(date))

    /** 区间（`date_range`）：两侧都是"月 + 日"，中间是 en dash。 */
    fun dateRange(from: LocalDate, to: LocalDate): String =
        format(template(KEY_RANGE), dateOnly(from), dateOnly(to))

    // ------------------------------------------------------------------ 时刻

    /**
     * 时刻：24 小时制跟随**设备**，AM/PM 符号跟随**应用语言**。
     * 小时不补零（与 iOS 的 `Hmm` / `hmm` 模板一致）。
     */
    fun timeOf(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String {
        val dateTime = instant.atZone(zone)
        return timeOf(dateTime.hour, dateTime.minute)
    }

    /** 只给小时与分钟（提醒时间、编辑器起始行等）。 */
    fun timeOf(hour: Int, minute: Int): String = if (uses24Hour) {
        String.format(locale, "%d:%02d", hour, minute)
    } else {
        val hour12 = if (hour % 12 == 0) 12 else hour % 12
        val amPm = if (hour < 12) amSymbol() else pmSymbol()
        String.format(locale, "%d:%02d %s", hour12, minute, amPm)
    }

    /**
     * "新一天的开始时间"轮盘的文案。档位区间与 iOS `L10n.dayStartLabel` 逐字对齐
     * （0 / 1–5 / 6–11 / 12 / 13–17 / 18–23）。
     *
     * **与 iOS 的差异（第 4 处有意修正）**：iOS 把原始 0–23 的小时直接塞进这些模板，
     * 而模板本身是 12 小时制的（`下午 %1$ld:00`、`%1$ld:00 PM`），于是 13 点会显示成
     * "下午 13:00"（中文）/"13:00 PM"（英文）。这里先把小时归一到 12 小时制再套模板 ——
     * 档位**区间**仍按原始小时判定。
     */
    fun dayStartLabel(hour: Int): String {
        val display = if (hour % 12 == 0) 12 else hour % 12
        return when (hour) {
            0 -> template(KEY_MIDNIGHT)
            in 1..5 -> format(template(KEY_DAWN), display)
            in 6..11 -> format(template(KEY_MORNING), display)
            12 -> template(KEY_NOON)
            in 13..17 -> format(template(KEY_AFTERNOON), display)
            else -> format(template(KEY_EVENING), display)
        }
    }

    // ------------------------------------------------------------------ 相对日与起始行

    /** 今天 / 昨天 / 明天 / N 天前 / N 天后。 */
    fun relativeDayLabel(date: LocalDate, today: LocalDate): String {
        val diff = DateUtil.relativeDays(date, today)
        return when {
            diff == 0L -> template(KEY_TODAY)
            diff == -1L -> template(KEY_YESTERDAY)
            diff == 1L -> template(KEY_TOMORROW)
            diff < 0L -> format(template(KEY_DAYS_AGO), -diff)
            else -> format(template(KEY_DAYS_LATER), diff)
        }
    }

    /** 阅读页标题下的"于某时某地开始写"。 */
    fun startLine(time: String, locText: String): String = if (locText.isEmpty()) {
        format(template(KEY_START_TIME), time)
    } else {
        format(template(KEY_START_TIME_LOC), time, locText)
    }

    // ------------------------------------------------------------------ 内部

    private fun amSymbol(): String = meridiemSymbol(hour = 9)

    private fun pmSymbol(): String = meridiemSymbol(hour = 15)

    private fun meridiemSymbol(hour: Int): String {
        val reference = LocalDate.of(2000, 1, 1).atTime(hour, 0).atZone(ZoneId.of("UTC"))
        return DateTimeFormatter.ofPattern("a", locale).format(reference)
    }

    private fun format(pattern: String, vararg args: Any): String = try {
        String.format(locale, pattern, *args)
    } catch (_: RuntimeException) {
        // 模板与参数不匹配时不崩，返回模板本身便于定位（资源是唯一真源，出错必然是资源写错了）
        pattern
    }

    companion object {
        // 与 iOS String Catalog 同名的 key
        const val KEY_YEAR = "date_year"
        const val KEY_MONTH_TITLE = "date_month_title"
        const val KEY_DAY_ONLY = "date_day_only"
        const val KEY_DAY_TITLE = "date_day_title"
        const val KEY_RANGE = "date_range"
        const val KEY_WEEK_HEAD = "index_week_head"
        const val KEY_TODAY = "index_today"
        const val KEY_YESTERDAY = "index_yesterday"
        const val KEY_TOMORROW = "index_tomorrow"
        const val KEY_DAYS_AGO = "index_days_ago"
        const val KEY_DAYS_LATER = "index_days_later"
        const val KEY_START_TIME = "read_start_time"
        const val KEY_START_TIME_LOC = "read_start_time_loc"
        const val KEY_MIDNIGHT = "time_label_midnight"
        const val KEY_DAWN = "time_label_dawn"
        const val KEY_MORNING = "time_label_morning"
        const val KEY_NOON = "time_label_noon"
        const val KEY_AFTERNOON = "time_label_afternoon"
        const val KEY_EVENING = "time_label_evening"

        /** key → 资源 id。显式映射而不是 `getIdentifier`，这样改名会在编译期被发现。 */
        val RESOURCE_IDS: Map<String, Int> = mapOf(
            KEY_YEAR to R.string.date_year,
            KEY_MONTH_TITLE to R.string.date_month_title,
            KEY_DAY_ONLY to R.string.date_day_only,
            KEY_DAY_TITLE to R.string.date_day_title,
            KEY_RANGE to R.string.date_range,
            KEY_WEEK_HEAD to R.string.index_week_head,
            KEY_TODAY to R.string.index_today,
            KEY_YESTERDAY to R.string.index_yesterday,
            KEY_TOMORROW to R.string.index_tomorrow,
            KEY_DAYS_AGO to R.string.index_days_ago,
            KEY_DAYS_LATER to R.string.index_days_later,
            KEY_START_TIME to R.string.read_start_time,
            KEY_START_TIME_LOC to R.string.read_start_time_loc,
            KEY_MIDNIGHT to R.string.time_label_midnight,
            KEY_DAWN to R.string.time_label_dawn,
            KEY_MORNING to R.string.time_label_morning,
            KEY_NOON to R.string.time_label_noon,
            KEY_AFTERNOON to R.string.time_label_afternoon,
            KEY_EVENING to R.string.time_label_evening,
        )

        /** 应用内语言 → 格式化用的 [Locale]（iOS `zh-Hans` ↔ Android `zh-CN`）。 */
        fun localeFor(appLanguage: String, systemLocale: Locale = Locale.getDefault()): Locale =
            Locale.forLanguageTag(AppLanguage.resolve(appLanguage, systemLocale.toLanguageTag()))

        /**
         * Android 侧装配：小时制取**设备设置**，模板取**应用内语言**的资源。
         *
         * `AppCompatDelegate.setApplicationLocales` 生效后 `context.getString` 会按应用内
         * 语言解析，所以这里不需要手动查表。
         */
        fun forContext(context: Context, appLanguage: String): DateFormats {
            val uses24Hour = AndroidDateFormat.is24HourFormat(context)
            val locale = localeFor(appLanguage)
            return DateFormats(
                locale = locale,
                uses24Hour = uses24Hour,
                template = { key ->
                    val id = RESOURCE_IDS[key]
                    if (id == null) key else context.getString(id)
                },
            )
        }

        /** 星期名按设置的周起始排列（0 = 该周第一天）。 */
        fun weekdayNames(locale: Locale, weekStart: String): List<String> {
            val anchor = LocalDate.of(2026, 9, 14) // 周一
            val first = DateUtil.weekFirst(anchor, weekStart)
            return (0..6).map { offset ->
                first.plusDays(offset.toLong()).dayOfWeek.getDisplayName(TextStyle.SHORT, locale)
            }
        }
    }
}
