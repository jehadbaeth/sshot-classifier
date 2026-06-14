package com.okapiorbits.sshotclassifier.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A weighted tag attached to a screenshot. Multiple per image. The weight is the
 * softmax-normalized CLIP score so weights are comparable across tags of one image.
 */
@Entity(
    tableName = "tags",
    foreignKeys = [
        ForeignKey(
            entity = ScreenshotEntity::class,
            parentColumns = ["id"],
            childColumns = ["screenshot_id"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("screenshot_id"), Index("label")],
)
data class TagEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val screenshot_id: Long,
    val label: String,
    val weight: Float,
    val source: String,
)

enum class TagSource { CLIP_ZERO_SHOT, OCR_HEURISTIC, USER }
