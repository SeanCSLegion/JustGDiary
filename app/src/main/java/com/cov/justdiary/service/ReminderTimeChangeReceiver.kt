package com.cov.justdiary.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.cov.justdiary.JustDiaryApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * P1-7：时区 / 系统时间变化后重排提醒。
 *
 * `WorkManager` 的一次性任务按**绝对时间**排队；用户改了时区或手动改时间后，原来那个
 * "延迟 N 毫秒"仍按旧时钟走，提醒会落在错误的本地时刻。这里收到系统广播后 `goAsync()`
 * 重回 `rearm()`，按新的本地时间重算下一次。
 */
class ReminderTimeChangeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            Intent.ACTION_TIMEZONE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            -> Unit
            else -> return
        }
        val app = context.applicationContext as? JustDiaryApplication ?: return
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                app.container.reminderScheduler.rearm()
            } catch (error: Throwable) {
                Log.app.w("时区/时间变化后重排提醒失败：${error.message}")
            } finally {
                pending.finish()
            }
        }
    }
}
