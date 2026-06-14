package com.okapiorbits.sshotclassifier.data.repository

import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.ScreenshotWithTags
import com.okapiorbits.sshotclassifier.data.db.TagCount
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.media.ImageHasher
import com.okapiorbits.sshotclassifier.data.media.MediaStoreScanner
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ScreenshotRepository @Inject constructor(
    private val dao: ScreenshotDao,
    private val scanner: MediaStoreScanner,
    private val hasher: ImageHasher,
) {
    fun observeGallery(): Flow<List<ScreenshotWithTags>> = dao.observeAllWithTags()

    fun observeByTag(label: String): Flow<List<ScreenshotWithTags>> = dao.observeByTag(label)

    fun observeCount(): Flow<Int> = dao.observeCount()

    fun observePendingCount(): Flow<Int> = dao.observeStatusCount()

    fun observeTagCounts(): Flow<List<TagCount>> = dao.observeTagCounts()

    suspend fun pendingScreenshots(): List<ScreenshotEntity> = dao.pending()

    /** Full-text OCR search. Empty/blank query returns nothing. */
    fun search(query: String): Flow<List<ScreenshotWithTags>> {
        val fts = toFtsPrefixQuery(query) ?: return flowOf(emptyList())
        return dao.searchByText(fts)
    }

    /**
     * Turns free user text into a safe FTS4 MATCH expression: strip special
     * characters, then prefix-match each token (AND semantics). Returns null if
     * nothing usable remains.
     */
    private fun toFtsPrefixQuery(raw: String): String? {
        val tokens = raw.lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
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
