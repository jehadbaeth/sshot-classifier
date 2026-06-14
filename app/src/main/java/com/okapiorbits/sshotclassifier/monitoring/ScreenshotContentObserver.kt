package com.okapiorbits.sshotclassifier.monitoring

import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore

/**
 * Live detection: watches the MediaStore images collection and kicks off
 * processing when it changes. This replaces FileObserver, which is unreliable
 * under scoped storage. WorkManager's KEEP policy debounces rapid changes.
 */
class ScreenshotContentObserver(
    private val context: Context,
) : ContentObserver(Handler(Looper.getMainLooper())) {

    fun register() {
        context.contentResolver.registerContentObserver(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            true,
            this,
        )
    }

    override fun onChange(selfChange: Boolean, uri: Uri?) {
        ScreenshotProcessingWorker.enqueue(context)
    }
}
