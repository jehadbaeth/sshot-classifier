package com.okapiorbits.sshotclassifier.pipeline.clip

import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/** A screenshot id with its cosine similarity to the query embedding. */
data class SemanticHit(val screenshotId: Long, val score: Float)

/**
 * Free-text visual search: embeds the query with the CLIP text encoder and ranks
 * stored image embeddings by cosine similarity (brute force, fine up to ~20k
 * images per design 11). Both encoders share the same 512-d space, and stored
 * image vectors are already L2-normalized, so cosine reduces to a dot product.
 *
 * Returns an empty list when the text model is not installed or no embeddings
 * exist yet, so the caller can fall back to OCR full-text search.
 */
@Singleton
class SemanticSearcher @Inject constructor(
    private val dao: ScreenshotDao,
    private val textEncoder: ClipTextEncoder,
) {
    suspend fun search(query: String, limit: Int = 100): List<SemanticHit> =
        withContext(Dispatchers.Default) {
            if (query.isBlank() || !textEncoder.isReady()) return@withContext emptyList()
            val q = textEncoder.encode(query) ?: return@withContext emptyList()
            val rows = dao.allEmbeddings()
            if (rows.isEmpty()) return@withContext emptyList()

            rows.asSequence()
                .map { row ->
                    val v = EmbeddingCodec.toFloats(row.vector)
                    SemanticHit(row.screenshot_id, dot(q, v))
                }
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
