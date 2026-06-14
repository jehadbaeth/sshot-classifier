package com.okapiorbits.sshotclassifier.data.repository

import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okapiorbits.sshotclassifier.data.db.AppDatabase
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.entity.EmbeddingEntity
import com.okapiorbits.sshotclassifier.data.db.entity.OcrFtsEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.media.ImageHasher
import com.okapiorbits.sshotclassifier.data.media.MediaStoreScanner
import com.okapiorbits.sshotclassifier.data.media.Reorganization
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCache
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCodec
import com.okapiorbits.sshotclassifier.pipeline.clip.LabelEmbedder
import com.okapiorbits.sshotclassifier.pipeline.clip.SemanticSearcher
import com.okapiorbits.sshotclassifier.pipeline.clip.TextEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Random
import kotlin.math.sqrt

/**
 * Scale / load test for the search and reorg paths at realistic library sizes
 * (500 .. 10k screenshots). The whole point of the app is classifying a LARGE
 * library, but the rest of the suite only seeds a handful of rows. This drives
 * the REAL on-disk Room database (so SQLite blob I/O is real, not in-memory) and
 * the REAL [SemanticSearcher] / [ScreenshotRepository.hybridSearch] with a fake
 * text embedder (no 65 MB model needed).
 *
 * It measures, per size:
 *   - bulk seed time,
 *   - the brute-force cosine search latency (design sec 12 target: < 50 ms),
 *   - full hybrid (visual + OCR FTS + RRF) latency,
 *   - the in-memory footprint of the loaded vectors (target: ~20 MB at 10k),
 *   - reorg album-assignment throughput.
 *
 * Numbers are logged under the tag "ScaleTest" so they can be read off logcat for
 * the report; assertions use generous emulator-safe ceilings and only hard-fail on
 * gross regressions (search an order of magnitude over target, or wrong results).
 */
@RunWith(AndroidJUnit4::class)
class ScaleInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private var db: AppDatabase? = null

    @After
    fun tearDown() {
        db?.close()
    }

    /** A fixed, L2-normalized query vector so cosine actually does work. */
    private class FakeEmbedder(private val q: FloatArray) : TextEmbedder {
        override fun isReady() = true
        override fun encode(text: String): FloatArray = q
    }

    private val noLabelEmbedder = object : LabelEmbedder {
        override fun isReady() = false
        override fun embed(label: String): FloatArray? = null
    }

    private fun freshOnDiskDb(): AppDatabase {
        // A unique on-disk DB per size so SQLite I/O is real (not in-memory).
        context.deleteDatabase("scale-test.db")
        return Room.databaseBuilder(context, AppDatabase::class.java, "scale-test.db")
            .fallbackToDestructiveMigration()
            .build()
            .also { db = it }
    }

    private fun randomUnitVector(rnd: Random): FloatArray {
        val v = FloatArray(DIM) { (rnd.nextFloat() * 2f - 1f) }
        var norm = 0f
        for (x in v) norm += x * x
        norm = sqrt(norm)
        if (norm > 0f) for (i in v.indices) v[i] /= norm
        return v
    }

    /** A handful of OCR phrases so FTS has realistic content to match. */
    private val ocrPhrases = listOf(
        "grocery receipt total amount due",
        "kotlin coroutines structured concurrency guide",
        "city skyline at sunset photo",
        "weekend hiking trail map directions",
        "monthly budget spreadsheet expenses",
        "boarding pass gate departure time",
        "chat message conversation thread",
        "settings screen notifications toggle",
    )

    @Test
    fun searchAndReorgScaleWithLibrarySize() {
        // 10k is the design's brute-force ceiling; include it to prove the claim.
        for (n in intArrayOf(500, 1000, 2000, 5000, 10_000)) {
            val dao = freshOnDiskDb().screenshotDao()
            val rnd = Random(42L + n) // deterministic per size

            // ---- seed ----
            val seedMs = measure {
                runBlocking {
                    for (id in 1..n) {
                        dao.insert(
                            ScreenshotEntity(
                                id = id.toLong(),
                                file_path = "file://$id",
                                file_hash = "hash$id",
                                media_store_id = id.toLong(),
                                date_added = id.toLong(),
                                date_processed = id.toLong(),
                                width = 1080,
                                height = 2400,
                            ),
                        )
                        dao.insertFts(OcrFtsEntity(rowid = id.toLong(), text = ocrPhrases[id % ocrPhrases.size]))
                        val v = randomUnitVector(rnd)
                        dao.insertEmbedding(EmbeddingEntity(screenshot_id = id.toLong(), vector = EmbeddingCodec.toBytes(v)))
                    }
                }
            }

            assertEquals(n, runBlocking { dao.allEmbeddings().size })

            val query = randomUnitVector(Random(7L))
            val cache = EmbeddingCache(dao)
            val searcher = SemanticSearcher(cache, FakeEmbedder(query))
            val repo = ScreenshotRepository(
                dao = dao,
                scanner = MediaStoreScanner(context),
                hasher = ImageHasher(context),
                semanticSearcher = searcher,
                categoryEmbedder = noLabelEmbedder,
            )

            // ---- warm up (first query pays JIT + page cache) ----
            runBlocking { searcher.search("warmup") }

            // ---- COLD: first query after an embedding change rebuilds the cache ----
            cache.invalidate()
            val coldMs = measure { runBlocking { searcher.search("cold") } }

            // ---- WARM: repeat queries reuse the cached decoded vectors ----
            var visualResults = 0
            val visualMs = measureAvg(REPEATS) {
                runBlocking { visualResults = searcher.search("query").size }
            }

            // ---- full hybrid (visual + OCR FTS + RRF + row hydration) latency ----
            var hybridResults = 0
            val hybridMs = measureAvg(REPEATS) {
                runBlocking { hybridResults = repo.hybridSearch("budget").size }
            }

            // ---- vector memory footprint actually held in RAM during a search ----
            val vectorBytes = runBlocking {
                dao.allEmbeddings().sumOf { it.vector.size.toLong() }
            }

            // ---- reorg album assignment throughput (the pure routing decision) ----
            val labels = listOf("map", "code editor", "receipt", "chat / messaging", "other", "")
            var albums = 0
            val reorgMs = measure {
                for (id in 1..n) {
                    val label = labels[id % labels.size]
                    Reorganization.albumFor(needsReview = id % 7 == 0, topLabel = label)
                    albums++
                }
            }

            Log.i(
                TAG,
                "n=$n | seed=${seedMs}ms | cold=${coldMs}ms | warm=${"%.2f".format(visualMs)}ms (${visualResults} hits) " +
                    "| hybrid=${"%.2f".format(hybridMs)}ms (${hybridResults} hits) " +
                    "| vectors=${vectorBytes / 1024}KB (${"%.1f".format(vectorBytes / 1_048_576.0)}MB) " +
                    "| reorgRoute=${reorgMs}ms for $albums",
            )

            // ---- assertions: emulator-safe ceilings, hard-fail only on gross regression ----
            assertTrue("visual search returned nothing at n=$n", visualResults > 0)
            assertTrue("hybrid search returned nothing at n=$n", hybridResults > 0)
            // Brute force over L2-normalized vectors must stay well under a generous
            // emulator ceiling. Target is 50 ms; allow 10x headroom for emulator noise.
            assertTrue("visual search too slow at n=$n: ${visualMs}ms", visualMs < 500.0)

            db?.close()
            db = null
        }
    }

    private inline fun measure(block: () -> Unit): Long {
        val start = System.nanoTime()
        block()
        return (System.nanoTime() - start) / 1_000_000
    }

    private inline fun measureAvg(repeats: Int, block: () -> Unit): Double {
        val start = System.nanoTime()
        repeat(repeats) { block() }
        return (System.nanoTime() - start) / 1_000_000.0 / repeats
    }

    companion object {
        private const val TAG = "ScaleTest"
        private const val DIM = 512
        private const val REPEATS = 20
    }
}
