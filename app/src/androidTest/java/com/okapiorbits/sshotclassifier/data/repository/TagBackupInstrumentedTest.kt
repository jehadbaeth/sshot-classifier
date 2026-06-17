package com.okapiorbits.sshotclassifier.data.repository

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okapiorbits.sshotclassifier.data.db.AppDatabase
import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Round-trips user tags through export/import JSON against a real Room database. */
@RunWith(AndroidJUnit4::class)
class TagBackupInstrumentedTest {

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

    private fun newRepo() = ScreenshotRepository(
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

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.screenshotDao()
        repo = newRepo()
        runBlocking {
            dao.insert(ScreenshotEntity(id = 1L, file_path = "a", file_hash = "hashA", media_store_id = 1L, date_added = 1L, date_processed = 1L, width = 1, height = 1))
            dao.insert(ScreenshotEntity(id = 2L, file_path = "b", file_hash = "hashB", media_store_id = 2L, date_added = 2L, date_processed = 1L, width = 1, height = 1))
        }
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun exportThenImport_reattachesUserTagsByHash() = runBlocking {
        repo.addUserTag(1L, "Work")
        repo.addUserTag(1L, "Receipts")
        repo.addUserTag(2L, "Travel")
        val json = repo.exportTagsJson()

        // Simulate a reinstall: drop the tags, then import them back.
        for (t in repo.observeTags(1L).first()) dao.deleteTag(t.id)
        for (t in repo.observeTags(2L).first()) dao.deleteTag(t.id)
        assertTrue(repo.observeTags(1L).first().isEmpty())

        val result = repo.importTagsJson(json)
        assertEquals(3, result.userTagsApplied)
        assertEquals(setOf("work", "receipts"), repo.observeTags(1L).first().map { it.label }.toSet())
        assertEquals(listOf("travel"), repo.observeTags(2L).first().map { it.label })
    }

    @Test
    fun import_skipsUnknownHashesAndDuplicates() = runBlocking {
        repo.addUserTag(1L, "Keep")
        // hashC is not present on this device; the existing "keep" tag is a duplicate.
        val json = """{"version":1,"userTags":[
            {"hash":"hashA","label":"keep"},
            {"hash":"hashC","label":"orphan"}
        ],"customCategories":[]}"""
        val result = repo.importTagsJson(json)
        assertEquals(0, result.userTagsApplied)
        assertEquals(2, result.skipped)
        assertEquals(listOf("keep"), repo.observeTags(1L).first().map { it.label })
    }

    @Test
    fun import_toleratesGarbageInput() = runBlocking {
        val result = repo.importTagsJson("not json at all")
        assertEquals(0, result.userTagsApplied)
    }
}
