package com.okapiorbits.sshotclassifier.monitoring

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object Notifications {
    const val CHANNEL_PROCESSING = "processing"
    const val ID_PROGRESS = 1001

    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_PROCESSING,
                "Screenshot processing",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Progress while classifying screenshots" }
            context.getSystemService(NotificationManager::class.java)
                .createNotificationChannel(channel)
        }
    }

    /** Builds the batch progress notification (also used as the foreground-service notification). */
    fun buildProgress(context: Context, done: Int, total: Int): Notification {
        ensureChannel(context)
        return NotificationCompat.Builder(context, CHANNEL_PROCESSING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Classifying screenshots")
            .setContentText(if (total > 0) "$done of $total" else "Looking for screenshots…")
            .setProgress(total, done, total == 0)
            .setOngoing(done < total)
            .setOnlyAlertOnce(true)
            .build()
    }

    /** Posts or updates the batch progress notification. No-ops if not permitted. */
    fun progress(context: Context, done: Int, total: Int) {
        val notif = buildProgress(context, done, total)
        try {
            NotificationManagerCompat.from(context).notify(ID_PROGRESS, notif)
        } catch (e: SecurityException) {
            // POST_NOTIFICATIONS not granted; processing still runs.
        }
    }

    fun clear(context: Context) {
        NotificationManagerCompat.from(context).cancel(ID_PROGRESS)
    }
}
