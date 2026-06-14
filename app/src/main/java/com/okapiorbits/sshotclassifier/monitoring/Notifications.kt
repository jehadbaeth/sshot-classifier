package com.okapiorbits.sshotclassifier.monitoring

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

    /** Posts or updates the batch progress notification. No-ops if not permitted. */
    fun progress(context: Context, done: Int, total: Int) {
        ensureChannel(context)
        val notif = NotificationCompat.Builder(context, CHANNEL_PROCESSING)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Classifying screenshots")
            .setContentText("$done of $total")
            .setProgress(total, done, total == 0)
            .setOngoing(done < total)
            .setOnlyAlertOnce(true)
            .build()
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
