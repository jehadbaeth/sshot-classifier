package com.okapiorbits.sshotclassifier.monitoring

import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit
import com.okapiorbits.sshotclassifier.data.media.ScreenshotOrganizer
import com.okapiorbits.sshotclassifier.data.prefs.ReorgMode
import com.okapiorbits.sshotclassifier.data.prefs.ReorgPreferencesStore
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
    private val organizer: ScreenshotOrganizer,
    private val reorgPrefsStore: ReorgPreferencesStore,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        // Run in the foreground so a long backfill survives the app being backgrounded.
        runCatching { setForeground(foregroundInfo(0, 0)) }

        repository.syncFromMediaStore()

        val pending = repository.pendingScreenshots()
        if (pending.isNotEmpty()) {
            val total = pending.size
            runCatching { setForeground(foregroundInfo(0, total)) }
            var done = 0
            for (shot in pending) {
                processor.process(shot)
                done++
                Notifications.progress(applicationContext, done, total)
            }
            Notifications.clear(applicationContext)
        }

        maybeAutoReorganize()
        return Result.success()
    }

    /**
     * If the user enabled auto-run, copy newly-tagged screenshots into albums. This is
     * always a COPY even when MOVE is the selected mode: deleting originals needs a
     * user-facing consent dialog that a background worker cannot show, so destructive
     * moves stay a deliberate manual action.
     */
    private suspend fun maybeAutoReorganize() {
        val prefs = reorgPrefsStore.current()
        if (!prefs.autoRun || !organizer.isSupported) return
        runCatching { organizer.organizeIntoAlbums(prefs.copy(mode = ReorgMode.COPY)) }
    }

    /** Used both for expedited fallback on API < 31 and the dataSync foreground run. */
    override suspend fun getForegroundInfo(): ForegroundInfo = foregroundInfo(0, 0)

    private fun foregroundInfo(done: Int, total: Int): ForegroundInfo {
        val notification = Notifications.buildProgress(applicationContext, done, total)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(Notifications.ID_PROGRESS, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(Notifications.ID_PROGRESS, notification)
        }
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
