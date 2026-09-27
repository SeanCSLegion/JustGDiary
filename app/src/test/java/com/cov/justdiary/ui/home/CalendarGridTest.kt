package com.cov.justdiary.ui.home

import com.cov.justdiary.model.DateUtil
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * 日历网格与键表测试。期望值来自 iOS `CalendarLayout.swift:246-368` 的定义：
 * 固定 6 行、尾部裁剪至少留 1 行、2412 个月份键、201 个年份、10488 个周键。
 */
class CalendarGridTest {

    private val monday = DateUtil.WEEK_START_MONDAY
    private val sunday = DateUtil.WEEK_START_SUNDAY

    @Test
    fun `month keys cover 201 years and 12 months`() {
        assertEquals(2412, CalendarGrid.MONTH_KEYS.size)
        assertEquals(190001, CalendarGrid.MONTH_KEYS.first())
        assertEquals(210012, CalendarGrid.MONTH_KEYS.last())
        assertEquals(1900, CalendarGrid.yearOfMonthKey(190001))
        assertEquals(1, CalendarGrid.monthOfMonthKey(190001))
        assertEquals(LocalDate.of(2026, 9, 1), CalendarGrid.monthFirstOfMonthKey(202609))
    }

    @Test
    fun `year list has 201 entries`() {
        assertEquals(201, CalendarGrid.YEARS.size)
        assertEquals(1900, CalendarGrid.YEARS.first())
        assertEquals(2100, CalendarGrid.YEARS.last())
    }

    @Test
    fun `weeks always produce six rows starting from the configured week start`() {
        val september = LocalDate.of(2026, 9, 1)
        val weeks = CalendarGrid.weeks(september, monday)

        assertEquals(6, weeks.size)
        weeks.forEach { assertEquals(7, it.size) }
        // 2026-09-01 是周二 → 周一起始的那一周从 2026-08-31 开始
        assertEquals(LocalDate.of(2026, 8, 31), weeks[0][0])
        assertEquals(LocalDate.of(2026, 9, 6), weeks[0][6])
        assertEquals(LocalDate.of(2026, 9, 7), weeks[1][0])

        // 周日起始时同一周从 2026-08-30 开始
        assertEquals(LocalDate.of(2026, 8, 30), CalendarGrid.weeks(september, sunday)[0][0])
    }

    @Test
    fun `displayed weeks trim trailing rows that hold no day of the month`() {
        // 2026-09-01 是周二，最后一行（10/05..10/11）全属 10 月 → 裁掉
        val september = CalendarGrid.displayedWeeks(LocalDate.of(2026, 9, 1), monday)
        assertEquals(5, september.size)
        assertTrue(
            "裁剪后最后一行必须含当月日期",
            september.last().any { it.monthValue == 9 },
        )

        // 2026-08-01 是周六，最后一行含 08/31 → 保留 6 行
        assertEquals(6, CalendarGrid.displayedWeeks(LocalDate.of(2026, 8, 1), monday).size)
    }

    @Test
    fun `displayed weeks always keep at least one row`() {
        // 2026-02 是最短月（28 天）；构造一个"整月只占一行"的极端也不应裁到 0 行
        val february = CalendarGrid.displayedWeeks(LocalDate.of(2026, 2, 1), monday)
        assertTrue("至少保留一行", february.isNotEmpty())
        assertTrue(february.size <= 6)
    }

    @Test
    fun `week keys start at the week containing 1900-01-01`() {
        val mondayKeys = CalendarGrid.weekKeys(monday)
        assertEquals(CalendarGrid.WEEK_KEYS_COUNT, mondayKeys.size)
        assertEquals("1900-01-01", mondayKeys.first())
        // 每 7 天一个键
        assertEquals("1900-01-08", mondayKeys[1])

        val sundayKeys = CalendarGrid.weekKeys(sunday)
        assertEquals(CalendarGrid.WEEK_KEYS_COUNT, sundayKeys.size)
        // 周日起始时，含 1900-01-01 的那一周从 1899-12-31 开始
        assertEquals("1899-12-31", sundayKeys.first())
    }

    @Test
    fun `week key and index helpers agree with the week list`() {
        val date = LocalDate.of(2026, 9, 15) // 周二
        val key = CalendarGrid.weekKeyOf(date, monday)
        assertEquals("2026-09-14", key)

        val keys = CalendarGrid.weekKeys(monday)
        val index = CalendarGrid.weekIndexOf(date, monday)
        assertTrue("周键必须能在表里找到", index >= 0)
        assertEquals(key, keys[index])
        assertEquals(1, CalendarGrid.columnOf(date, monday))
        assertEquals(2, CalendarGrid.columnOf(date, sunday))
    }

    /**
     * 周条那一周必须**由选中日推**（iOS `weekKey(selectedDate)`）：
     * 点到露出来的下个月那几天时，当前月的 `weeks()` 里没有这一天 ——
     * 旧写法会退回本月第一周，表现为"动画结束后周条跳到另一周"。
     */
    @Test
    fun `week of a selected day follows the day even across months`() {
        // 2026-10-01 是周四（周一为一周之始 → 这一周是 9/28–10/4，跨月）
        val week = CalendarGrid.weekOf("2026-10-01", monday)
        assertEquals(7, week.size)
        assertEquals(LocalDate.of(2026, 9, 28), week.first())
        assertEquals(LocalDate.of(2026, 10, 4), week.last())
        assertTrue("这一周要包含选中日", week.any { DateUtil.dayKeyOf(it) == "2026-10-01" })
        // 真正会出错的场景：**顶部大标题的月份 ≠ 点中那天所在的月**。
        // 连续流可以滚到大标题已是 10 月、而屏幕上还露着 9 月下旬那几行，
        // 此时点 9/27：它不在 10 月的周窗口里（窗口从 9/28 开始），
        // 旧写法"在当月周列表里找、找不到退回第一周"就会把周条跳到 9/28 那一周。
        val octoberWeeks = CalendarGrid.displayedWeeks(LocalDate.of(2026, 10, 1), monday)
        assertTrue(
            "10 月的周窗口里没有 9/27（旧写法正是在这里退回第一周）",
            octoberWeeks.none { row -> row.any { DateUtil.dayKeyOf(it) == "2026-09-27" } },
        )
        val week927 = CalendarGrid.weekOf("2026-09-27", monday)
        assertEquals("9/27 属于 9/21 那一周", LocalDate.of(2026, 9, 21), week927.first())
        // 周日为一周之始也要对
        val sundayWeek = CalendarGrid.weekOf("2026-10-01", sunday)
        assertEquals(LocalDate.of(2026, 9, 27), sundayWeek.first())
        // 非法 key 不炸
        assertTrue(CalendarGrid.weekOf("", monday).isEmpty())
    }

    @Test
    fun `weeks cache returns the same instance for the same month`() {
        val monthFirst = LocalDate.of(2026, 9, 1)
        val first = CalendarGrid.weeks(monthFirst, monday)
        val second = CalendarGrid.weeks(monthFirst, monday)
        assertTrue("同一月份应命中缓存", first === second)
    }
}
