package com.cov.justdiary.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.cov.justdiary.R
import com.cov.justdiary.data.DiaryRepository
import com.cov.justdiary.model.DateUtil
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * 每日提醒的调度。对应 iOS `Services/ReminderService.swift`。
 *
 * ## 为什么用 `WorkManager` 而不是精确闹钟
 *
 * iOS 用一次性的 `UNCalendarNotificationTrigger`，系统保证准点。Android 上：
 *
 * - `AlarmManager.setExactAndAllowWhileIdle` 需要 `SCHEDULE_EXACT_ALARM`（API 31+），
 *   而**用户可以随时在系统设置里撤销**；
 * - `USE_EXACT_ALARM` 只对闹钟/日历类应用开放，日记提醒用它**会违反 Play 政策**。
 *
 * 因此按决策 Q7 用 `WorkManager` 的一次性任务，投递后再排下一天，
 * 接受 Doze 下几分钟的偏差（排期规则本身由纯函数 [ReminderSchedule] 保证，有单测）。
 *
 * 其它与 iOS 对齐的点：只在用户打开提醒开关时申请通知权限；"今天已写"则顺延到明天；
 * 语言变化后要重排（通知文案跟着语言）。
 */
class ReminderScheduler(
    context: Context,
    private val settings: SettingsStore,
    private val repository: DiaryRepository,
) {

    private val appContext: Context = context.applicationContext

    /** 通知渠道（API 26+ 必须建，重复调用是幂等的）。 */
    fun ensureChannel() {
        val manager = appContext.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            appContext.getString(R.string.reminder_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = appContext.getString(R.string.reminder_channel_description)
        }
        manager.createNotificationChannel(channel)
    }

    /** 是否已获得通知权限（API 33+ 需要运行时授权）。 */
    fun canPostNotifications(): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        }

    /**
     * 重排下一次提醒。对应 iOS 的 `rearm()` / `ensureDailyReminder()`：
     * 关闭开关则取消；开着则按"今天是否已写"算出下一次时刻。
     */
    suspend fun rearm() {
        val current = settings.load()
        if (!current.remindEnabled) {
            cancelAll()
            return
        }
        if (!canPostNotifications()) {
            // 没有通知权限就不排了 —— 与 iOS 一致：权限在打开开关时申请，这里不弹窗
            Log.app.w("没有通知权限，跳过提醒排期")
            return
        }
        ensureChannel()

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val todayKey = DateUtil.dayKeyForUtc(System.currentTimeMillis(), current.dayStartHour, zone)
        val hasWrittenToday = repository.getDiaryByDay(todayKey) != null
        val target = ReminderSchedule.nextTrigger(
            now = now,
            hour = current.remindHour,
            minute = current.remindMinute,
            skipIfTodayWritten = hasWrittenToday,
        )
        val delay = ReminderSchedule.delayMillisUntil(now, target, zone)

        val request = OneTimeWorkRequestBuilder<DailyReminderWorker>()
            .setInitialDelay(delay, TimeUnit.MILLISECONDS)
            .addTag(TAG)
            .build()
        WorkManager.getInstance(appContext)
            .enqueueUniqueWork(ReminderSchedule.IDENTIFIER, ExistingWorkPolicy.REPLACE, request)
        Log.app.i("提醒已排期：$target（${delay / 1000}s 后）")
    }

    fun cancelAll() {
        WorkManager.getInstance(appContext).cancelUniqueWork(ReminderSchedule.IDENTIFIER)
        NotificationManagerCompat.from(appContext).cancel(ReminderSchedule.IDENTIFIER.hashCode())
    }

    companion object {
        const val CHANNEL_ID = "just_diary_reminder"
        const val TAG = "daily_reminder"
    }
}

/**
 * 投递每日提醒的 Worker。
 *
 * 到点时**再确认一次**：今天还没写、且现在确实过了提醒时刻才投递
 * （`WorkManager` 可能因 Doze 被延后，延后到第二天就不该再发）。投递后立刻排下一天。
 */
class DailyReminderWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val container = (applicationContext as? com.cov.justdiary.JustDiaryApplication)?.container
            ?: return Result.success()
        val settings = container.settings
        val current = settings.load()
        if (!current.remindEnabled) return Result.success()

        val zone = ZoneId.systemDefault()
        val now = LocalDateTime.now(zone)
        val todayKey = DateUtil.dayKeyForUtc(System.currentTimeMillis(), current.dayStartHour, zone)
        val hasWrittenToday = container.repository.getDiaryByDay(todayKey) != null

        if (ReminderSchedule.shouldDeliver(now, hasWrittenToday, current.remindHour, current.remindMinute)) {
            notifyReminder()
        }
        // 无论是否投递都排下一天（否则「今天已写」之后提醒就永远不会再来）
        container.reminderScheduler.rearm()
        return Result.success()
    }

    private fun notifyReminder() {
        val context = applicationContext
        val scheduler = (context as? com.cov.justdiary.JustDiaryApplication)?.container?.reminderScheduler
        scheduler?.ensureChannel()

        // 点通知直接打开应用（M9 可以带 dayKey 直达当天编辑页）
        val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val pending = intent?.let {
            PendingIntent.getActivity(
                context,
                0,
                it,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        }

        val notification = NotificationCompat.Builder(context, ReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(context.getString(R.string.reminder_title))
            .setContentText(context.getString(R.string.reminder_content))
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setAutoCancel(true)
            .apply { pending?.let { setContentIntent(it) } }
            .build()

        runCatching {
            NotificationManagerCompat.from(context).notify(ReminderSchedule.IDENTIFIER.hashCode(), notification)
        }.onFailure { Log.app.w("投递提醒通知失败：${it.message}") }
    }

    companion object {
        const val UNUSED_INTENT_ACTION = "com.cov.justdiary.REMIND"
    }
}
