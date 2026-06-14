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
    suspend fun insertOcr(entry: OcrEntryEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertFts(entry: OcrFtsEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertEmbedding(entry: EmbeddingEntity)

    @Query("DELETE FROM tags WHERE screenshot_id = :screenshotId AND source = :source")
    suspend fun deleteTagsBySource(screenshotId: Long, source: String)

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
}

data class TagCount(val label: String, val cnt: Int)
