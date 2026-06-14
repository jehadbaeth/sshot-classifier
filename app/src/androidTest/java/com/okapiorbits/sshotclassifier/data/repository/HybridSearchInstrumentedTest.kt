package com.okapiorbits.sshotclassifier.data.repository

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
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCache
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCodec
import com.okapiorbits.sshotclassifier.pipeline.clip.LabelEmbedder
import com.okapiorbits.sshotclassifier.pipeline.clip.SemanticSearcher
import com.okapiorbits.sshotclassifier.pipeline.clip.TextEmbedder
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Drives the REAL Room database (FTS4 + the `WHERE id IN (...)` join) through
 * [ScreenshotRepository.hybridSearch], with a fake [TextEmbedder] so no 65 MB
 * TFLite model is needed. This is the on-device coverage the CLIP instrumented
 * test could not give: it proves the DAO SQL, the rank fusion, the result
 * reordering, and the graceful OCR-only degradation actually work end to end.
 */
@RunWith(AndroidJUnit4::class)
class HybridSearchInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: ScreenshotDao

    /** Returns a fixed unit vector pointing at the given axis, or null when "not ready". */
    private class FakeEmbedder(private val ready: Boolean, private val axis: Int?) : TextEmbedder {
        override fun isReady() = ready
        override fun encode(text: String): FloatArray? {
            if (!ready || axis == null) return null
            return FloatArray(512).also { it[axis] = 1f }
        }
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.screenshotDao()
    }

    @After
    fun tearDown() = db.close()

    private val noLabelEmbedder = object : LabelEmbedder {
        override fun isReady() = false
        override fun embed(label: String): FloatArray? = null
    }

    private fun repo(embedder: TextEmbedder) = ScreenshotRepository(
        dao = dao,
        scanner = MediaStoreScanner(context),
        hasher = ImageHasher(context),
        semanticSearcher = SemanticSearcher(EmbeddingCache(dao), embedder),
        categoryEmbedder = noLabelEmbedder,
        watchedFoldersStore = com.okapiorbits.sshotclassifier.data.prefs.WatchedFoldersStore(context),
    )

    /** Inserts a screenshot with OCR text and an embedding aligned to [axis]. */
    private fun seed(id: Long, ocr: String, axis: Int) = runBlocking {
        dao.insert(
            ScreenshotEntity(
                id = id,
                file_path = "file://$id",
                file_hash = "hash$id",
                media_store_id = id,
                date_added = id, // higher id = more recent
                date_processed = id,
                width = 100,
                height = 100,
            ),
        )
        dao.insertFts(OcrFtsEntity(rowid = id, text = ocr))
        val v = FloatArray(512).also { it[axis] = 1f }
        dao.insertEmbedding(EmbeddingEntity(screenshot_id = id, vector = EmbeddingCodec.toBytes(v)))
    }

    @Test
    fun visualMatchOutranksUnrelatedRows() = runBlocking {
        seed(1L, "grocery receipt total", axis = 10)
        seed(2L, "source code editor", axis = 20)
        seed(3L, "city skyline photo", axis = 30)

        // Query embeds onto axis 20 -> screenshot 2 is the visual match. The query
        // words don't appear in any OCR text, so this is purely the visual path.
        val results = repo(FakeEmbedder(ready = true, axis = 20)).hybridSearch("xyzzy")
        assertTrue("expected at least the visual match", results.isNotEmpty())
        assertEquals(2L, results.first().screenshot.id)
    }

    @Test
    fun ocrAndVisualFuse_sharedHitRanksFirst() = runBlocking {
        seed(1L, "annual budget spreadsheet", axis = 10)
        seed(2L, "weekend hiking plan", axis = 20)
        seed(3L, "budget airline boarding pass", axis = 30)

        // OCR "budget" matches rows 1 and 3; visual axis 10 matches row 1.
        // Row 1 is the only one hit by BOTH signals -> must rank first.
        val results = repo(FakeEmbedder(ready = true, axis = 10)).hybridSearch("budget")
        assertEquals(1L, results.first().screenshot.id)
        assertTrue(results.map { it.screenshot.id }.containsAll(listOf(1L, 3L)))
    }

    @Test
    fun textModelAbsent_degradesToOcrOnly() = runBlocking {
        seed(1L, "kotlin coroutines guide", axis = 10)
        seed(2L, "italian pasta recipe", axis = 20)

        // Not ready -> visual ranking empty; only OCR "pasta" should match row 2.
        val results = repo(FakeEmbedder(ready = false, axis = null)).hybridSearch("pasta")
        assertEquals(1, results.size)
        assertEquals(2L, results.first().screenshot.id)
    }

    @Test
    fun ocrPrefixMatchWorks() = runBlocking {
        seed(1L, "configuration settings", axis = 10)
        // "config" is a prefix of "configuration"; toFtsPrefixQuery appends '*'.
        val results = repo(FakeEmbedder(ready = false, axis = null)).hybridSearch("config")
        assertEquals(listOf(1L), results.map { it.screenshot.id })
    }

    @Test
    fun blankQuery_returnsNothing() = runBlocking {
        seed(1L, "anything", axis = 10)
        assertTrue(repo(FakeEmbedder(ready = true, axis = 10)).hybridSearch("   ").isEmpty())
    }
}
