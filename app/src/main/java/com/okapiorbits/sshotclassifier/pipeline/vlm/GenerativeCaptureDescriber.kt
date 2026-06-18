package com.okapiorbits.sshotclassifier.pipeline.vlm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.genai.llminference.GraphOptions
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.google.mediapipe.tasks.genai.llminference.LlmInferenceSession
import com.okapiorbits.sshotclassifier.pipeline.CaptureContext
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.max

/**
 * EXPERIMENTAL on-device generative caption (Gemma 3n E2B multimodal via the MediaPipe LLM
 * Inference API). See docs/spikes/vlm-device-research.md.
 *
 * IMPORTANT — this path is UNVERIFIED on real hardware by the author: it cannot run on the
 * emulator, so it has only been compile- and logic-checked here. It is fenced behind the
 * device-capability gate, the experimental opt-in, and a user-imported model, and every
 * failure falls back to the structured describer ([generate] returns null), so a wrong
 * assumption here can never corrupt a capture's description.
 *
 * Memory lifecycle: the model weights are ~3 GB resident, so [LlmInference] is created,
 * used, and closed within a single [generate] call — it is NEVER held across calls or as a
 * singleton field. A [Mutex] serialises calls so two captures can't load the model at once
 * and OOM the process. This makes each caption pay the multi-second model-load cost; captures
 * are infrequent (manual photos) so that is acceptable for v1. Batch-level loading is a
 * documented follow-up, not a correctness issue.
 */
@Singleton
class GenerativeCaptureDescriber @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: VlmModelManager,
) {
    private val mutex = Mutex()

    /**
     * Generates a one-line caption for [ctx]'s image, or null on any failure (missing model,
     * no image, decode error, runtime/OOM error). Callers must fall back to the structured
     * describer on null.
     */
    suspend fun generate(ctx: CaptureContext): String? {
        val uri = ctx.imageUri ?: return null
        if (!modelManager.isInstalled()) return null
        return mutex.withLock {
            withContext(Dispatchers.Default) {
                Log.i(TAG, "Generative caption: loading model (${modelManager.sizeBytes() / 1_000_000} MB) for $uri")
                runCatching { runInference(uri, ctx) }
                    .onFailure { Log.w(TAG, "Generative caption failed; falling back to structured", it) }
                    .getOrNull()
            }
        }
    }

    private fun runInference(uri: Uri, ctx: CaptureContext): String? {
        val bitmap = loadDownscaledBitmap(uri) ?: run {
            Log.w(TAG, "Could not decode capture image; falling back")
            return null
        }
        val options = LlmInference.LlmInferenceOptions.builder()
            .setModelPath(modelManager.modelFile.absolutePath)
            .setMaxNumImages(1)
            .build()

        LlmInference.createFromOptions(context, options).use { llm ->
            val sessionOptions = LlmInferenceSession.LlmInferenceSessionOptions.builder()
                .setGraphOptions(GraphOptions.builder().setEnableVisionModality(true).build())
                .build()
            LlmInferenceSession.createFromOptions(llm, sessionOptions).use { session ->
                session.addQueryChunk(buildPrompt(ctx))
                session.addImage(BitmapImageBuilder(bitmap).build())
                val raw = session.generateResponse()
                val caption = raw?.let(::tidy)?.takeIf { it.isNotBlank() }
                Log.i(TAG, "Generative caption ${if (caption != null) "ok (${caption.length} chars)" else "empty"}")
                return caption
            }
        }
    }

    /** A concise, factual instruction; tags are passed as a hint, not as the answer. */
    private fun buildPrompt(ctx: CaptureContext): String {
        val hint = ctx.tags.filter { it != "other" && it != "qr code" }
            .take(3).joinToString(", ")
        val lead = "Describe this photo in one short factual sentence for a searchable inventory. " +
            "Do not start with \"This image\"."
        return if (hint.isBlank()) lead else "$lead It may show: $hint."
    }

    /** Collapse whitespace, trim, and cap length so a runaway generation can't bloat the row. */
    private fun tidy(s: String): String {
        val collapsed = s.replace(WHITESPACE, " ").trim()
        return if (collapsed.length <= MAX_LEN) collapsed else collapsed.take(MAX_LEN).trimEnd() + "…"
    }

    /**
     * Decodes [uri] downscaled so the model gets a sane input and the device does not decode a
     * full-resolution photo into memory next to a multi-GB model.
     */
    private fun loadDownscaledBitmap(uri: Uri): Bitmap? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, bounds)
        }
        val longest = max(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (longest / sample > MAX_DIM * 2) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        context.contentResolver.openInputStream(uri)?.use {
            BitmapFactory.decodeStream(it, null, opts)
        }
    }.getOrNull()

    companion object {
        private const val TAG = "GenerativeCaption"
        private const val MAX_LEN = 200
        private const val MAX_DIM = 768
        private val WHITESPACE = Regex("""\s+""")
    }
}
