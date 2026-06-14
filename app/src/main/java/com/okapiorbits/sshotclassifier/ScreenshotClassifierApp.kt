package com.okapiorbits.sshotclassifier

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.okapiorbits.sshotclassifier.monitoring.Notifications
import com.okapiorbits.sshotclassifier.monitoring.ScreenshotContentObserver
import com.okapiorbits.sshotclassifier.monitoring.ScreenshotProcessingWorker
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class ScreenshotClassifierApp : Application(), Configuration.Provider {

    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        Notifications.ensureChannel(this)
        ScreenshotProcessingWorker.schedulePeriodic(this)
        ScreenshotContentObserver(this).register()
    }
}
