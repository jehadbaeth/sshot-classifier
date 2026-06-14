package com.okapiorbits.sshotclassifier.monitoring

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import com.okapiorbits.sshotclassifier.data.repository.ScreenshotRepository
import com.okapiorbits.sshotclassifier.pipeline.ImageProcessor
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Syncs new screenshots from MediaStore, then processes every PENDING row
 * (OCR + heuristic tagging) while posting a progress notification.
 *
 * Runs expedited so a manual scan or a freshly captured screenshot is handled
 * promptly. Uses a normal progress notification, not a foreground service; a
 * foreground service for very large backfills is a later refinement.
 */
@HiltWorker
class ScreenshotProcessingWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val repository: ScreenshotRepository,
    private val processor: ImageProcessor,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        repository.syncFromMediaStore()

        val pending = repository.pendingScreenshots()
        if (pending.isEmpty()) return Result.success()

        val total = pending.size
        Notifications.progress(applicationContext, 0, total)
        var done = 0
        for (shot in pending) {
            processor.process(shot)
            done++
            Notifications.progress(applicationContext, done, total)
        }
        Notifications.clear(applicationContext)
        return Result.success()
    }

    companion object {
        const val UNIQUE_NAME = "screenshot-processing"
        const val PERIODIC_NAME = "screenshot-processing-periodic"

        /** Enqueue an expedited one-off run (manual scan or live detection). */
        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<ScreenshotProcessingWorker>()
                .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Periodic catch-up so nothing is missed while the app is not running. */
        fun schedulePeriodic(context: Context) {
            val request = PeriodicWorkRequestBuilder<ScreenshotProcessingWorker>(
                6, TimeUnit.HOURS,
            ).setConstraints(
                Constraints.Builder().setRequiresBatteryNotLow(true).build()
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                PERIODIC_NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
        }
    }
}
