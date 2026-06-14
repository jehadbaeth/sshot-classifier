package com.okapiorbits.sshotclassifier.pipeline.clip

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Turns a user's category label into a CLIP text embedding using prompt
 * ensembling: the label is filled into several screenshot-flavored templates,
 * each encoded with the on-device text encoder, and the per-template unit vectors
 * are averaged and re-normalized. Ensembling is what the bundled built-in label
 * embeddings use too; it markedly stabilizes zero-shot scoring over a single prompt.
 *
 * Returns null when the text model is not installed (so the caller can tell the
 * user to install it before adding auto-categories).
 */
@Singleton
class CategoryEmbedder @Inject constructor(
    private val textEncoder: ClipTextEncoder,
) : LabelEmbedder {
    override fun isReady(): Boolean = textEncoder.isReady()

    override fun embed(label: String): FloatArray? {
        val clean = label.trim()
        if (clean.isEmpty() || !textEncoder.isReady()) return null

        val sum = FloatArray(ClipModelManager.EMBED_DIM)
        var n = 0
        for (template in TEMPLATES) {
            val v = textEncoder.encode(template.format(clean)) ?: continue
            for (i in v.indices) sum[i] += v[i]
            n++
        }
        if (n == 0) return null
        return l2normalize(sum)
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var s = 0f
        for (x in v) s += x * x
        val norm = sqrt(s).coerceAtLeast(1e-8f)
        for (i in v.indices) v[i] /= norm
        return v
    }

    companion object {
        /** Screenshot-oriented prompt templates; %s is the user's label. */
        val TEMPLATES = listOf(
            "a screenshot of %s",
            "a screenshot showing %s",
            "a %s screen",
            "an app showing %s",
            "a photo of %s",
            "a picture of %s",
            "%s",
        )
    }
}
