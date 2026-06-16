package com.okapiorbits.sshotclassifier.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One row per indexed image. Identity is the content hash so the same image is
 * never processed twice even if it moves on disk.
 *
 * Despite the name (kept to avoid a wide rename), this is not screenshot-only:
 * [source_type] distinguishes auto-discovered screenshots from photos the user
 * captures in-app with the camera (the Phase A real-world inventory feature).
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
    /** True when auto-tagging was low-confidence/contradicted and wants a human look. */
    val needs_review: Boolean = false,
    /** Where this image came from: an auto-discovered screenshot or an in-app camera capture. */
    val source_type: String = SourceType.SCREENSHOT.name,
    /**
     * Human-readable description of a camera capture, composed from OCR text, the top
     * tags, and any decoded QR/barcode payload. Null for screenshots (the feature is
     * camera-only for now). See CaptureDescriber.
     */
    val description: String? = null,
    /** Raw decoded QR/barcode payload for a camera capture, if one was found. Null otherwise. */
    val qr_payload: String? = null,
    /** Resolved link-preview title for a QR URL (Phase B). Null until resolved. */
    val qr_title: String? = null,
    /** Resolved link-preview description for a QR URL. Null until resolved. */
    val qr_description: String? = null,
    /** Resolved og:image URL for a QR URL (stored as a string; only loaded if the user opts in). */
    val qr_image_url: String? = null,
    /** When the QR link was resolved (epoch millis), or null if never resolved. */
    val qr_resolved_at: Long? = null,
)

enum class ProcessingStatus { PENDING, PROCESSING, DONE, FAILED }

/** Origin of an indexed image. */
enum class SourceType { SCREENSHOT, CAMERA }
