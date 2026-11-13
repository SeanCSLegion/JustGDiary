package com.cov.justdiary.service

import android.app.Notification
import com.cov.justdiary.R
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config

/**
 * P1-7：提醒通知的小图标 / 动作 / 深链。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ReminderNotificationRobolectricTest {

    private val context = RuntimeEnvironment.getApplication()

    @Test
    fun `the reminder uses the white silhouette icon and a write action`() {
        val notification = buildReminderNotification(context)

        assertEquals(R.drawable.ic_notification, notification.smallIcon.resId)
        assertEquals(1, notification.actions.size)
        assertEquals(
            context.getString(R.string.reminder_action_write),
            notification.actions[0].title.toString(),
        )
        assertNotNull(notification.contentIntent)
    }

    @Test
    fun `tapping the notification or the action opens today without a hard-coded day key`() {
        val notification = buildReminderNotification(context)
        val content = Shadows.shadowOf(notification.contentIntent).savedIntent
        val action = Shadows.shadowOf(notification.actions[0].actionIntent).savedIntent

        for (intent in listOf(content, action)) {
            assertEquals("justdiary", intent.data?.scheme)
            assertEquals("editor", intent.data?.host)
            assertNull("dayKey 必须打开时现算，不能写死", intent.data?.getQueryParameter("dayKey"))
            assertEquals(
                "com.cov.justdiary.app.MainActivity",
                intent.component?.className,
            )
        }
    }

    @Test
    fun `the notification is auto cancel and uses the reminder channel`() {
        val notification = buildReminderNotification(context)
        assertEquals(Notification.FLAG_AUTO_CANCEL, notification.flags and Notification.FLAG_AUTO_CANCEL)
        assertEquals(ReminderScheduler.CHANNEL_ID, notification.channelId)
    }
}
