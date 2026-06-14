package com.okapiorbits.sshotclassifier.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A user-defined auto-tag category. The label is the tag applied to matching
 * screenshots; the embedding is its prompt-ensembled CLIP text vector (512
 * float32, little-endian blob) computed on-device when the category is created.
 * Screenshots whose image embedding has cosine >= threshold with this vector get
 * tagged (source = CUSTOM). Independent of the built-in label set.
 */
@Entity(
    tableName = "custom_categories",
    indices = [Index(value = ["label"], unique = true)],
)
data class CustomCategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val label: String,
    val embedding: ByteArray,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CustomCategoryEntity) return false
        return id == other.id && label == other.label && embedding.contentEquals(other.embedding)
    }

    override fun hashCode(): Int {
        var result = id.hashCode()
        result = 31 * result + label.hashCode()
        result = 31 * result + embedding.contentHashCode()
        return result
    }
}
