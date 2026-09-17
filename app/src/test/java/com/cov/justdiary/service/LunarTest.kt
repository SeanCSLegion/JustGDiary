package com.cov.justdiary.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

/**
 * 农历测试。
 *
 * 名称表与干支/生肖是**纯逻辑**，逐条断言；"公历 → 农历"的换算依赖平台 ICU 日历，
 * 因此通过 [Lunar.LunarCalendarProvider] 注入固定值，让绘制逻辑也能在 JVM 上验证。
 * （`ChineseCalendarProvider` 的正确性只能在设备上验证，已记入实施记录。）
 */
class LunarTest {

    private val shanghai = ZoneId.of("Asia/Shanghai")

    @Test
    fun `name tables match the ios lunar tables`() {
        assertEquals(30, Lunar.DAY_NAMES.size)
        assertEquals("初一", Lunar.DAY_NAMES.first())
        assertEquals("十五", Lunar.DAY_NAMES[14])
        assertEquals("三十", Lunar.DAY_NAMES.last())

        assertEquals(12, Lunar.MONTH_NAMES.size)
        assertEquals("正月", Lunar.MONTH_NAMES.first())
        assertEquals("腊月", Lunar.MONTH_NAMES.last())

        assertEquals(listOf("甲", "乙", "丙", "丁", "戊", "己", "庚", "辛", "壬", "癸"), Lunar.STEMS)
        assertEquals(12, Lunar.BRANCHES.size)
        assertEquals(12, Lunar.ANIMALS.size)
        assertEquals("鼠", Lunar.ANIMALS.first())
        assertEquals("猪", Lunar.ANIMALS.last())
    }

    @Test
    fun `sexagenary cycle follows the ios formula`() {
        // ⚠️ 入参是**六十甲子内的循环年（1..60）**，不是公历年
        assertEquals("甲子", Lunar.sexagenary(1))
        assertEquals("乙丑", Lunar.sexagenary(2))

        // 公历 2026 = 循环年 43 = 丙午，对应首页年历标题右侧的"丙午马年"
        assertEquals("丙午", Lunar.sexagenary(43))
        assertEquals("马", Lunar.zodiac(43))
        assertEquals("丙午马年", Lunar.yearZodiacLabel(43))

        // 若误传公历年会算出"己酉" —— 这正是这个字段命名要提醒的坑
        assertEquals("己酉", Lunar.sexagenary(2026))

        // 边界：<= 0 时按 1 处理，不崩
        assertEquals(Lunar.sexagenary(1), Lunar.sexagenary(0))
    }

    @Test
    fun `cycle repeats every sixty years`() {
        for (year in listOf(1, 20, 43, 60)) {
            assertEquals(Lunar.sexagenary(year), Lunar.sexagenary(year + 60))
            assertEquals(Lunar.zodiac(year), Lunar.zodiac(year + 12))
        }
    }

    @Test
    fun `day label shows the month name on the first day`() {
        assertEquals("正月", Lunar.dayLabel(Lunar.LunarDate(43, 1, 1, false)))
        assertEquals("闰二月", Lunar.dayLabel(Lunar.LunarDate(43, 2, 1, true)))
        assertEquals("腊月", Lunar.dayLabel(Lunar.LunarDate(43, 12, 1, false)))
        assertEquals("初二", Lunar.dayLabel(Lunar.LunarDate(43, 1, 2, false)))
        assertEquals("十五", Lunar.dayLabel(Lunar.LunarDate(43, 1, 15, false)))
    }

    @Test
    fun `full label combines sexagenary month and day`() {
        assertEquals("丙午年正月初一", Lunar.fullLabel(Lunar.LunarDate(43, 1, 1, false)))
        assertEquals("丙午年腊月三十", Lunar.fullLabel(Lunar.LunarDate(43, 12, 30, false)))
    }

    @Test
    fun `out of range day falls back to an empty label instead of crashing`() {
        assertEquals("", Lunar.dayLabel(Lunar.LunarDate(43, 1, 31, false)))
        assertEquals("", Lunar.dayLabel(Lunar.LunarDate(43, 1, 0, false)))
    }

    @Test
    fun `day label of uses the injected provider`() {
        val provider = Lunar.LunarCalendarProvider { _, _ -> Lunar.LunarDate(43, 9, 15, false) }
        assertEquals("十五", Lunar.dayLabelOf(LocalDate.of(2026, 9, 15), shanghai, provider))
    }

    @Test
    fun `month canvas numbers are stable for the design month`() {
        // 设计文档里的示例：2026-09-15
        val provider = Lunar.LunarCalendarProvider { date, _ ->
            // 只是构造一个确定的农历值，验证组合逻辑
            Lunar.LunarDate(cycleYear = 43, month = 8, day = date.dayOfMonth, isLeapMonth = false)
        }
        val label = Lunar.dayLabelOf(LocalDate.of(2026, 9, 15), shanghai, provider)
        assertEquals("十五", label)
        assertTrue(label.isNotEmpty())
    }
}
