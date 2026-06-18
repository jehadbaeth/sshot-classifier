package com.okapiorbits.sshotclassifier.pipeline


import android.net.Uri
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okapiorbits.sshotclassifier.data.db.AppDatabase
import com.okapiorbits.sshotclassifier.data.db.entity.ProcessingStatus
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.SourceType
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipEncoder
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipLabels
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipModelManager
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCache
import com.okapiorbits.sshotclassifier.pipeline.clip.TagFuser
import com.okapiorbits.sshotclassifier.pipeline.clip.ZeroShotClassifier
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * End-to-end check of the camera-capture half of the pipeline against the REAL
 * [ImageProcessor]: real ML Kit barcode decode + the structured describer, on a
 * real QR image, through a real on-disk Room DB. The CLIP image model is not
 * installed, so this exercises the OCR-only + barcode path (the QR tag is added
 * regardless of CLIP).
 *
 * Asserts the contract the feature promises for a scanned QR:
 *  - the raw payload is decoded and stored,
 *  - an authoritative "qr code" tag is attached,
 *  - the capture is NOT flagged needs-review (a decoded code is ground truth),
 *  - a description is composed that names the link host (not the full URL).
 */
@RunWith(AndroidJUnit4::class)
class CameraCapturePipelineTest {

    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private val workDir = File(appContext.cacheDir, "capture-test")

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        appContext.deleteDatabase("capture-test.db")
        workDir.deleteRecursively()
    }

    private fun materialize(assetName: String): File {
        workDir.mkdirs()
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val out = File(workDir, assetName)
        testAssets.open(assetName).use { input -> out.outputStream().use { input.copyTo(it) } }
        return out
    }

    private fun newProcessor(dao: com.okapiorbits.sshotclassifier.data.db.ScreenshotDao): ImageProcessor {
        val modelManager = ClipModelManager(appContext)
        return ImageProcessor(
            dao = dao,
            ocr = OcrExtractor(appContext, TesseractOcr(appContext)),
            heuristics = OcrHeuristics(),
            clipEncoder = ClipEncoder(appContext, modelManager),
            zeroShot = ZeroShotClassifier(ClipLabels(appContext)),
            fuser = TagFuser(),
            embeddingCache = EmbeddingCache(dao),
            barcode = BarcodeExtractor(appContext),
            describer = StructuredCaptureDescriber(),
        )
    }

    @Test
    fun labelSets_arePartitioned_soScreenshotCandidateSetIsUnchanged() {
        val labels = ClipLabels(appContext)
        // The screenshot scoring set must remain exactly the original 22 labels: this is
        // what keeps the validated screenshot eval unaffected by the 8 added real-world labels.
        assertEquals(30, labels.labels.size)
        assertEquals(22, labels.screenshotLabels.size)
        assertEquals(8, labels.realWorldLabels.size)
        assertTrue(
            "no real-world tag may leak into the screenshot candidate set",
            labels.screenshotLabels.none { it.tag in ClipLabels.REALWORLD_TAGS },
        )
    }

    @Test
    fun qrCapture_isDecoded_taggedAndDescribed() {
        val qr = materialize("qr_okapiorbits.png")
        db = Room.databaseBuilder(appContext, AppDatabase::class.java, "capture-test.db")
            .fallbackToDestructiveMigration()
            .build()
        val dao = db.screenshotDao()

        val id = runBlocking {
            dao.insert(
                ScreenshotEntity(
                    file_path = Uri.fromFile(qr).toString(),
                    file_hash = "qr-test-hash",
                    media_store_id = 999_001L,
                    date_added = 1L,
                    date_processed = null,
                    width = 492,
                    height = 492,
                    status = ProcessingStatus.PENDING.name,
                    source_type = SourceType.CAMERA.name,
                )
            )
        }

        val processor = newProcessor(dao)
        val ok = runBlocking { processor.process(dao.pending().first { it.id == id }) }
        assertTrue("processing should succeed", ok)

        val row = runBlocking { dao.observeScreenshot(id).first() }
        assertNotNull(row)
        assertEquals(
            "decoded payload should be stored verbatim",
            "https://www.okapiorbits.com/menu?ref=table12",
            row!!.qr_payload,
        )
        assertFalse("a decoded QR is ground truth, not needs-review", row.needs_review)
        assertEquals(ProcessingStatus.DONE.name, row.status)

        val tags = runBlocking { dao.observeTagsFor(id).first() }
        assertTrue(
            "should carry the authoritative qr code tag; got ${tags.map { it.label }}",
            tags.any { it.label == ImageProcessor.QR_TAG },
        )

        assertNotNull("camera capture should get a description", row.description)
        assertTrue(
            "description should name the link host; got \"${row.description}\"",
            row.description!!.contains("okapiorbits.com"),
        )
        // The full URL with tracking params must not be dumped into the prose description.
        assertFalse(row.description!!.contains("ref=table12"))
    }
}
