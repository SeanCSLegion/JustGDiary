package com.cov.justdiary.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 每日提醒排期测试。
 *
 * iOS 用一次性的 `UNCalendarNotificationTrigger` + `rearm()` 反复重排；
 * Android 用 `WorkManager` 一次性任务，投递后再排下一天。两条路径的**排期规则必须一致**：
 * 到点未写才提醒，写过就顺延。
 */
class ReminderScheduleTest {

    private val zone = ZoneId.of("Asia/Shanghai")

    @Test
    fun `before the target time today is the trigger`() {
        val now = LocalDateTime.of(2026, 9, 15, 20, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 15, 21, 0),
            ReminderSchedule.nextTrigger(now, hour = 21, minute = 0, skipIfTodayWritten = false),
        )
    }

    @Test
    fun `at or after the target time the trigger moves to tomorrow`() {
        val exactly = LocalDateTime.of(2026, 9, 15, 21, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 16, 21, 0),
            ReminderSchedule.nextTrigger(exactly, hour = 21, minute = 0, skipIfTodayWritten = false),
        )
        val later = LocalDateTime.of(2026, 9, 15, 22, 30)
        assertEquals(
            LocalDateTime.of(2026, 9, 16, 21, 0),
            ReminderSchedule.nextTrigger(later, hour = 21, minute = 0, skipIfTodayWritten = false),
        )
    }

    @Test
    fun `having written today pushes the reminder to tomorrow`() {
        val now = LocalDateTime.of(2026, 9, 15, 20, 0)
        assertEquals(
            LocalDateTime.of(2026, 9, 16, 21, 0),
            ReminderSchedule.nextTrigger(now, hour = 21, minute = 0, skipIfTodayWritten = true),
        )
    }

    @Test
    fun `the reminder time is preserved across days`() {
        val now = LocalDateTime.of(2026, 12, 31, 23, 0)
        assertEquals(
            LocalDateTime.of(2027, 1, 1, 8, 30),
            ReminderSchedule.nextTrigger(now, hour = 8, minute = 30, skipIfTodayWritten = false),
        )
    }

    @Test
    fun `out of range hours and minutes are clamped`() {
        val now = LocalDateTime.of(2026, 9, 15, 0, 0)
        val target = ReminderSchedule.nextTrigger(now, hour = 99, minute = 99, skipIfTodayWritten = false)
        assertEquals(23, target.hour)
        assertEquals(59, target.minute)
    }

    @Test
    fun `delivery is skipped when the day was already written`() {
        val now = LocalDateTime.of(2026, 9, 15, 21, 5)
        assertTrue(ReminderSchedule.shouldDeliver(now, hasWrittenToday = false, hour = 21, minute = 0))
        assertFalse(ReminderSchedule.shouldDeliver(now, hasWrittenToday = true, hour = 21, minute = 0))
    }

    @Test
    fun `delivery never happens before the target time`() {
        val now = LocalDateTime.of(2026, 9, 15, 20, 59)
        assertFalse(ReminderSchedule.shouldDeliver(now, hasWrittenToday = false, hour = 21, minute = 0))
    }

    /** Doze 可能把任务延后；延后到第二天就不该再投递（那是前一天该发的提醒）。 */
    @Test
    fun `a reminder delayed past midnight is not delivered`() {
        val nextDay = LocalDateTime.of(2026, 9, 16, 9, 0)
        assertFalse(ReminderSchedule.shouldDeliver(nextDay, hasWrittenToday = false, hour = 21, minute = 0))
    }

    @Test
    fun `delay is measured in real time across midnight`() {
        val now = LocalDateTime.of(2026, 9, 15, 20, 0)
        val target = ReminderSchedule.nextTrigger(now, hour = 21, minute = 0, skipIfTodayWritten = false)
        assertEquals(60L * 60L * 1000L, ReminderSchedule.delayMillisUntil(now, target, zone))

        val late = LocalDateTime.of(2026, 9, 15, 22, 0)
        val tomorrow = ReminderSchedule.nextTrigger(late, hour = 21, minute = 0, skipIfTodayWritten = false)
        assertEquals(23L * 60L * 60L * 1000L, ReminderSchedule.delayMillisUntil(late, tomorrow, zone))
    }

    @Test
    fun `delay is never negative`() {
        val now = LocalDateTime.of(2026, 9, 15, 21, 0)
        assertEquals(0L, ReminderSchedule.delayMillisUntil(now, now.minusHours(2), zone))
    }

    @Test
    fun `identifier and string keys match the ios ones`() {
        assertEquals("daily_reminder", ReminderSchedule.IDENTIFIER)
        assertEquals("reminder_title", ReminderSchedule.TITLE_KEY)
        assertEquals("reminder_content", ReminderSchedule.CONTENT_KEY)
    }
}
