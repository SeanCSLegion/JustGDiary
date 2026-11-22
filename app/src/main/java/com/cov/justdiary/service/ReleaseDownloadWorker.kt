package com.cov.justdiary.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.cov.justdiary.R
import com.cov.justdiary.app.MainActivity
import com.cov.justdiary.app.LaunchIntent
import java.io.File

/** Long-running, uniquely enqueued release download that survives UI and Activity recreation. */
class ReleaseDownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val release = runCatching {
            GitHubReleaseUpdater.Release(
                tag = inputData.getString(KEY_TAG) ?: error("Missing release tag"),
                versionName = inputData.getString(KEY_VERSION) ?: error("Missing version"),
                notes = inputData.getString(KEY_NOTES).orEmpty(),
                downloadUrl = inputData.getString(KEY_URL) ?: error("Missing download URL"),
                expectedSize = inputData.getLong(KEY_SIZE, 0L),
                sha256 = inputData.getString(KEY_SHA256),
            )
        }.getOrElse { return Result.failure(workDataOfError(it)) }
        ensureChannel()
        setForeground(notificationInfo(0, 0))
        return runCatching {
            val file = GitHubReleaseUpdater(applicationContext).downloadAndVerify(release) { downloaded, total ->
                val percent = if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0
                setProgress(Data.Builder().putLong(KEY_DOWNLOADED, downloaded).putLong(KEY_SIZE, total).build())
                setForeground(notificationInfo(percent, downloaded, total))
            }
            Result.success(
                Data.Builder().putString(KEY_FILE, file.absolutePath)
                    .putString(KEY_TAG, release.tag).putString(KEY_VERSION, release.versionName)
                    .putString(KEY_NOTES, release.notes).putString(KEY_URL, release.downloadUrl)
                    .putLong(KEY_SIZE, release.expectedSize).putString(KEY_SHA256, release.sha256).build(),
            )
        }.getOrElse { error -> Result.failure(workDataOfError(error)) }
    }

    private fun ensureChannel() {
        val manager = applicationContext.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(CHANNEL_ID, applicationContext.getString(R.string.update_channel_name), NotificationManager.IMPORTANCE_LOW))
    }

    private fun notificationInfo(percent: Int, downloaded: Long, total: Long = 0L): ForegroundInfo {
        val text = if (total > 0) "$percent% · ${formatBytes(downloaded)} / ${formatBytes(total)}" else applicationContext.getString(R.string.update_downloading)
        val open = PendingIntent.getActivity(applicationContext, NOTIFICATION_ID, Intent(applicationContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(LaunchIntent.EXTRA_OPEN_SETTINGS, true)
        }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(applicationContext.getString(R.string.update_downloading))
            .setContentText(text)
            .setContentIntent(open)
            .setProgress(100, percent, total <= 0)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
        // WorkManager posts this foreground notification. Without POST_NOTIFICATIONS it remains
        // available in system task management while in-app WorkInfo progress continues to update.
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun formatBytes(value: Long): String = if (value < 1024) "$value B" else "%.1f MB".format(value / 1024.0 / 1024.0)

    private fun workDataOfError(error: Throwable) = Data.Builder().putString(KEY_ERROR, error.message ?: "APK verification failed").build()

    companion object {
        const val UNIQUE_WORK = "release-apk-download"
        const val CHANNEL_ID = "release_downloads"
        const val NOTIFICATION_ID = 7301
        const val KEY_TAG = "tag"
        const val KEY_VERSION = "version"
        const val KEY_NOTES = "notes"
        const val KEY_URL = "url"
        const val KEY_SIZE = "size"
        const val KEY_SHA256 = "sha256"
        const val KEY_FILE = "file"
        const val KEY_ERROR = "error"
        const val KEY_DOWNLOADED = "downloaded"

        fun input(release: GitHubReleaseUpdater.Release): Data = Data.Builder()
            .putString(KEY_TAG, release.tag).putString(KEY_VERSION, release.versionName)
            .putString(KEY_NOTES, release.notes).putString(KEY_URL, release.downloadUrl)
            .putLong(KEY_SIZE, release.expectedSize).putString(KEY_SHA256, release.sha256).build()
    }
}
