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
 * Downloads the on-device CLIP models to internal storage on first use, verifies
 * each sha256, then renames into place so a partial or corrupt download never
 * looks installed. Two models are fetched:
 *   - the image encoder (~90 MB): tagging + image embeddings
 *   - the text encoder (~65 MB): free-text semantic search (Phase 3)
 * Already-installed models are skipped, so a user updating from a tagging-only
 * build only pays for the new text model.
 *
 * Hosted on a public mirror repo because the app repo is private (release assets
 * of a private repo are not publicly fetchable).
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

    private data class Spec(val url: String, val sha256: String, val target: File)

    private fun specs(): List<Spec> = listOf(
        Spec(IMAGE_MODEL_URL, IMAGE_MODEL_SHA256, modelManager.modelFile),
        Spec(TEXT_MODEL_URL, TEXT_MODEL_SHA256, modelManager.textModelFile),
    )

    suspend fun download(onProgress: (Float) -> Unit): State = withContext(Dispatchers.IO) {
        val pending = specs().filter { !isInstalled(it.target) }
        if (pending.isEmpty()) return@withContext State.Done

        val count = pending.size
        pending.forEachIndexed { index, spec ->
            val result = downloadOne(spec) { p ->
                onProgress((index + p) / count)
            }
            if (result is State.Failed) return@withContext result
        }
        State.Done
    }

    private fun isInstalled(f: File): Boolean = f.exists() && f.length() > 1_000_000

    private fun downloadOne(spec: Spec, onProgress: (Float) -> Unit): State {
        val tmp = File(spec.target.parentFile, "${spec.target.name}.part")
        return try {
            val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 30_000
                readTimeout = 30_000
            }
            conn.connect()
            if (conn.responseCode !in 200..299) return State.Failed("HTTP ${conn.responseCode}")
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
            if (!actual.equals(spec.sha256, ignoreCase = true)) {
                tmp.delete()
                return State.Failed("checksum mismatch")
            }
            if (!tmp.renameTo(spec.target)) return State.Failed("rename failed")
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
        const val IMAGE_MODEL_URL =
            "https://github.com/jehadbaeth/sshot-classifier-models/releases/download/clip-vit-b32-laion-int8/clip_image_b32_int8w.tflite"
        const val IMAGE_MODEL_SHA256 =
            "c2745120a841d43db4768a948bab42f612ceedb8f379b73df558215449e4f034"
        const val TEXT_MODEL_URL =
            "https://github.com/jehadbaeth/sshot-classifier-models/releases/download/clip-vit-b32-laion-int8/clip_text_b32_int8w.tflite"
        const val TEXT_MODEL_SHA256 =
            "322c79491743246b38e00cbd9ce26cd915d1042ab642602ea9db04da009d52d4"
    }
}
