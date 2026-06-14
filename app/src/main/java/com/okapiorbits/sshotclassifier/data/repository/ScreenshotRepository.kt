package com.okapiorbits.sshotclassifier.data.repository

import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.ScreenshotWithTags
import com.okapiorbits.sshotclassifier.data.db.TagCount
import com.okapiorbits.sshotclassifier.data.db.entity.CustomCategoryEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagSource
import com.okapiorbits.sshotclassifier.data.media.ImageHasher
import com.okapiorbits.sshotclassifier.data.media.MediaStoreScanner
import com.okapiorbits.sshotclassifier.pipeline.clip.CustomCategoryScorer
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCodec
import com.okapiorbits.sshotclassifier.pipeline.clip.LabelEmbedder
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
    private val categoryEmbedder: LabelEmbedder,
) {
    fun observeGallery(): Flow<List<ScreenshotWithTags>> = dao.observeAllWithTags()

    fun observeByTag(label: String): Flow<List<ScreenshotWithTags>> = dao.observeByTag(label)

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observePendingCount(): Flow<Int> = dao.observeStatusCount()

    fun observeTagCounts(): Flow<List<TagCount>> = dao.observeTagCounts()

    /** Count of DONE screenshots lacking a CLIP image embedding (reprocess candidates). */
    fun observeReprocessableCount(): Flow<Int> = dao.observeReprocessableCount()

    /** Count of recorded reorganization moves available to undo. */
    fun observeReorgMoveCount(): Flow<Int> = dao.observeReorgMoveCount()

    /** Screenshots whose auto-tagging was uncertain and wants a human look. */
    fun observeNeedsReview(): Flow<List<ScreenshotWithTags>> = dao.observeNeedsReview()
    fun observeNeedsReviewCount(): Flow<Int> = dao.observeNeedsReviewCount()

    /** Resets DONE-but-unembedded screenshots to PENDING. Returns how many. */
    suspend fun markForReprocessing(): Int = dao.markMissingEmbeddingsForReprocessing()

    suspend fun pendingScreenshots(): List<ScreenshotEntity> = dao.pending()

    /** Reactive screenshot + its tags for the detail editor. */
    fun observeScreenshot(id: Long): Flow<ScreenshotEntity?> = dao.observeScreenshot(id)
    fun observeTags(screenshotId: Long): Flow<List<TagEntity>> = dao.observeTagsFor(screenshotId)

    /**
     * Adds a user-authored tag. Labels are trimmed and lowercased so they match
     * the auto-tag namespace and the search chips. No-ops (returns false) on a
     * blank label or an exact duplicate already present on the screenshot.
     */
    suspend fun addUserTag(screenshotId: Long, rawLabel: String): Boolean {
        val label = rawLabel.trim().lowercase()
        if (label.isEmpty()) return false
        if (dao.tagExists(screenshotId, label)) return false
        dao.insertTag(
            TagEntity(
                screenshot_id = screenshotId,
                label = label,
                weight = 1f,
                source = TagSource.USER.name,
            )
        )
        dao.updateNeedsReview(screenshotId, false) // a human edited it -> reviewed
        return true
    }

    /** Removes any single tag (user-added or a wrong auto tag); editing counts as reviewed. */
    suspend fun removeTag(tagId: Long, screenshotId: Long) {
        dao.deleteTag(tagId)
        dao.updateNeedsReview(screenshotId, false)
    }

    // ---- User-defined auto-tag categories ----

    fun observeCategories(): Flow<List<CustomCategoryEntity>> = dao.observeCategories()

    /** Whether categories can be created (needs the text model to embed labels). */
    fun canAddCategories(): Boolean = categoryEmbedder.isReady()

    sealed interface AddCategoryResult {
        /** Created and applied to [matched] existing screenshots. */
        data class Added(val matched: Int) : AddCategoryResult
        data object Blank : AddCategoryResult
        data object Duplicate : AddCategoryResult
        data object ModelMissing : AddCategoryResult
        data object EncodeFailed : AddCategoryResult
    }

    /**
     * Creates a custom auto-category: embeds the label on-device (prompt-ensembled),
     * stores it, then immediately scores it against every stored image embedding so
     * already-indexed screenshots get tagged right away. New screenshots are scored
     * later by [com.okapiorbits.sshotclassifier.pipeline.ImageProcessor].
     */
    suspend fun addCustomCategory(rawLabel: String): AddCategoryResult {
        val label = rawLabel.trim().lowercase()
        if (label.isEmpty()) return AddCategoryResult.Blank
        if (!categoryEmbedder.isReady()) return AddCategoryResult.ModelMissing
        if (dao.categoryExists(label)) return AddCategoryResult.Duplicate
        val embedding = categoryEmbedder.embed(label) ?: return AddCategoryResult.EncodeFailed

        dao.insertCategory(CustomCategoryEntity(label = label, embedding = EmbeddingCodec.toBytes(embedding)))

        val cat = listOf(CustomCategoryScorer.Category(label, embedding))
        var matched = 0
        for (row in dao.allEmbeddings()) {
            val hits = CustomCategoryScorer.score(EmbeddingCodec.toFloats(row.vector), cat)
            if (hits.isEmpty()) continue
            if (dao.tagExists(row.screenshot_id, label)) continue // don't duplicate an existing tag
            dao.insertTag(
                TagEntity(
                    screenshot_id = row.screenshot_id,
                    label = label,
                    weight = hits.first().weight,
                    source = TagSource.CUSTOM.name,
                )
            )
            matched++
        }
        return AddCategoryResult.Added(matched)
    }

    /** Deletes a custom category and removes the auto tags it produced. */
    suspend fun removeCustomCategory(id: Long, label: String) {
        dao.deleteCategory(id)
        dao.deleteTagsByLabelAndSource(label, TagSource.CUSTOM.name)
    }

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
