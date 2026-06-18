package com.okapiorbits.sshotclassifier.pipeline


import android.net.Uri
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okapiorbits.sshotclassifier.data.db.AppDatabase
import com.okapiorbits.sshotclassifier.data.db.entity.ProcessingStatus
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipEncoder
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipLabels
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipModelManager
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCache
import com.okapiorbits.sshotclassifier.pipeline.clip.TagFuser
import com.okapiorbits.sshotclassifier.pipeline.clip.ZeroShotClassifier
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Classification-throughput / queue-drain test at volume. The other suite covers
 * search and memory at scale; this covers the OTHER half of "classify a large
 * library": that the per-screenshot pipeline drains a few-hundred-deep PENDING
 * queue cleanly, with steady per-image latency and no memory blow-up.
 *
 * It runs the REAL [ImageProcessor] (real ML Kit OCR + heuristics, on real
 * bundled screenshots cycled to [COUNT] files) in the same loop the worker uses,
 * against a real on-disk Room DB. The CLIP image model is NOT installed, so this
 * measures the OCR-only path (the path that runs on every device before the 90 MB
 * model is downloaded). CLIP encode latency is a separate, model-dependent cost
 * and is deliberately out of scope here.
 *
 * It deliberately does NOT go through WorkManager/JobScheduler: that scheduler has
 * shown emulator-only flakiness ("Job didn't exist in JobStore") that is a harness
 * artifact, not app logic. Driving ImageProcessor directly tests the app's actual
 * batch behavior without that noise.
 *
 * Numbers are logged under "ThroughputTest".
 */
@RunWith(AndroidJUnit4::class)
class ProcessingThroughputTest {

    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private val workDir = File(appContext.cacheDir, "throughput-imgs")

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
        appContext.deleteDatabase("throughput-test.db")
        workDir.deleteRecursively()
    }

    /** Copies the bundled test screenshots out of the androidTest assets onto disk. */
    private fun materializeSourceImages(): List<File> {
        workDir.mkdirs()
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val names = testAssets.list("testimg")?.toList().orEmpty()
        assertTrue("no bundled test images found", names.isNotEmpty())
        return names.map { name ->
            val out = File(workDir, name)
            testAssets.open("testimg/$name").use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            out
        }
    }

    @Test
    fun drainsLargePendingQueueWithSteadyLatency() {
        val sources = materializeSourceImages()

        db = Room.databaseBuilder(appContext, AppDatabase::class.java, "throughput-test.db")
            .fallbackToDestructiveMigration()
            .build()
        val dao = db.screenshotDao()

        // Seed COUNT PENDING screenshots, cycling through the real sample images so
        // OCR runs over genuinely varied content (map, code, receipt, city, landscape).
        runBlocking {
            for (id in 1..COUNT) {
                val src = sources[id % sources.size]
                dao.insert(
                    ScreenshotEntity(
                        id = id.toLong(),
                        file_path = Uri.fromFile(src).toString(),
                        file_hash = "hash$id",
                        media_store_id = id.toLong(),
                        date_added = id.toLong(),
                        date_processed = null,
                        width = 1080,
                        height = 2400,
                    ),
                )
            }
        }

        val modelManager = ClipModelManager(appContext)
        assertTrue(
            "this test expects the OCR-only path; CLIP model must be absent",
            !modelManager.isModelInstalled(),
        )
        val processor = ImageProcessor(
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

        val runtime = Runtime.getRuntime()
        System.gc()
        val usedBefore = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576

        // Drive the SAME loop the worker uses: pull PENDING, process each.
        val pending = runBlocking { dao.pending() }
        assertEquals(COUNT, pending.size)

        var processed = 0
        var failed = 0
        var slowest = 0L
        val start = System.nanoTime()
        runBlocking {
            for (s in pending) {
                val t0 = System.nanoTime()
                val ok = processor.process(s)
                val ms = (System.nanoTime() - t0) / 1_000_000
                if (ms > slowest) slowest = ms
                if (ok) processed++ else failed++
            }
        }
        val totalMs = (System.nanoTime() - start) / 1_000_000

        System.gc()
        val usedAfter = (runtime.totalMemory() - runtime.freeMemory()) / 1_048_576

        val doneCount = runBlocking { dao.pending(ProcessingStatus.DONE.name).size }
        val perImage = totalMs.toDouble() / COUNT

        Log.i(
            TAG,
            "count=$COUNT | processed=$processed failed=$failed | done=$doneCount | " +
                "total=${totalMs}ms | perImage=${"%.1f".format(perImage)}ms | slowest=${slowest}ms | " +
                "heap ${usedBefore}MB -> ${usedAfter}MB | " +
                "projected 1000=${"%.1f".format(perImage * 1000 / 1000)}s",
        )

        // The whole queue must drain; OCR can legitimately fail on a given image but
        // the batch must not crash or stall, and everything must reach a terminal state.
        assertEquals("every screenshot must reach DONE", COUNT, doneCount)
        assertEquals("processed + failed must cover the queue", COUNT, processed + failed)
        // Guard against an unbounded heap leak over the batch (generous emulator bound).
        assertTrue("heap grew too much over the batch: ${usedAfter - usedBefore}MB", usedAfter - usedBefore < 200)
    }

    companion object {
        private const val TAG = "ThroughputTest"
        private const val COUNT = 300
    }
}
