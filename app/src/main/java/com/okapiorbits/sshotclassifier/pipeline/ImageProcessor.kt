package com.okapiorbits.sshotclassifier.pipeline

import android.net.Uri
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.entity.EmbeddingEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrEntryEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrFtsEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ProcessingStatus
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.SourceType
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagSource
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipEncoder
import com.okapiorbits.sshotclassifier.pipeline.clip.CustomCategoryScorer
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCache
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCodec
import com.okapiorbits.sshotclassifier.pipeline.clip.TagFuser
import com.okapiorbits.sshotclassifier.pipeline.clip.ZeroShotClassifier
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Per-image pipeline: OCR -> persist text (+ FTS) -> OCR heuristic signals,
 * then (if the CLIP model is installed) image embedding -> zero-shot scores ->
 * fuse with OCR behind the margin gate -> weighted tags. Without CLIP it degrades
 * to OCR-only heuristic tags.
 *
 * Camera captures ([SourceType.CAMERA]) additionally run on-device QR/barcode
 * decoding and get a composed [CaptureDescriber] description. A decoded QR is
 * treated as ground truth (authoritative "qr code" tag, not needs-review). Both
 * steps are gated to camera captures so the screenshot path and its eval are
 * unchanged.
 */
@Singleton
class ImageProcessor @Inject constructor(
    private val dao: ScreenshotDao,
    private val ocr: OcrExtractor,
    private val heuristics: OcrHeuristics,
    private val clipEncoder: ClipEncoder,
    private val zeroShot: ZeroShotClassifier,
    private val fuser: TagFuser,
    private val embeddingCache: EmbeddingCache,
    private val barcode: BarcodeExtractor,
    private val describer: CaptureDescriber,
) {
    /**
     * @param decodeQrCodes whether camera captures are scanned for QR/barcodes (user
     *   preference, read once by the worker and passed in so this stays off the prefs/IO
     *   path and the existing test call sites keep working via the default).
     */
    suspend fun process(screenshot: ScreenshotEntity, decodeQrCodes: Boolean = true): Boolean {
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

        // Camera captures: decode QR/barcodes on-device (offline). Screenshots skip this
        // so their throughput and eval are unchanged.
        val isCamera = screenshot.source_type == SourceType.CAMERA.name
        val decoded = if (isCamera && decodeQrCodes) barcode.extract(uri) else null

        // CLIP path: image embedding + zero-shot, fused with OCR. Falls back to
        // OCR-only tags when the model is not installed or encoding fails.
        val embedding = if (clipEncoder.isReady()) clipEncoder.encode(uri) else null

        dao.deleteAutoTags(screenshot.id)
        val autoTags = mutableListOf<TagEntity>()
        var needsReview: Boolean
        if (embedding != null) {
            dao.insertEmbedding(EmbeddingEntity(screenshot_id = screenshot.id, vector = EmbeddingCodec.toBytes(embedding)))
            embeddingCache.invalidate() // the new vector must show up in search
            // Screenshots score against the original label set; camera captures also see
            // the real-world labels. This keeps the screenshot candidate set (and eval) intact.
            val clipScores = zeroShot.classify(embedding, includeRealWorld = isCamera)
            val decision = fuser.decide(fuser.fuse(clipScores, ocrCandidates))
            decision.tags.mapTo(autoTags) {
                TagEntity(screenshot_id = screenshot.id, label = it.label, weight = it.weight, source = TagSource.FUSED.name)
            }
            needsReview = decision.needsReview

            // User-defined auto-categories (additive; independent of the built-in tags).
            val categories = dao.allCategories().map {
                CustomCategoryScorer.Category(it.label, EmbeddingCodec.toFloats(it.embedding))
            }
            if (categories.isNotEmpty()) {
                CustomCategoryScorer.score(embedding, categories).mapTo(autoTags) {
                    TagEntity(screenshot_id = screenshot.id, label = it.label, weight = it.weight, source = TagSource.CUSTOM.name)
                }
            }
        } else {
            ocrCandidates.mapTo(autoTags) {
                TagEntity(screenshot_id = screenshot.id, label = it.label, weight = it.weight, source = TagSource.OCR_HEURISTIC.name)
            }
            // OCR-only (no visual model): only flag the truly untagged. The reprocess
            // banner separately nudges installing the model.
            needsReview = autoTags.isEmpty()
        }

        // A decoded QR/barcode is ground truth: there is definitely a code in the image.
        // Make "qr code" the authoritative top tag and clear needs-review.
        if (decoded != null) {
            if (autoTags.none { it.label == QR_TAG }) {
                autoTags.add(0, TagEntity(screenshot_id = screenshot.id, label = QR_TAG, weight = 1f, source = TagSource.FUSED.name))
            }
            needsReview = false
        }

        if (autoTags.isNotEmpty()) dao.insertTags(autoTags)

        // Camera captures get a composed, searchable description and the raw QR payload.
        if (isCamera) {
            val labels = autoTags.sortedByDescending { it.weight }.map { it.label }
            val description = describer.describe(
                CaptureContext(
                    ocrText = ocrResult.text,
                    tags = labels,
                    qrPayload = decoded?.rawValue,
                    qrIsUrl = decoded?.isUrl ?: false,
                )
            )
            dao.updateCaptureMeta(screenshot.id, description, decoded?.rawValue)
        }

        dao.updateNeedsReview(screenshot.id, needsReview)
        dao.updateStatus(screenshot.id, ProcessingStatus.DONE.name, System.currentTimeMillis())
        return true
    }

    companion object {
        /** Authoritative tag for an image containing a decoded QR/barcode. */
        const val QR_TAG = "qr code"
    }
}
