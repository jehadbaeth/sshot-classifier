package com.okapiorbits.sshotclassifier.pipeline

import android.net.Uri
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.entity.OcrEntryEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrFtsEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ProcessingStatus
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagSource
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1 per-screenshot pipeline: OCR -> persist text (+ FTS) -> OCR heuristic
 * tags -> mark DONE. CLIP embedding and zero-shot tagging arrive in Phase 2 and
 * will fuse with the heuristic tags here.
 */
@Singleton
class ImageProcessor @Inject constructor(
    private val dao: ScreenshotDao,
    private val ocr: OcrExtractor,
    private val heuristics: OcrHeuristics,
) {
    /** Returns true if the screenshot was processed, false if it failed. */
    suspend fun process(screenshot: ScreenshotEntity): Boolean {
        dao.updateStatus(screenshot.id, ProcessingStatus.PROCESSING.name, null)

        val result = ocr.extract(Uri.parse(screenshot.file_path))
        if (result == null) {
            dao.updateStatus(screenshot.id, ProcessingStatus.FAILED.name, System.currentTimeMillis())
            return false
        }

        dao.insertOcr(
            OcrEntryEntity(
                screenshot_id = screenshot.id,
                full_text = result.text,
                language = result.language,
            )
        )
        dao.insertFts(OcrFtsEntity(rowid = screenshot.id, text = result.text))

        // Re-tag idempotently: clear prior heuristic tags before writing new ones.
        dao.deleteTagsBySource(screenshot.id, TagSource.OCR_HEURISTIC.name)
        val tags = heuristics.classify(result.text).map {
            TagEntity(
                screenshot_id = screenshot.id,
                label = it.label,
                weight = it.weight,
                source = TagSource.OCR_HEURISTIC.name,
            )
        }
        if (tags.isNotEmpty()) dao.insertTags(tags)

        dao.updateStatus(screenshot.id, ProcessingStatus.DONE.name, System.currentTimeMillis())
        return true
    }
}
