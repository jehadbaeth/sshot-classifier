package com.okapiorbits.sshotclassifier.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Relation
import androidx.room.Transaction
import com.okapiorbits.sshotclassifier.data.db.entity.EmbeddingEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrEntryEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrFtsEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ProcessingStatus
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

    /** True if a tag with this exact label already exists on the screenshot (any source). */
    @Query("SELECT EXISTS(SELECT 1 FROM tags WHERE screenshot_id = :screenshotId AND label = :label)")
    suspend fun tagExists(screenshotId: Long, label: String): Boolean

    /** Removes a single tag by row id (used to delete a user or a wrong auto tag). */
    @Query("DELETE FROM tags WHERE id = :tagId")
    suspend fun deleteTag(tagId: Long)

    /** One screenshot by id (for the detail view); null if it was removed. */
    @Query("SELECT * FROM screenshots WHERE id = :id")
    fun observeScreenshot(id: Long): Flow<ScreenshotEntity?>

    @Query("DELETE FROM tags WHERE screenshot_id = :screenshotId AND source != 'USER'")
    suspend fun deleteAutoTags(screenshotId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM screenshots WHERE file_hash = :hash)")
    suspend fun existsByHash(hash: String): Boolean

    @Query("UPDATE screenshots SET status = :status, date_processed = :processedAt WHERE id = :id")
    suspend fun updateStatus(id: Long, status: String, processedAt: Long?)

    @Query("SELECT * FROM screenshots WHERE status = :status ORDER BY date_added ASC")
    suspend fun pending(status: String = ProcessingStatus.PENDING.name): List<ScreenshotEntity>

    @Query("SELECT COUNT(*) FROM screenshots WHERE status = :status")
    fun observeStatusCount(status: String = ProcessingStatus.PENDING.name): Flow<Int>

    @Transaction
    @Query("SELECT * FROM screenshots ORDER BY date_added DESC")
    fun observeAllWithTags(): Flow<List<ScreenshotWithTags>>

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
}

data class TagCount(val label: String, val cnt: Int)

/** Lightweight projection of an embedding row (no entity id) for search. */
data class EmbeddingRow(val screenshot_id: Long, val vector: ByteArray) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is EmbeddingRow) return false
        return screenshot_id == other.screenshot_id && vector.contentEquals(other.vector)
    }

    override fun hashCode(): Int = 31 * screenshot_id.hashCode() + vector.contentHashCode()
}
