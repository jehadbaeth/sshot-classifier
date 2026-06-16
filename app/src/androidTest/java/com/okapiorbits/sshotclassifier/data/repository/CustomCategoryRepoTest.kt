package com.okapiorbits.sshotclassifier.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okapiorbits.sshotclassifier.data.db.AppDatabase
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.entity.EmbeddingEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagSource
import com.okapiorbits.sshotclassifier.data.media.ImageHasher
import com.okapiorbits.sshotclassifier.data.media.MediaStoreScanner
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCache
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCodec
import com.okapiorbits.sshotclassifier.pipeline.clip.LabelEmbedder
import com.okapiorbits.sshotclassifier.pipeline.clip.SemanticSearcher
import com.okapiorbits.sshotclassifier.pipeline.clip.TextEmbedder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the custom-category flow against real Room with a fake embedder
 * (no text model): adding a category embeds the label, applies it to existing
 * screenshots above the cosine threshold, and removing it deletes those tags.
 */
@RunWith(AndroidJUnit4::class)
class CustomCategoryRepoTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: ScreenshotDao

    private val noText = object : TextEmbedder {
        override fun isReady() = false
        override fun encode(text: String): FloatArray? = null
    }

    /** Embeds every label to the same unit vector on [axis], so matches are deterministic. */
    private class FakeLabelEmbedder(val ready: Boolean, val axis: Int) : LabelEmbedder {
        override fun isReady() = ready
        override fun embed(label: String): FloatArray? =
            if (ready) FloatArray(512).also { it[axis] = 1f } else null
    }

    private fun repo(embedder: LabelEmbedder) = ScreenshotRepository(
        dao = dao,
        scanner = MediaStoreScanner(context),
        hasher = ImageHasher(context),
        semanticSearcher = SemanticSearcher(EmbeddingCache(dao), noText),
        categoryEmbedder = embedder,
        watchedFoldersStore = com.okapiorbits.sshotclassifier.data.prefs.WatchedFoldersStore(context),
        capturePreferencesStore = com.okapiorbits.sshotclassifier.data.prefs.CapturePreferencesStore(context),
        linkPreviewResolver = com.okapiorbits.sshotclassifier.data.network.LinkPreviewResolver(),
        networkChecker = com.okapiorbits.sshotclassifier.data.network.NetworkChecker(context),
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.screenshotDao()
    }

    @After
    fun tearDown() = db.close()

    private fun seedWithEmbedding(id: Long, axis: Int) = runBlocking {
        dao.insert(
            ScreenshotEntity(
                id = id, file_path = "f$id", file_hash = "h$id", media_store_id = id,
                date_added = id, date_processed = id, width = 1, height = 1,
            ),
        )
        val v = FloatArray(512).also { it[axis] = 1f }
        dao.insertEmbedding(EmbeddingEntity(screenshot_id = id, vector = EmbeddingCodec.toBytes(v)))
    }

    @Test
    fun add_appliesToMatchingExistingScreenshotsOnly() = runBlocking {
        seedWithEmbedding(1L, axis = 5)  // aligned with the category -> cosine 1.0
        seedWithEmbedding(2L, axis = 9)  // orthogonal -> cosine 0.0

        val result = repo(FakeLabelEmbedder(ready = true, axis = 5)).addCustomCategory("Widgets")
        assertEquals(ScreenshotRepository.AddCategoryResult.Added(1), result)

        val s1 = dao.observeTagsFor(1L).first()
        assertEquals(1, s1.size)
        assertEquals("widgets", s1[0].label)
        assertEquals(TagSource.CUSTOM.name, s1[0].source)
        assertTrue(dao.observeTagsFor(2L).first().isEmpty())
    }

    @Test
    fun add_rejectsBlankDuplicateAndMissingModel() = runBlocking {
        val ready = repo(FakeLabelEmbedder(ready = true, axis = 5))
        assertEquals(ScreenshotRepository.AddCategoryResult.Blank, ready.addCustomCategory("  "))

        assertTrue(ready.addCustomCategory("travel") is ScreenshotRepository.AddCategoryResult.Added)
        assertEquals(ScreenshotRepository.AddCategoryResult.Duplicate, ready.addCustomCategory("Travel"))

        val notReady = repo(FakeLabelEmbedder(ready = false, axis = 5))
        assertEquals(ScreenshotRepository.AddCategoryResult.ModelMissing, notReady.addCustomCategory("memes"))
    }

    @Test
    fun remove_deletesCategoryAndItsTags() = runBlocking {
        seedWithEmbedding(1L, axis = 5)
        val r = repo(FakeLabelEmbedder(ready = true, axis = 5))
        r.addCustomCategory("widgets")
        assertEquals(1, dao.observeTagsFor(1L).first().size)

        val cat = dao.allCategories().single()
        r.removeCustomCategory(cat.id, cat.label)

        assertTrue(dao.allCategories().isEmpty())
        assertTrue(dao.observeTagsFor(1L).first().isEmpty())
    }
}
