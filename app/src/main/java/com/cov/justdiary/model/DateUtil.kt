package com.cov.justdiary.model

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 日期工具，移植自 iOS `JustDiary/Models/DateUtil.swift`。
 *
 * ## 关键语义（跨端数据契约的一部分）
 *
 * - 所有 `*_utc` 字段是 **epoch 毫秒**；
 * - `day_key` 是**本地时区**下的 `YYYY-MM-DD`（零填充，字符串比较即时间序）；
 * - **"日记日"规则**：本地小时 `< dayStartHour`（默认 4）时，归属**前一天**；
 * - 库里**不存时区**，因此换时区后 `day_key` 需要按当前时区重算（见 `DiaryRepository.recomputeDayKeys`）。
 *
 * ## 与 iOS 的一处有意差异
 *
 * iOS 的 `DateUtil.calendar` 是 `static let`，**时区在首次访问时被钉死**（改时区要重启 App 才生效）。
 * 这里每次调用 `ZoneId.systemDefault()`，改时区立即生效 —— 行为更正确，且不影响同一时刻的
 * 计算结果（只在"会话中途改时区"这一场景下有差异）。
 *
 * ## 与 iOS 的一处结构调整
 *
 * iOS 的 `weekFirst` 直接读全局设置（`SettingsStore.load().weekStart`）。这里把 `weekStart`
 * 作为**显式参数**，保持纯函数、可单测；调用方从 `SettingsStore` 取值。
 */
object DateUtil {

    const val DAY_KEY_PATTERN: String = "%04d-%02d-%02d"

    /** `YYYY-MM-DD` 的本地日期；等价于 iOS `dayKeyOf`。 */
    fun dayKeyOf(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): String =
        dayKeyOf(instant.atZone(zone).toLocalDate())

    fun dayKeyOf(date: LocalDate): String =
        String.format(DAY_KEY_PATTERN, date.year, date.monthValue, date.dayOfMonth)

    /**
     * 按"日记日"规则把 UTC 毫秒换算成 `day_key`：本地小时 < [dayStartHour] 时算前一天。
     *
     * 与 iOS `dayKeyForUtc` 一致（iOS 用 `calendar.date(byAdding: .day, value: -1)`，
     * 这里用 `minusDays(1)`，两者在夏令时切换日都保持"本地墙上时钟减一天"的语义）。
     */
    fun dayKeyForUtc(
        utcMillis: Long,
        dayStartHour: Int,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        val dateTime = Instant.ofEpochMilli(utcMillis).atZone(zone)
        return if (dateTime.hour < dayStartHour) {
            dayKeyOf(dateTime.minusDays(1).toLocalDate())
        } else {
            dayKeyOf(dateTime.toLocalDate())
        }
    }

    /**
     * 解析 `YYYY-MM-DD`；格式非法返回 `null`（与 iOS 的 `parts.count == 3` 校验等价）。
     */
    fun parseDayKey(key: String): LocalDate? {
        val parts = key.split('-')
        if (parts.size != 3) return null
        val year = parts[0].toIntOrNull() ?: return null
        val month = parts[1].toIntOrNull() ?: return null
        val day = parts[2].toIntOrNull() ?: return null
        return runCatching { LocalDate.of(year, month, day) }.getOrNull()
    }

    /**
     * 时间控件用的"参考日"（2000-01-01 的 h:m）。
     *
     * iOS 侧因为 `DatePicker` 只认 `Date` 而库里只存时分才需要它；Android 的
     * `TimePicker` 直接吃 hour/minute，这个函数主要为对齐语义与测试而保留。
     */
    fun referenceTime(hour: Int, minute: Int): LocalDateTime =
        LocalDateTime.of(2000, 1, 1, hour, minute)

    fun hourOf(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): Int =
        instant.atZone(zone).hour

    fun minuteOf(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): Int =
        instant.atZone(zone).minute

    fun addDays(date: LocalDate, days: Long): LocalDate = date.plusDays(days)

    fun startOfDay(instant: Instant, zone: ZoneId = ZoneId.systemDefault()): Instant =
        instant.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()

    fun monthFirst(date: LocalDate): LocalDate = date.withDayOfMonth(1)

    /**
     * 该日期所在周的第一天，周起始由设置决定（`"monday"` / `"sunday"`）。
     */
    fun weekFirst(date: LocalDate, weekStart: String): LocalDate =
        date.minusDays(weekdayIndex(date, weekStart).toLong())

    /**
     * 0 = 该周的第一天。
     *
     * 与 iOS 的公式等价（iOS 用 `Calendar.component(.weekday)`，周日 = 1）：
     *  - `sunday` → `(wd - 1)` ⇒ 这里 `isoDayOfWeek % 7`
     *  - `monday` → `(wd + 5) % 7` ⇒ 这里 `isoDayOfWeek - 1`
     */
    fun weekdayIndex(date: LocalDate, weekStart: String): Int {
        val iso = date.dayOfWeek.value // 周一 = 1 … 周日 = 7
        return if (weekStart == WEEK_START_SUNDAY) iso % 7 else iso - 1
    }

    /**
     * 整天差：`date - today`（今天为 0，昨天为 -1）。
     *
     * 与 iOS `relativeDays(from:to:)` 的返回方向一致（iOS 用 `from: b, to: a`，即 a - b）。
     */
    fun relativeDays(
        from: LocalDate,
        to: LocalDate,
    ): Long = ChronoUnit.DAYS.between(to, from)

    /** 方便按序号取星期名（0 = 该周第一天）。 */
    fun dayOfWeekAt(date: LocalDate, indexInWeek: Int, weekStart: String): DayOfWeek {
        val first = weekFirst(date, weekStart)
        return first.plusDays(indexInWeek.toLong()).dayOfWeek
    }

    const val WEEK_START_MONDAY: String = "monday"
    const val WEEK_START_SUNDAY: String = "sunday"

    val ISO_DATE: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE
}
