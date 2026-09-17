package com.cov.justdiary.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 日期语义测试。全部使用**显式时区**，因此与运行机器的时区无关。
 *
 * 重点是"日记日"规则（`dayKeyForUtc`）—— 它决定一条日记落在哪一天，
 * 是跨端数据契约的一部分（iOS `DateUtil.swift:51-59`）。
 */
class DateUtilTest {

    private val shanghai: ZoneId = ZoneId.of("Asia/Shanghai")

    @Test
    fun `day key is zero padded iso`() {
        assertEquals("2026-09-15", DateUtil.dayKeyOf(LocalDate.of(2026, 9, 15)))
        assertEquals("2026-01-05", DateUtil.dayKeyOf(LocalDate.of(2026, 1, 5)))
        assertEquals("0999-12-31", DateUtil.dayKeyOf(LocalDate.of(999, 12, 31)))
    }

    @Test
    fun `day key of instant uses the given zone`() {
        // 2026-09-15T16:30:00Z = 2026-09-16 00:30（上海）
        val instant = Instant.parse("2026-09-15T16:30:00Z")
        assertEquals("2026-09-16", DateUtil.dayKeyOf(instant, shanghai))
        assertEquals("2026-09-15", DateUtil.dayKeyOf(instant, ZoneId.of("UTC")))
    }

    @Test
    fun `hours before the day start hour belong to the previous day`() {
        // 本地 02:00（dayStartHour = 4）→ 前一天
        val beforeStart = Instant.parse("2026-09-15T18:00:00Z") // 上海 2026-09-16 02:00
        assertEquals("2026-09-15", DateUtil.dayKeyForUtc(beforeStart.toEpochMilli(), 4, shanghai))

        // 本地 05:00 → 当天
        val afterStart = Instant.parse("2026-09-15T21:00:00Z") // 上海 2026-09-16 05:00
        assertEquals("2026-09-16", DateUtil.dayKeyForUtc(afterStart.toEpochMilli(), 4, shanghai))
    }

    @Test
    fun `day start hour zero never shifts backwards`() {
        val justAfterMidnight = Instant.parse("2026-09-15T16:00:00Z") // 上海 2026-09-16 00:00
        assertEquals("2026-09-16", DateUtil.dayKeyForUtc(justAfterMidnight.toEpochMilli(), 0, shanghai))
    }

    @Test
    fun `day start hour at the boundary is not shifted`() {
        val exactlyFour = Instant.parse("2026-09-15T20:00:00Z") // 上海 2026-09-16 04:00
        assertEquals("2026-09-16", DateUtil.dayKeyForUtc(exactlyFour.toEpochMilli(), 4, shanghai))
    }

    @Test
    fun `month boundary shifts into the previous month`() {
        // 上海 2026-10-01 01:00，dayStartHour = 4 → 2026-09-30
        val instant = Instant.parse("2026-09-30T17:00:00Z")
        assertEquals("2026-09-30", DateUtil.dayKeyForUtc(instant.toEpochMilli(), 4, shanghai))
    }

    @Test
    fun `parse day key accepts only valid keys`() {
        assertEquals(LocalDate.of(2026, 9, 15), DateUtil.parseDayKey("2026-09-15"))
        assertNull(DateUtil.parseDayKey("2026-9"))
        assertNull(DateUtil.parseDayKey("2026-13-01"))
        assertNull(DateUtil.parseDayKey("not-a-date"))
        assertNull(DateUtil.parseDayKey(""))
    }

    @Test
    fun `week first follows the configured week start`() {
        // 2026-09-15 是周二
        val tuesday = LocalDate.of(2026, 9, 15)
        assertEquals(LocalDate.of(2026, 9, 14), DateUtil.weekFirst(tuesday, DateUtil.WEEK_START_MONDAY))
        assertEquals(LocalDate.of(2026, 9, 13), DateUtil.weekFirst(tuesday, DateUtil.WEEK_START_SUNDAY))
        // 一周的中间四天都回到同一个起点
        assertEquals(LocalDate.of(2026, 9, 14), DateUtil.weekFirst(LocalDate.of(2026, 9, 20), DateUtil.WEEK_START_MONDAY))
    }

    @Test
    fun `weekday index matches the ios formulas`() {
        val monday = LocalDate.of(2026, 9, 14)
        val sunday = LocalDate.of(2026, 9, 13)
        val saturday = LocalDate.of(2026, 9, 19)

        assertEquals(0, DateUtil.weekdayIndex(monday, DateUtil.WEEK_START_MONDAY))
        assertEquals(6, DateUtil.weekdayIndex(sunday, DateUtil.WEEK_START_MONDAY))
        assertEquals(5, DateUtil.weekdayIndex(saturday, DateUtil.WEEK_START_MONDAY))

        assertEquals(1, DateUtil.weekdayIndex(monday, DateUtil.WEEK_START_SUNDAY))
        assertEquals(0, DateUtil.weekdayIndex(sunday, DateUtil.WEEK_START_SUNDAY))
        assertEquals(6, DateUtil.weekdayIndex(saturday, DateUtil.WEEK_START_SUNDAY))
    }

    @Test
    fun `relative days is signed with today as zero`() {
        val today = LocalDate.of(2026, 9, 15)
        assertEquals(0L, DateUtil.relativeDays(today, today))
        assertEquals(-1L, DateUtil.relativeDays(today.minusDays(1), today))
        assertEquals(1L, DateUtil.relativeDays(today.plusDays(1), today))
        assertEquals(3L, DateUtil.relativeDays(today.plusDays(3), today))
    }

    @Test
    fun `month first and add days`() {
        assertEquals(LocalDate.of(2026, 9, 1), DateUtil.monthFirst(LocalDate.of(2026, 9, 15)))
        assertEquals(LocalDate.of(2026, 10, 1), DateUtil.addDays(LocalDate.of(2026, 9, 30), 1))
        assertEquals(LocalDate.of(2025, 12, 31), DateUtil.addDays(LocalDate.of(2026, 1, 1), -1))
    }

    @Test
    fun `reference time is on two thousand january first`() {
        assertEquals(4, DateUtil.referenceTime(4, 0).hour)
        assertEquals(0, DateUtil.referenceTime(4, 0).minute)
    }

    @Test
    fun `hour and minute come from the zone`() {
        val instant = Instant.parse("2026-09-15T16:30:00Z")
        assertEquals(0, DateUtil.hourOf(instant, shanghai))
        assertEquals(30, DateUtil.minuteOf(instant, shanghai))
        assertEquals(16, DateUtil.hourOf(instant, ZoneId.of("UTC")))
    }

    @Test
    fun `start of day is local midnight`() {
        val instant = Instant.parse("2026-09-15T16:30:00Z")
        assertEquals(
            Instant.parse("2026-09-15T16:00:00Z"),
            DateUtil.startOfDay(instant, shanghai),
        )
    }
}
