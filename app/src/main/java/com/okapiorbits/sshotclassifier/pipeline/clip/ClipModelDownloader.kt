package com.okapiorbits.sshotclassifier.pipeline.clip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Downloads the CLIP image-encoder model (~90 MB) to internal storage on first
 * use, verifies its sha256, then renames into place so a partial or corrupt
 * download never looks installed. Hosted on a public mirror repo because the app
 * repo is private (release assets of a private repo are not publicly fetchable).
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
            val actual = sha256(tmp)
            if (!actual.equals(MODEL_SHA256, ignoreCase = true)) {
                tmp.delete()
                return@withContext State.Failed("checksum mismatch")
            }
            if (!tmp.renameTo(modelManager.modelFile)) return@withContext State.Failed("rename failed")
            State.Done
        } catch (e: Exception) {
            tmp.delete()
            State.Failed(e.message ?: "download error")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { stream ->
            val buf = ByteArray(64 * 1024)
            var read = stream.read(buf)
            while (read >= 0) {
                digest.update(buf, 0, read)
                read = stream.read(buf)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val MODEL_URL =
            "https://github.com/jehadbaeth/sshot-classifier-models/releases/download/clip-vit-b32-laion-int8/clip_image_b32_int8w.tflite"
        const val MODEL_SHA256 =
            "c2745120a841d43db4768a948bab42f612ceedb8f379b73df558215449e4f034"
    }
}
