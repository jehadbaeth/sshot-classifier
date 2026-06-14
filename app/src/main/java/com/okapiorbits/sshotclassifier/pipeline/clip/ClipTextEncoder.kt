package com.okapiorbits.sshotclassifier.pipeline.clip

import org.tensorflow.lite.Interpreter
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Runs the CLIP ViT-B/32 text encoder (TFLite, int8 weight-only) on a tokenized
 * query and returns an L2-normalized 512-dim embedding in the SAME space as the
 * stored image embeddings, so cosine similarity gives visual search relevance.
 *
 * Lazily created and only if the text model is installed; returns null otherwise
 * so semantic search degrades gracefully to OCR full-text search.
 */
@Singleton
class ClipTextEncoder @Inject constructor(
    private val modelManager: ClipModelManager,
    private val tokenizer: ClipTokenizer,
) {
    @Volatile private var interpreter: Interpreter? = null

    private fun ensureInterpreter(): Interpreter? {
        interpreter?.let { return it }
        if (!modelManager.isTextModelInstalled()) return null
        return synchronized(this) {
            interpreter ?: runCatching {
                Interpreter(modelManager.textModelFile, Interpreter.Options().apply { numThreads = 4 })
            }.getOrNull()?.also { interpreter = it }
        }
    }

    fun isReady(): Boolean = modelManager.isTextModelInstalled()

    /** Returns the L2-normalized text embedding, or null if model missing or run fails. */
    fun encode(text: String): FloatArray? {
        val interp = ensureInterpreter() ?: return null
        val tokens = tokenizer.tokenize(text, ClipModelManager.CONTEXT_LENGTH)
        val input = arrayOf(tokens) // [1, 77] int32
        val output = Array(1) { FloatArray(ClipModelManager.EMBED_DIM) }
        return try {
            interp.run(input, output)
            l2normalize(output[0])
        } catch (e: Exception) {
            null
        }
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).coerceAtLeast(1e-8f)
        for (i in v.indices) v[i] /= norm
        return v
    }
}
