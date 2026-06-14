package com.okapiorbits.sshotclassifier.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per discovered screenshot. Identity is the content hash so the same
 * image is never processed twice even if it moves on disk.
 */
@Entity(
    tableName = "screenshots",
    indices = [
        Index(value = ["file_hash"], unique = true),
        Index(value = ["media_store_id"], unique = true),
        Index(value = ["date_added"]),
    ],
)
data class ScreenshotEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val file_path: String,
    val file_hash: String,
    val media_store_id: Long,
    val date_added: Long,
    val date_processed: Long?,
    val width: Int,
    val height: Int,
    /** Processing lifecycle: PENDING -> PROCESSING -> DONE / FAILED. */
    val status: String = ProcessingStatus.PENDING.name,
)

enum class ProcessingStatus { PENDING, PROCESSING, DONE, FAILED }
