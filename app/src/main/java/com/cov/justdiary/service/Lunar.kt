package com.cov.justdiary.service

import android.icu.util.ChineseCalendar
import java.time.LocalDate
import java.time.ZoneId

/**
 * 农历。移植自 iOS `Models/Lunar.swift`（45 行）。
 *
 * ## 与 iOS 的对应
 *
 * iOS 用 `Calendar(identifier: .chinese)`（Foundation 内置中国农历）；
 * Android 用 **`android.icu.util.ChineseCalendar`**（平台 API，API 24+）—— 同样是 ICU 的
 * 中国农历实现，字段语义一致：
 * `EXTENDED_YEAR`（干支纪年用的年号）/ `MONTH`（0 起）/ `DAY_OF_MONTH` / `IS_LEAP_MONTH`。
 *
 * ## 可测性
 *
 * 名称表、干支、生肖都是**纯逻辑**，因此单独成 [Lunar] 并可在 JVM 上断言；
 * 只有"公历 → 农历"的换算需要平台日历，通过 [LunarCalendarProvider] 注入
 * （测试里可以换成固定值，真机上用 [ChineseCalendarProvider]）。
 */
object Lunar {

    /** 初一 … 三十。 */
    val DAY_NAMES: List<String> = listOf(
        "初一", "初二", "初三", "初四", "初五", "初六", "初七", "初八", "初九", "初十",
        "十一", "十二", "十三", "十四", "十五", "十六", "十七", "十八", "十九", "二十",
        "廿一", "廿二", "廿三", "廿四", "廿五", "廿六", "廿七", "廿八", "廿九", "三十",
    )

    /** 正月 … 腊月（下标 0..11）。 */
    val MONTH_NAMES: List<String> = listOf(
        "正月", "二月", "三月", "四月", "五月", "六月",
        "七月", "八月", "九月", "十月", "冬月", "腊月",
    )

    val STEMS: List<String> = listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸")

    val BRANCHES: List<String> = listOf("子", "丑", "寅", "卯", "辰", "巳", "午", "未", "申", "酉", "戌", "亥")

    val ANIMALS: List<String> = listOf("鼠", "牛", "虎", "兔", "龙", "蛇", "马", "羊", "猴", "鸡", "狗", "猪")

    /**
     * 农历日期的一次快照。
     *
     * ⚠️ [cycleYear] 是**六十甲子内的循环年（1..60）**，不是公历年 —— Foundation 与 ICU 的
     * 农历日历 `.year` 字段都是这个语义。`sexagenary` 的 `(y-1) % 10 / % 12` 公式依赖它：
     * 循环年 43 才是"丙午"（对应公历 2026），若误传 2026 会算出"己酉"。
     */
    data class LunarDate(
        val cycleYear: Int,
        val month: Int,
        val day: Int,
        val isLeapMonth: Boolean,
    )

    /**
     * 干支纪年：`stems[(y-1) % 10] + branches[(y-1) % 12]`（与 iOS `sexagenary` 一致）。
     */
    fun sexagenary(cycleYear: Int): String {
        val year = if (cycleYear <= 0) 1 else cycleYear
        return STEMS[(year - 1) % 10] + BRANCHES[(year - 1) % 12]
    }

    /** 生肖：`animals[(y-1) % 12]`。 */
    fun zodiac(cycleYear: Int): String {
        val year = if (cycleYear <= 0) 1 else cycleYear
        return ANIMALS[(year - 1) % 12]
    }

    /** 月格里的农历行：初一时显示月名（闰月加"闰"），其余显示日名。 */
    fun dayLabel(date: LunarDate): String = if (date.day == 1) {
        monthLabel(date)
    } else {
        DAY_NAMES.getOrElse(date.day - 1) { "" }
    }

    fun monthLabel(date: LunarDate): String {
        val name = MONTH_NAMES.getOrElse(date.month - 1) { "" }
        return if (date.isLeapMonth) "闰$name" else name
    }

    /** 完整标签：干支年 + 闰 + 月 + 日。 */
    fun fullLabel(date: LunarDate): String =
        sexagenary(date.cycleYear) + "年" + monthLabel(date) + DAY_NAMES.getOrElse(date.day - 1) { "" }

    /** 年历标题右侧的"丙午马年"。 */
    fun yearZodiacLabel(cycleYear: Int): String = sexagenary(cycleYear) + zodiac(cycleYear) + "年"

    /**
     * 公历 → 农历。测试可注入固定值，避免依赖平台日历。
     */
    fun interface LunarCalendarProvider {
        fun lunarDateOf(date: LocalDate, zone: ZoneId): LunarDate
    }

    /** 真机实现：`android.icu.util.ChineseCalendar`。 */
    object ChineseCalendarProvider : LunarCalendarProvider {
        override fun lunarDateOf(date: LocalDate, zone: ZoneId): LunarDate {
            val calendar = ChineseCalendar(android.icu.util.TimeZone.getTimeZone(zone.id))
            // 用绝对时间设置（而不是 set(y, m, d)）：ChineseCalendar 的 YEAR/MONTH/DATE
            // 是**农历**字段，直接 set 会被当成农历日期解释。
            calendar.timeInMillis = date.atStartOfDay(zone).toInstant().toEpochMilli()

            // ICU 的 YEAR 是六十甲子内的循环年（1..60），与 Foundation 的 .year 同义；
            // EXTENDED_YEAR 才是公历年号。
            val cycleYear = calendar.get(ChineseCalendar.YEAR)
            val month = calendar.get(ChineseCalendar.MONTH) + 1 // ICU 的 MONTH 从 0 开始
            val day = calendar.get(ChineseCalendar.DAY_OF_MONTH)
            val isLeap = calendar.get(ChineseCalendar.IS_LEAP_MONTH) == 1
            return LunarDate(cycleYear = cycleYear, month = month, day = day, isLeapMonth = isLeap)
        }
    }

    /** 便捷入口：用平台日历取某天的农历日名（月格绘制用）。 */
    fun dayLabelOf(
        date: LocalDate,
        zone: ZoneId = ZoneId.systemDefault(),
        provider: LunarCalendarProvider = ChineseCalendarProvider,
    ): String = dayLabel(provider.lunarDateOf(date, zone))

    /**
     * [dayLabelOf] 的缓存版 —— **绘制路径请一律用它**。
     *
     * `ChineseCalendar` 每次换算都要新建对象 + 读四个字段（农历月/闰月是天文推算），
     * 是这几屏里单次最贵的调用；而结果只跟日期有关，与选中/主题/动画进度都无关。
     * 未缓存时：月视图点一次日期要让 60~70 个格子各算一次（≈3~8ms），
     * 年↔月 morph 每帧 30~60 次。
     *
     * 农历只跟**时区**有关（与语言无关），所以按 `dayOfEpoch` 缓存即可；
     * 换时区时调用 [clearCache]。
     */
    private val dayLabelCache = object : LinkedHashMap<Long, String>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, String>) = size > 1024
    }

    fun dayLabelCached(date: LocalDate): String {
        val key = date.toEpochDay()
        dayLabelCache[key]?.let { return it }
        return dayLabelOf(date).also { dayLabelCache[key] = it }
    }

    /** 时区变化后清空 [dayLabelCached] 的缓存。 */
    fun clearCache() = dayLabelCache.clear()
}
