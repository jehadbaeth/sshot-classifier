package com.okapiorbits.sshotclassifier.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okapiorbits.sshotclassifier.data.db.AppDatabase
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagEntity
import com.okapiorbits.sshotclassifier.data.db.entity.TagSource
import com.okapiorbits.sshotclassifier.data.media.ImageHasher
import com.okapiorbits.sshotclassifier.data.media.MediaStoreScanner
import com.okapiorbits.sshotclassifier.pipeline.clip.EmbeddingCache
import com.okapiorbits.sshotclassifier.pipeline.clip.LabelEmbedder
import com.okapiorbits.sshotclassifier.pipeline.clip.SemanticSearcher
import com.okapiorbits.sshotclassifier.pipeline.clip.TextEmbedder
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Verifies user-tag add/normalize/dedup/remove against a real Room database. */
@RunWith(AndroidJUnit4::class)
class TagEditingInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: ScreenshotDao
    private lateinit var repo: ScreenshotRepository

    private val noEmbedder = object : TextEmbedder {
        override fun isReady() = false
        override fun encode(text: String): FloatArray? = null
    }
    private val noLabelEmbedder = object : LabelEmbedder {
        override fun isReady() = false
        override fun embed(label: String): FloatArray? = null
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.screenshotDao()
        repo = ScreenshotRepository(
            dao = dao,
            scanner = MediaStoreScanner(context),
            hasher = ImageHasher(context),
            semanticSearcher = SemanticSearcher(EmbeddingCache(dao), noEmbedder),
            categoryEmbedder = noLabelEmbedder,
            watchedFoldersStore = com.okapiorbits.sshotclassifier.data.prefs.WatchedFoldersStore(context),
            capturePreferencesStore = com.okapiorbits.sshotclassifier.data.prefs.CapturePreferencesStore(context),
            linkPreviewResolver = com.okapiorbits.sshotclassifier.data.network.LinkPreviewResolver(),
            networkChecker = com.okapiorbits.sshotclassifier.data.network.NetworkChecker(context),
        )
        runBlocking {
            dao.insert(
                ScreenshotEntity(
                    id = 1L, file_path = "f", file_hash = "h", media_store_id = 1L,
                    date_added = 1L, date_processed = 1L, width = 1, height = 1,
                ),
            )
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun addUserTag_normalizesAndStoresAsUserSource() = runBlocking {
        assertTrue(repo.addUserTag(1L, "  Work Stuff "))
        val tags = repo.observeTags(1L).first()
        assertEquals(1, tags.size)
        assertEquals("work stuff", tags[0].label)
        assertEquals(TagSource.USER.name, tags[0].source)
    }

    @Test
    fun addUserTag_rejectsBlank() = runBlocking {
        assertFalse(repo.addUserTag(1L, "   "))
        assertTrue(repo.observeTags(1L).first().isEmpty())
    }

    @Test
    fun addUserTag_dedupesCaseInsensitively() = runBlocking {
        assertTrue(repo.addUserTag(1L, "Travel"))
        assertFalse(repo.addUserTag(1L, "travel")) // duplicate after normalization
        assertEquals(1, repo.observeTags(1L).first().size)
    }

    @Test
    fun addingUserTag_clearsNeedsReview() = runBlocking {
        dao.updateNeedsReview(1L, true)
        assertEquals(1, dao.observeNeedsReviewCount().first())
        repo.addUserTag(1L, "reviewed")
        assertEquals(0, dao.observeNeedsReviewCount().first())
    }

    @Test
    fun removeTag_deletesAnyTagIncludingAuto() = runBlocking {
        dao.insertTag(
            TagEntity(screenshot_id = 1L, label = "map", weight = 0.9f, source = TagSource.FUSED.name),
        )
        val auto = repo.observeTags(1L).first().single()
        repo.removeTag(auto.id, 1L)
        assertTrue(repo.observeTags(1L).first().isEmpty())
    }
}
