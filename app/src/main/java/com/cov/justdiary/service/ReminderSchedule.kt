package com.cov.justdiary.service

import java.time.Duration
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * 每日提醒的排期计算。移植自 iOS `Services/ReminderService.swift`。
 *
 * ## iOS 的做法
 *
 * `UNCalendarNotificationTrigger(dateMatching: [year, month, day, hour, minute], repeats: false)`
 * —— **一次触发**，靠 `rearm()` 在每次进入前台 / 改设置后重新排下一天；
 * 排期时若"今天已经写过"，就顺延到明天。
 *
 * ## Android 的落地
 *
 * 精确闹钟在 Android 12+ 需要 `SCHEDULE_EXACT_ALARM`（用户可随时撤销）、
 * 而 `USE_EXACT_ALARM` 只允许闹钟/日历类应用，日记提醒用它会违反 Play 政策
 * （方案 §8.2 与决策 Q7）。因此这里用 **`WorkManager` 的一次性任务**，
 * 在投递后再排下一天；Doze 下允许有几分钟偏差。
 *
 * 本对象只负责**算下一次该在什么时候**（纯函数），不碰平台调度。
 */
object ReminderSchedule {

    /** 通知与任务的唯一标识（与 iOS 的 identifier 同名）。 */
    const val IDENTIFIER = "daily_reminder"

    /**
     * 下一次触发时刻。
     *
     * @param skipIfTodayWritten 今天已经写过日记时顺延到明天
     */
    fun nextTrigger(
        now: LocalDateTime,
        hour: Int,
        minute: Int,
        skipIfTodayWritten: Boolean,
    ): LocalDateTime {
        val todayTarget = now.toLocalDate().atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        return if (skipIfTodayWritten || !now.isBefore(todayTarget)) {
            todayTarget.plusDays(1)
        } else {
            todayTarget
        }
    }

    /**
     * 到点投递前再确认一次：今天没写过才提醒，且现在确实已经过了提醒时刻。
     *
     * iOS 靠系统触发时间保证"到点"，但 WorkManager 可能因 Doze 延后 ——
     * 延后到第二天再投递就荒唐了，所以这里给出显式的判定。
     */
    fun shouldDeliver(
        now: LocalDateTime,
        hasWrittenToday: Boolean,
        hour: Int,
        minute: Int,
    ): Boolean {
        if (hasWrittenToday) return false
        val todayTarget = now.toLocalDate().atTime(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
        return !now.isBefore(todayTarget) && now.isBefore(todayTarget.plusDays(1))
    }

    /** 距离目标时刻的毫秒数（WorkManager 需要的是延迟而不是绝对时刻）。 */
    fun delayMillisUntil(now: LocalDateTime, target: LocalDateTime, zone: ZoneId = ZoneId.systemDefault()): Long =
        Duration.between(now.atZone(zone), target.atZone(zone)).toMillis().coerceAtLeast(0L)

    /** 通知文案的资源 key（与 iOS 保持一致，译文在 `strings.xml`）。 */
    const val TITLE_KEY = "reminder_title"
    const val CONTENT_KEY = "reminder_content"
}
