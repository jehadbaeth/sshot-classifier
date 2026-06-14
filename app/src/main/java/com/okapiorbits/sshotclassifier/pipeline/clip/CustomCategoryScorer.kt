package com.okapiorbits.sshotclassifier.pipeline.clip

import com.okapiorbits.sshotclassifier.pipeline.TagCandidate

/**
 * Scores an image embedding against user-defined category embeddings by cosine
 * similarity (a dot product, since both are L2-normalized) and keeps those at or
 * above a threshold. Independent of the built-in softmax: custom categories are
 * purely additive and never disturb the built-in tags.
 *
 * Pure and dependency-free so the thresholding can be unit-tested on the JVM.
 */
object CustomCategoryScorer {

    /**
     * Default cosine cutoff. CLIP ViT-B/32 cross-modal cosines for a genuine match
     * sit around 0.25-0.30 (see the Phase 3 on-device numbers), so this is set just
     * below that band. It is deliberately conservative and documented as tunable;
     * the manual "remove tag" action is the safety net for false positives.
     */
    const val DEFAULT_THRESHOLD = 0.24f

    /** A category to score: its label and its unit embedding. */
    data class Category(val label: String, val embedding: FloatArray)

    /**
     * Returns the matching categories as weighted tag candidates (weight = cosine),
     * highest first. [imageEmbedding] is assumed L2-normalized.
     */
    fun score(
        imageEmbedding: FloatArray,
        categories: List<Category>,
        threshold: Float = DEFAULT_THRESHOLD,
    ): List<TagCandidate> =
        categories.asSequence()
            .map { it.label to cosine(imageEmbedding, it.embedding) }
            .filter { it.second >= threshold }
            .sortedByDescending { it.second }
            .map { TagCandidate(it.first, it.second) }
            .toList()

    private fun cosine(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}
