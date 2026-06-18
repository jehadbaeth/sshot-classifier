package com.okapiorbits.sshotclassifier.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.okapiorbits.sshotclassifier.data.db.entity.CustomCategoryEntity
import com.okapiorbits.sshotclassifier.data.db.entity.EmbeddingEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrEntryEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrFtsEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ProcessingStatus
import com.okapiorbits.sshotclassifier.data.db.entity.ReorgMoveEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import kotlinx.coroutines.flow.Flow

/** A screenshot joined with its tags, for gallery display. */
data class ScreenshotWithTags(
    @Embedded val screenshot: ScreenshotEntity,
    @Relation(parentColumn = "id", entityColumn = "screenshot_id")
    val tags: List<TagEntity>,
)

@Dao
interface ScreenshotDao {

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(screenshot: ScreenshotEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTags(tags: List<TagEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTag(tag: TagEntity): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOcr(entry: OcrEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entry: OcrFtsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(entry: EmbeddingEntity)

    @Query("DELETE FROM tags WHERE screenshot_id = :screenshotId AND source = :source")
    suspend fun deleteTagsBySource(screenshotId: Long, source: String)

    /** Tags on one screenshot, weightiest first, for the detail editor. */
    @Query("SELECT * FROM tags WHERE screenshot_id = :screenshotId ORDER BY weight DESC, label ASC")
    fun observeTagsFor(screenshotId: Long): Flow<List<TagEntity>>

    /** Extracted OCR text for one screenshot (null until processed / if none), for the detail view. */
    @Query("SELECT full_text FROM ocr_entries WHERE screenshot_id = :screenshotId LIMIT 1")
    fun observeOcrText(screenshotId: Long): Flow<String?>

    /** True if a tag with this exact label already exists on the screenshot (any source). */
    @Query("SELECT EXISTS(SELECT 1 FROM tags WHERE screenshot_id = :screenshotId AND label = :label)")
    suspend fun tagExists(screenshotId: Long, label: String): Boolean

    /** Removes a single tag by row id (used to delete a user or a wrong auto tag). */
    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    /** One screenshot by id (for the detail view); null if it was removed. */
    @Query("SELECT * FROM screenshots WHERE id = :id")
    fun observeScreenshot(id: Long): Flow<ScreenshotEntity?>

    /** One-shot fetch by id (for QR link resolution); null if it was removed. */
    @Query("SELECT * FROM screenshots WHERE id = :id")
    suspend fun getById(id: Long): ScreenshotEntity?

    /** Stores a resolved QR link preview on a capture. */
    @Query(
        """
        UPDATE screenshots
        SET qr_title = :title, qr_description = :description, qr_image_url = :imageUrl, qr_resolved_at = :resolvedAt
        WHERE id = :id
        """
    )
    suspend fun updateLinkPreview(id: Long, title: String?, description: String?, imageUrl: String?, resolvedAt: Long)

    /** Removes all tags with a given label and source (used when a custom category is deleted). */
    @Query("DELETE FROM tags WHERE label = :label AND source = :source")
    suspend fun deleteTagsByLabelAndSource(label: String, source: String)

    // ---- User-defined auto-tag categories ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertCategory(category: CustomCategoryEntity): Long

    @Query("SELECT * FROM custom_categories ORDER BY label ASC")
    fun observeCategories(): Flow<List<CustomCategoryEntity>>

    @Query("SELECT * FROM custom_categories")
    suspend fun allCategories(): List<CustomCategoryEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM custom_categories WHERE label = :label)")
    suspend fun categoryExists(label: String): Boolean

    @Query("DELETE FROM custom_categories WHERE id = :id")
    suspend fun deleteCategory(id: Long)

    @Query("DELETE FROM tags WHERE screenshot_id = :screenshotId AND source != 'USER'")
    suspend fun deleteAutoTags(screenshotId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM screenshots WHERE file_hash = :hash)")
    suspend fun existsByHash(hash: String): Boolean

    /** Screenshot id for a content hash, or null. Used to re-attach imported user tags. */
    @Query("SELECT id FROM screenshots WHERE file_hash = :hash")
    suspend fun idByHash(hash: String): Long?

    /**
     * User-authored tags joined to their image content hash, for backup/export. Auto tags
     * (FUSED/CLIP/OCR/CUSTOM) are re-derivable by re-scanning, so only USER tags are exported;
     * the hash (not the row id, which is unstable across reinstall) re-attaches them on import.
     */
    @Query(
        """
        SELECT s.file_hash AS fileHash, t.label AS label
        FROM tags t JOIN screenshots s ON s.id = t.screenshot_id
        WHERE t.source = 'USER'
        """
    )
    suspend fun userTagsForExport(): List<UserTagExport>

    @Query("UPDATE screenshots SET status = :status, date_processed = :processedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, processedAt: Long?)

    @Query("UPDATE screenshots SET needs_review = :needsReview WHERE id = :id")
    suspend fun updateNeedsReview(id: Long, needsReview: Boolean)

    /** Stores the composed description and decoded QR/barcode payload for a camera capture. */
    @Query("UPDATE screenshots SET description = :description, qr_payload = :qrPayload WHERE id = :id")
    suspend fun updateCaptureMeta(id: Long, description: String?, qrPayload: String?)

    @Query("SELECT COUNT(*) FROM screenshots WHERE needs_review = 1")
    fun observeNeedsReviewCount(): Flow<Int>

    @Transaction
    @Query("SELECT * FROM screenshots WHERE needs_review = 1 ORDER BY date_added DESC")
    fun observeNeedsReview(): Flow<List<ScreenshotWithTags>>

    @Query("SELECT * FROM screenshots WHERE status = :status ORDER BY date_added ASC")
    suspend fun pending(status: String = ProcessingStatus.PENDING.name): List<ScreenshotEntity>

    @Query("SELECT COUNT(*) FROM screenshots WHERE status = :status")
    fun observeStatusCount(status: String = ProcessingStatus.PENDING.name): Flow<Int>

    @Transaction
    @Query("SELECT * FROM screenshots ORDER BY date_added DESC")
    fun observeAllWithTags(): Flow<List<ScreenshotWithTags>>

    /** Snapshot of processed screenshots with tags, for the reorganization pass. */
    @Transaction
    @Query("SELECT * FROM screenshots WHERE status = 'DONE' ORDER BY date_added DESC")
    suspend fun doneWithTags(): List<ScreenshotWithTags>

    @Transaction
    @Query(
        """
        SELECT s.* FROM screenshots s
        INNER JOIN tags t ON t.screenshot_id = s.id
        WHERE t.label = :label
        ORDER BY t.weight DESC
        """
    )
    fun observeByTag(label: String): Flow<List<ScreenshotWithTags>>

    @Query("SELECT COUNT(*) FROM screenshots")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM screenshots WHERE source_type = :source")
    fun observeSourceCount(source: String): Flow<Int>

    /**
     * Full-text search over OCR text. The FTS rowid equals the screenshot id.
     * Ranked by recency for now (FTS4 has no bm25). The caller passes an FTS
     * MATCH expression (see SearchRepository for query sanitization).
     */
    @Transaction
    @Query(
        """
        SELECT s.* FROM screenshots s
        JOIN ocr_fts ON ocr_fts.rowid = s.id
        WHERE ocr_fts.text MATCH :ftsQuery
        ORDER BY s.date_added DESC
        """
    )
    fun searchByText(ftsQuery: String): Flow<List<ScreenshotWithTags>>

    @Query("SELECT label, COUNT(*) AS cnt FROM tags GROUP BY label ORDER BY cnt DESC")
    fun observeTagCounts(): Flow<List<TagCount>>

    /** All stored image embeddings, for in-memory brute-force cosine search. */
    @Query("SELECT screenshot_id, vector FROM embeddings")
    suspend fun allEmbeddings(): List<EmbeddingRow>

    /**
     * Screenshots already processed (DONE) but with no image embedding. These were
     * tagged before the CLIP model was installed, so they have OCR-only tags and
     * are invisible to visual search. Drives the "reprocess" affordance.
     */
    @Query(
        """
        SELECT COUNT(*) FROM screenshots
        WHERE status = 'DONE' AND id NOT IN (SELECT screenshot_id FROM embeddings)
        """
    )
    fun observeReprocessableCount(): Flow<Int>

    /**
     * Resets DONE-but-unembedded screenshots back to PENDING so the processing
     * worker re-runs them through the full (now CLIP-capable) pipeline. The
     * pipeline is idempotent (OCR REPLACE, deleteAutoTags before re-tagging).
     * Returns the number of rows reset.
     */
    @Query(
        """
        UPDATE screenshots SET status = 'PENDING'
        WHERE status = 'DONE' AND id NOT IN (SELECT screenshot_id FROM embeddings)
        """
    )
    suspend fun markMissingEmbeddingsForReprocessing(): Int

    /** OCR full-text match -> screenshot ids, recency-ordered, for hybrid fusion. */
    @Query(
        """
        SELECT s.id FROM screenshots s
        JOIN ocr_fts ON ocr_fts.rowid = s.id
        WHERE ocr_fts.text MATCH :ftsQuery
        ORDER BY s.date_added DESC
        """
    )
    suspend fun searchIdsByText(ftsQuery: String): List<Long>

    /** Fetch screenshots (with tags) by id; caller re-orders to the ranked sequence. */
    @Transaction
    @Query("SELECT * FROM screenshots WHERE id IN (:ids)")
    suspend fun screenshotsByIds(ids: List<Long>): List<ScreenshotWithTags>

    /** Repoints a screenshot at a new file uri (after a move or an undo-restore). */
    @Query("UPDATE screenshots SET file_path = :uri WHERE id = :id")
    suspend fun updateFilePath(id: Long, uri: String)

    // ---- Reorganization undo log (records of moved files) ----

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertReorgMove(move: ReorgMoveEntity): Long

    @Query("SELECT * FROM reorg_moves ORDER BY moved_at DESC")
    suspend fun allReorgMoves(): List<ReorgMoveEntity>

    @Query("SELECT COUNT(*) FROM reorg_moves")
    fun observeReorgMoveCount(): Flow<Int>

    @Query("DELETE FROM reorg_moves WHERE id = :id")
    suspend fun deleteReorgMove(id: Long)

    @Query("DELETE FROM reorg_moves")
    suspend fun clearReorgMoves()
}

data class TagCount(val label: String, val cnt: Int)

/** One exported user tag: the image's content hash plus the label. */
data class UserTagExport(val fileHash: String, val label: String)

/** Lightweight projection of an embedding row (no entity id) for search. */
data class EmbeddingRow(val screenshot_id: Long, val vector: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingRow) return false
        return screenshot_id == other.screenshot_id && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = 31 * screenshot_id.hashCode() + vector.contentHashCode()
}
