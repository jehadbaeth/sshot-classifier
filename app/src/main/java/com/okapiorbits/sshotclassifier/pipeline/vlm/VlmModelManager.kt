package com.okapiorbits.sshotclassifier.pipeline.vlm

import android.content.Context
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Tracks the user-provided VLM model file for the experimental generative describer
 * (Gemma 3n E2B `.task`, ~3.1 GB; see docs/spikes/vlm-device-research.md).
 *
 * The model is NEVER bundled in the APK or hosted by us: it is far over GitHub's 2 GB
 * release-asset limit, and Gemma's weights are gated on the official sources (the user
 * must accept Google's licence). So the user downloads it themselves and imports it via
 * the Storage Access Framework ([importFrom]); we copy it into app-internal storage so the
 * MediaPipe runtime can open a plain file path. A `.part` temp + rename makes a half-copied
 * import never look installed.
 */
@Singleton
class VlmModelManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val modelDir: File get() = File(context.filesDir, "models").apply { mkdirs() }

    /** The user-imported multimodal model the LLM Inference API loads. */
    val modelFile: File get() = File(modelDir, MODEL_NAME)

    /** A model is considered installed only well above any partial copy. */
    fun isInstalled(): Boolean = modelFile.exists() && modelFile.length() > MIN_VALID_BYTES

    fun sizeBytes(): Long = modelFile.let { if (it.exists()) it.length() else 0L }

    /** Removes an imported model (e.g. to re-import or reclaim ~3 GB). */
    fun delete(): Boolean = !modelFile.exists() || modelFile.delete()

    sealed interface ImportResult {
        data class Done(val bytes: Long) : ImportResult
        data class Failed(val message: String) : ImportResult
    }

    /**
     * Copies the model from a SAF [uri] into [modelFile]. Streams to a `.part` file and
     * renames on success so an interrupted copy never passes [isInstalled]. [onProgress] is
     * best-effort: the total comes from the SAF document size when the provider reports it,
     * else progress stays at 0 until completion.
     */
    suspend fun importFrom(uri: Uri, onProgress: (Float) -> Unit = {}): ImportResult =
        withContext(Dispatchers.IO) {
            val tmp = File(modelDir, "$MODEL_NAME.part")
            try {
                val total = querySize(uri)
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20) // 1 MB
                        var copied = 0L
                        var read = input.read(buf)
                        while (read >= 0) {
                            out.write(buf, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            read = input.read(buf)
                        }
                    }
                } ?: return@withContext ImportResult.Failed("Could not open the selected file")

                val bytes = tmp.length()
                if (bytes <= MIN_VALID_BYTES) {
                    tmp.delete()
                    return@withContext ImportResult.Failed("File looks too small to be the model")
                }
                if (modelFile.exists()) modelFile.delete()
                if (!tmp.renameTo(modelFile)) {
                    tmp.delete()
                    return@withContext ImportResult.Failed("Could not save the model")
                }
                onProgress(1f)
                ImportResult.Done(bytes)
            } catch (e: Exception) {
                tmp.delete()
                ImportResult.Failed(e.message ?: "Import failed")
            }
        }

    /**
     * Downloads the model from [MODEL_URL] into [modelFile], streaming to a `.part` file,
     * verifying [MODEL_SHA256], then renaming on success. The checksum is the trust anchor:
     * the URL points at a community re-host (it can change or vanish), so a swapped or corrupt
     * file is rejected rather than loaded. No resume: a failure restarts the ~3 GB download.
     */
    suspend fun download(onProgress: (Float) -> Unit = {}): ImportResult =
        withContext(Dispatchers.IO) {
            val tmp = File(modelDir, "$MODEL_NAME.part")
            try {
                val conn = (URL(MODEL_URL).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 30_000
                    readTimeout = 60_000
                }
                conn.connect()
                if (conn.responseCode !in 200..299) {
                    return@withContext ImportResult.Failed("Download failed (HTTP ${conn.responseCode})")
                }
                val total = conn.contentLengthLong.coerceAtLeast(MODEL_SIZE_BYTES)
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(1 shl 20) // 1 MB
                        var copied = 0L
                        var read = input.read(buf)
                        while (read >= 0) {
                            out.write(buf, 0, read)
                            digest.update(buf, 0, read)
                            copied += read
                            if (total > 0) onProgress((copied.toFloat() / total).coerceIn(0f, 1f))
                            read = input.read(buf)
                        }
                    }
                }
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                if (!actual.equals(MODEL_SHA256, ignoreCase = true)) {
                    tmp.delete()
                    return@withContext ImportResult.Failed("Checksum mismatch (download corrupt or model changed)")
                }
                if (modelFile.exists()) modelFile.delete()
                if (!tmp.renameTo(modelFile)) {
                    tmp.delete()
                    return@withContext ImportResult.Failed("Could not save the model")
                }
                onProgress(1f)
                ImportResult.Done(modelFile.length())
            } catch (e: Exception) {
                tmp.delete()
                ImportResult.Failed(e.message ?: "Download failed")
            }
        }

    /** SAF document size via OpenableColumns, or -1 if the provider does not report it. */
    private fun querySize(uri: Uri): Long = runCatching {
        context.contentResolver.query(uri, null, null, null, null)?.use { c ->
            val idx = c.getColumnIndex(android.provider.OpenableColumns.SIZE)
            if (idx >= 0 && c.moveToFirst() && !c.isNull(idx)) c.getLong(idx) else -1L
        } ?: -1L
    }.getOrDefault(-1L)

    companion object {
        /** MediaPipe LLM Inference loads a `.task` bundle (Gemma 3n E2B multimodal). */
        const val MODEL_NAME = "vlm_model.task"

        /** Floor to reject an obviously-wrong import; the real model is ~3.1 GB. */
        const val MIN_VALID_BYTES = 100_000_000L

        /**
         * Gemma 3n E2B int4 `.task`. This is a COMMUNITY re-host (Gemma is gated on the official
         * source, so there is no first-party no-auth link). It can change or disappear; the
         * sha256 below is the trust + integrity anchor. Swap this to a self-owned public mirror
         * for durability. See docs/spikes/vlm-device-research.md.
         */
        const val MODEL_URL =
            "https://huggingface.co/xiaohan1/gemma3n/resolve/main/gemma-3n-E2B-it-int4.task"
        const val MODEL_SHA256 =
            "a7f544cfee68f579fabadb22aa9284faa4020a0f5358d0e15b49fdd4cefe4200"
        const val MODEL_SIZE_BYTES = 3_136_226_711L
    }
}
