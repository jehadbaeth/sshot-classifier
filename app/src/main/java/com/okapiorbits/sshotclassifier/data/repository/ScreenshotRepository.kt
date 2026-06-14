package com.okapiorbits.sshotclassifier.data.repository

import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.ScreenshotWithTags
import com.okapiorbits.sshotclassifier.data.db.TagCount
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.media.ImageHasher
import com.okapiorbits.sshotclassifier.data.media.MediaStoreScanner
import com.okapiorbits.sshotclassifier.pipeline.clip.SemanticSearcher
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotRepository @Inject constructor(
    private val dao: ScreenshotDao,
    private val scanner: MediaStoreScanner,
    private val hasher: ImageHasher,
    private val semanticSearcher: SemanticSearcher,
) {
    fun observeGallery(): Flow<List<ScreenshotWithTags>> = dao.observeAllWithTags()

    fun observeByTag(label: String): Flow<List<ScreenshotWithTags>> = dao.observeByTag(label)

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observePendingCount(): Flow<Int> = dao.observeStatusCount()

    fun observeTagCounts(): Flow<List<TagCount>> = dao.observeTagCounts()

    suspend fun pendingScreenshots(): List<ScreenshotEntity> = dao.pending()

    /** Full-text OCR search. Empty/blank query returns nothing. */
    fun search(query: String): Flow<List<ScreenshotWithTags>> {
        val fts = SearchFusion.toFtsPrefixQuery(query) ?: return flowOf(emptyList())
        return dao.searchByText(fts)
    }

    /**
     * Hybrid search: free-text visual search (CLIP text encoder -> cosine over
     * stored image embeddings) merged with OCR full-text matches via reciprocal
     * rank fusion. RRF avoids comparing the two incompatible score scales (CLIP
     * cosine ~0.2-0.35 vs a binary FTS hit) by fusing ranks, not raw scores.
     *
     * Degrades gracefully: if the text model is absent, visual ranking is empty
     * and this returns pure OCR results; if there are no OCR matches, it returns
     * pure visual results. Empty/blank query returns nothing.
     */
    suspend fun hybridSearch(query: String, limit: Int = 100): List<ScreenshotWithTags> {
        if (query.isBlank()) return emptyList()

        val visualRanking = semanticSearcher.search(query, limit).map { it.screenshotId }
        val textRanking = SearchFusion.toFtsPrefixQuery(query)
            ?.let { dao.searchIdsByText(it) }
            ?: emptyList()

        val fused = SearchFusion.reciprocalRankFusion(listOf(visualRanking, textRanking))
            .take(limit)
        if (fused.isEmpty()) return emptyList()

        val byId = dao.screenshotsByIds(fused).associateBy { it.screenshot.id }
        return SearchFusion.reorderTo(fused, byId)
    }

    /**
     * Discovers screenshots from MediaStore and inserts any not yet known (by hash).
     * Returns the number of newly indexed screenshots. ML processing happens later
     * in the pipeline; this phase only records existence.
     */
    suspend fun syncFromMediaStore(): Int {
        var inserted = 0
        for (found in scanner.queryScreenshots()) {
            val hash = hasher.sha256(found.uri) ?: continue
            if (dao.existsByHash(hash)) continue
            val rowId = dao.insert(
                ScreenshotEntity(
                    file_path = found.uri.toString(),
                    file_hash = hash,
                    media_store_id = found.mediaStoreId,
                    date_added = found.dateAdded,
                    date_processed = null,
                    width = found.width,
                    height = found.height,
                )
            )
            if (rowId != -1L) inserted++
        }
        return inserted
    }
}
