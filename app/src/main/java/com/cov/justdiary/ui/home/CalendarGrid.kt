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
        // ⚠️ 缓存满了要**清空重来**，不能"满了就不再写入" —— 后者会让后续所有月份都
        // 缓存不命中，`weeks()` 的"同一月份返回同一实例"这条约定就断了
        // （单测 `weeks cache returns the same instance for the same month` 正是钉这条；
        //   它是被 `MonthFlowLayoutTest` 遍历大量月份后才暴露出来的）。
        if (weeksCache.size >= MAX_WEEKS_CACHE) weeksCache.clear()
        weeksCache[cacheKey] = rows
        return rows
    }

    /**
     * 选中日所在的那一周（7 天，可能跨月）。
     *
     * 周视图的周条用它 —— iOS 对应 `CalendarLayout.weekKey(selectedDate)`。
     * ⚠️ **不能**改成"在当前月 `weeks()` 里找选中日、找不到就退回第一周"：
     * 连续月历流里点到露出来的下个月那几天时，当前月的周列表里根本没有这一天，
     * 于是会退回本月第一周，表现为"动画正确、动画结束后周条突然跳到另一周"。
     */
    fun weekOf(selectedKey: String, weekStart: String): List<LocalDate> {
        val day = DateUtil.parseDayKey(selectedKey) ?: return emptyList()
        val first = DateUtil.weekFirst(day, weekStart)
        return (0 until 7).map { first.plusDays(it.toLong()) }
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

    /**
     * [displayedWeeks] 的**行数**，O(1) 算出来。
     *
     * 连续月历流（[MonthFlowLayout]）要为 1900–2100 共 2412 个月建前缀偏移，
     * 每个月都去展开一次周数组（6×7 次判断）会拖慢首次布局；行数只取决于
     * 「1 号是星期几 + 这个月几天」：`ceil((前导天数 + 当月天数) / 7)`，
     * 而最后一格一定含当月的一天，所以不会有需要裁掉的空尾行 —— 与
     * [displayedWeeks] 等价（由 `MonthFlowLayoutTest` 在 2412 个月上守住）。
     */
    fun displayedWeekCount(monthFirst: LocalDate, weekStart: String): Int {
        val lead = DateUtil.weekdayIndex(monthFirst, weekStart)
        val days = monthFirst.lengthOfMonth()
        return maxOf(1, ((lead + days + 6) / 7))
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
