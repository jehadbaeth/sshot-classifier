package com.okapiorbits.sshotclassifier.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** Extracted OCR text for one screenshot. Mirrored into an FTS table for search. */
@Entity(
    tableName = "ocr_entries",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["screenshot_id"], unique = true)],
)
data class OcrEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val screenshot_id: Long,
    val full_text: String,
    val language: String?,
)
