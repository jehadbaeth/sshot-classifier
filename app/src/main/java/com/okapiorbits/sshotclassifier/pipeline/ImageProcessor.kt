package com.okapiorbits.sshotclassifier.pipeline

import android.net.Uri
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.entity.EmbeddingEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrEntryEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrFtsEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ProcessingStatus
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagSource
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipEncoder
import com.okapiorbits.sshotclassifier.pipeline.clip.CustomCategoryScorer
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCodec
import com.okapiorbits.sshotclassifier.pipeline.clip.TagFuser
import com.okapiorbits.sshotclassifier.pipeline.clip.ZeroShotClassifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-screenshot pipeline: OCR -> persist text (+ FTS) -> OCR heuristic signals,
 * then (if the CLIP model is installed) image embedding -> zero-shot scores ->
 * fuse with OCR behind the margin gate -> weighted tags. Without CLIP it degrades
 * to OCR-only heuristic tags.
 */
@Singleton
class ImageProcessor @Inject constructor(
    private val dao: ScreenshotDao,
    private val ocr: OcrExtractor,
    private val heuristics: OcrHeuristics,
    private val clipEncoder: ClipEncoder,
    private val zeroShot: ZeroShotClassifier,
    private val fuser: TagFuser,
) {
    suspend fun process(screenshot: ScreenshotEntity): Boolean {
        dao.updateStatus(screenshot.id, ProcessingStatus.PROCESSING.name, null)
        val uri = Uri.parse(screenshot.file_path)

        val ocrResult = ocr.extract(uri)
        if (ocrResult == null) {
            dao.updateStatus(screenshot.id, ProcessingStatus.FAILED.name, System.currentTimeMillis())
            return false
        }

        dao.insertOcr(OcrEntryEntity(screenshot_id = screenshot.id, full_text = ocrResult.text, language = ocrResult.language))
        dao.insertFts(OcrFtsEntity(rowid = screenshot.id, text = ocrResult.text))

        val ocrCandidates = heuristics.classify(ocrResult.text)

        // CLIP path: image embedding + zero-shot, fused with OCR. Falls back to
        // OCR-only tags when the model is not installed or encoding fails.
        val embedding = if (clipEncoder.isReady()) clipEncoder.encode(uri) else null

        dao.deleteAutoTags(screenshot.id)
        var needsReview: Boolean
        if (embedding != null) {
            dao.insertEmbedding(EmbeddingEntity(screenshot_id = screenshot.id, vector = EmbeddingCodec.toBytes(embedding)))
            val clipScores = zeroShot.classify(embedding)
            val fused = fuser.fuse(clipScores, ocrCandidates)
            val tags = fused.tags.map {
                TagEntity(screenshot_id = screenshot.id, label = it.label, weight = it.weight, source = TagSource.FUSED.name)
            }
            if (tags.isNotEmpty()) dao.insertTags(tags)

            // Flag for human review when tagging is weak: nothing stuck, the top tag
            // failed the margin/OCR gate, or it only landed on the catch-all "other".
            needsReview = tags.isEmpty() || !fused.primaryConfident || tags.first().label == "other"

            // User-defined auto-categories (additive; independent of the built-in tags).
            val categories = dao.allCategories().map {
                CustomCategoryScorer.Category(it.label, EmbeddingCodec.toFloats(it.embedding))
            }
            if (categories.isNotEmpty()) {
                val customTags = CustomCategoryScorer.score(embedding, categories).map {
                    TagEntity(screenshot_id = screenshot.id, label = it.label, weight = it.weight, source = TagSource.CUSTOM.name)
                }
                if (customTags.isNotEmpty()) dao.insertTags(customTags)
            }
        } else {
            val tags = ocrCandidates.map {
                TagEntity(screenshot_id = screenshot.id, label = it.label, weight = it.weight, source = TagSource.OCR_HEURISTIC.name)
            }
            if (tags.isNotEmpty()) dao.insertTags(tags)
            // OCR-only (no visual model): only flag the truly untagged. The reprocess
            // banner separately nudges installing the model.
            needsReview = tags.isEmpty()
        }

        dao.updateNeedsReview(screenshot.id, needsReview)
        dao.updateStatus(screenshot.id, ProcessingStatus.DONE.name, System.currentTimeMillis())
        return true
    }
}
