package com.cov.justdiary.ui.home

import com.cov.justdiary.model.DateUtil
import java.time.LocalDate
import java.util.Collections

/**
 * 日历的网格与键表生成。移植自 iOS `CalendarLayout.swift:246-368`。
 *
 * 同样是**纯逻辑**（不依赖 Compose），因此可以在 JVM 上断言：
 * 固定 6 行、尾部空行裁剪、以及 2412 / 201 / 周键的数量与首尾值。
 *
 * 与 iOS 一致的两个细节：
 *  - `weeks` 固定 6 行，且结果按 `(year*100+month)*2 + (sunday?1:0)` 缓存；
 *  - `displayedWeeks` 从尾部删除"7 天全不属于本月"的行，**至少保留 1 行** ——
 *    这是横屏"只剩一行日期"那个 bug 的修复。
 */
object CalendarGrid {

    /** 可选月份键的范围：1900-01 … 2100-12。 */
    const val MIN_YEAR = 1900
    const val MAX_YEAR = 2100

    /** 月份键 = `year * 100 + month`（与 iOS 一致）。 */
    fun monthKey(year: Int, month: Int): Int = year * 100 + month

    fun yearOfMonthKey(key: Int): Int = key / 100

    fun monthOfMonthKey(key: Int): Int = key % 100

    fun monthFirstOfMonthKey(key: Int): LocalDate = LocalDate.of(yearOfMonthKey(key), monthOfMonthKey(key), 1)

    fun monthKeyOf(date: LocalDate): Int = monthKey(date.year, date.monthValue)

    /** 全部月份键：**2412** 个（201 年 × 12 月）。 */
    val MONTH_KEYS: List<Int> = buildList {
        for (year in MIN_YEAR..MAX_YEAR) {
            for (month in 1..12) add(monthKey(year, month))
        }
    }

    /** 全部年份：**201** 个。 */
    val YEARS: List<Int> = (MIN_YEAR..MAX_YEAR).toList()

    /**
     * 当月网格：**固定 6 行 × 7 天**，第一行从"含当月 1 日的那一周的第一天"开始。
     */
    fun weeks(monthFirst: LocalDate, weekStart: String): List<List<LocalDate>> {
        val cacheKey = monthKeyOf(monthFirst) * 2 + if (weekStart == DateUtil.WEEK_START_SUNDAY) 1 else 0
        weeksCache[cacheKey]?.let { return it }

        val start = DateUtil.weekFirst(monthFirst, weekStart)
        val rows = ArrayList<List<LocalDate>>(CalendarLayout.MONTH_ROWS)
        for (row in 0 until CalendarLayout.MONTH_ROWS) {
            val rowStart = start.plusDays(row * 7L)
            rows.add((0 until 7).map { rowStart.plusDays(it.toLong()) })
        }
        if (weeksCache.size < MAX_WEEKS_CACHE) weeksCache[cacheKey] = rows
        return rows
    }

    /**
     * 会实际绘制的行：从尾部删除"整行都不属于当月"的行，至少保留 1 行。
     *
     * 横屏高度不足时，这一裁剪决定了要画几行（进而决定格子高度）。
     */
    fun displayedWeeks(monthFirst: LocalDate, weekStart: String): List<List<LocalDate>> {
        val rows = weeks(monthFirst, weekStart).toMutableList()
        while (rows.size > 1) {
            val last = rows.last()
            val anyInMonth = last.any { it.year == monthFirst.year && it.monthValue == monthFirst.monthValue }
            if (anyInMonth) break
            rows.removeAt(rows.size - 1)
        }
        return rows
    }

    /** 含该日期的那一周的第一天（周条的键）。 */
    fun weekKeyOf(date: LocalDate, weekStart: String): String =
        DateUtil.dayKeyOf(DateUtil.weekFirst(date, weekStart))

    /**
     * 全部周键：从含 1900-01-01 的那一周起，每 7 天一个，直到 2101-01-01（含）。
     *
     * 与 iOS 一样按 `weekStart` 分别缓存（`CalendarLayout.swift:287-307`）。
     */
    fun weekKeys(weekStart: String): List<String> {
        weekKeysCache[weekStart]?.let { return it }

        val end = LocalDate.of(MAX_YEAR + 1, 1, 1)
        var cursor = DateUtil.weekFirst(LocalDate.of(MIN_YEAR, 1, 1), weekStart)
        val keys = ArrayList<String>(WEEK_KEYS_COUNT)
        while (!cursor.isAfter(end)) {
            keys.add(DateUtil.dayKeyOf(cursor))
            cursor = cursor.plusDays(7)
        }
        weekKeysCache[weekStart] = keys
        return keys
    }

    /** 周键表里该周的下标（找不到返回 -1）。 */
    fun weekIndexForKey(weekKeys: List<String>, key: String): Int = weekKeys.binarySearch(key)

    /** 该日期所在周在 [weekKeys] 里的下标。 */
    fun weekIndexOf(date: LocalDate, weekStart: String): Int =
        weekIndexForKey(weekKeys(weekStart), weekKeyOf(date, weekStart))

    /** 某一天在该周里的列（0..6）。 */
    fun columnOf(date: LocalDate, weekStart: String): Int = DateUtil.weekdayIndex(date, weekStart)

    /** 1900-01-01..2101-01-01 之间的周数（两个 weekStart 取值下都是 10488）。 */
    const val WEEK_KEYS_COUNT: Int = 10488

    private const val MAX_WEEKS_CACHE = 64

    private val weeksCache: MutableMap<Int, List<List<LocalDate>>> =
        Collections.synchronizedMap(LinkedHashMap())

    private val weekKeysCache: MutableMap<String, List<String>> =
        Collections.synchronizedMap(HashMap())
}
