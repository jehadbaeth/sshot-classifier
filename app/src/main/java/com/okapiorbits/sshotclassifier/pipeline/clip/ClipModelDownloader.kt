package com.okapiorbits.sshotclassifier.pipeline.clip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads the CLIP image-encoder model (~90 MB) to internal storage on first
 * use. Writes to a temp file then renames, so a partial download never looks
 * installed.
 *
 * NOTE: MODEL_URL is a placeholder. The repo is private, so release assets are not
 * publicly fetchable; a public host (or a public mirror release) still needs to be
 * decided. For dev/test the model is pushed via adb instead (see ClipModelManager).
 */
@Singleton
class ClipModelDownloader @Inject constructor(
    private val modelManager: ClipModelManager,
) {
    sealed interface State {
        data object Idle : State
        data class Downloading(val progress: Float) : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    suspend fun download(onProgress: (Float) -> Unit): State = withContext(Dispatchers.IO) {
        if (modelManager.isModelInstalled()) return@withContext State.Done
        val tmp = File(modelManager.modelDir, "${ClipModelManager.MODEL_NAME}.part")
        try {
            val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return@withContext State.Failed("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong.coerceAtLeast(1)
            conn.inputStream.use { input ->
                tmp.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    var read = input.read(buf)
                    var done = 0L
                    while (read >= 0) {
                        out.write(buf, 0, read)
                        done += read
                        onProgress(done.toFloat() / total)
                        read = input.read(buf)
                    }
                }
            }
            if (!tmp.renameTo(modelManager.modelFile)) return@withContext State.Failed("rename failed")
            State.Done
        } catch (e: Exception) {
            tmp.delete()
            State.Failed(e.message ?: "download error")
        }
    }

    companion object {
        // TODO: point at a real public host before shipping model download.
        const val MODEL_URL = "https://example.invalid/clip_image_b32_int8w.tflite"
    }
}
