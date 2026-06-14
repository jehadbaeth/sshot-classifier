package com.okapiorbits.sshotclassifier.pipeline.clip

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A screenshot id with its cosine similarity to the query embedding. */
data class SemanticHit(val screenshotId: Long, val score: Float)

/**
 * Free-text visual search: embeds the query with the CLIP text encoder and ranks
 * stored image embeddings by cosine similarity. Both encoders share the same 512-d
 * space, and stored image vectors are already L2-normalized, so cosine reduces to a
 * dot product.
 *
 * Returns an empty list when the text model is not installed or no embeddings
 * exist yet, so the caller can fall back to OCR full-text search.
 *
 * Scaling (measured 2026-06-14, docs/spikes/scale-test.md): the brute-force cost was
 * never the dot products but re-decoding every embedding blob from SQLite per query
 * (linear, ~126 ms at 10k). The decoded vectors now live in [EmbeddingCache], so only
 * the first query after an embedding change pays that; repeats are near-instant.
 */
@Singleton
class SemanticSearcher @Inject constructor(
    private val embeddings: EmbeddingCache,
    private val textEncoder: TextEmbedder,
) {
    suspend fun search(query: String, limit: Int = 100): List<SemanticHit> =
        withContext(Dispatchers.Default) {
            if (query.isBlank() || !textEncoder.isReady()) return@withContext emptyList()
            val q = textEncoder.encode(query) ?: return@withContext emptyList()
            val rows = embeddings.entries()
            if (rows.isEmpty()) return@withContext emptyList()

            rows.asSequence()
                .map { SemanticHit(it.screenshotId, dot(q, it.vector)) }
                .sortedByDescending { it.score }
                .take(limit)
                .toList()
        }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}
