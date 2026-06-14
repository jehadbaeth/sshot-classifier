package com.okapiorbits.sshotclassifier.pipeline.clip

import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.exp

/**
 * Zero-shot CLIP scoring: cosine of the image embedding against each precomputed
 * label embedding, softmax over the internal label set (temperature ~0.01, i.e.
 * the CLIP logit scale ~100), then aggregated to user-facing tags by summing the
 * mass of internal labels that map to the same tag.
 */
@Singleton
class ZeroShotClassifier @Inject constructor(
    private val labels: ClipLabels,
) {
    private val logitScale = 100f // CLIP logit scale; equivalent to softmax temperature 0.01

    /** Returns user-facing tag -> probability (sums to ~1 across tags). */
    fun classify(imageEmbedding: FloatArray): Map<String, Float> {
        val all = labels.labels
        if (all.isEmpty()) return emptyMap()

        val logits = FloatArray(all.size) { i -> dot(imageEmbedding, all[i].embedding) * logitScale }
        val max = logits.max()
        var sum = 0f
        val probs = FloatArray(all.size) { i -> exp(logits[i] - max).also { sum += it } }

        val byTag = HashMap<String, Float>()
        for (i in all.indices) {
            val p = probs[i] / sum
            byTag[all[i].tag] = (byTag[all[i].tag] ?: 0f) + p
        }
        return byTag
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}
