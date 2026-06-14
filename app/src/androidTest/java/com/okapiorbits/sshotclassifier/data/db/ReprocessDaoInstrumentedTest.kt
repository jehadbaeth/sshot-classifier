package com.okapiorbits.sshotclassifier.data.db

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.okapiorbits.sshotclassifier.data.db.entity.EmbeddingEntity
import com.okapiorbits.sshotclassifier.data.db.entity.ProcessingStatus
import com.okapiorbits.sshotclassifier.data.db.entity.ScreenshotEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies the reprocess queries against real SQLite: only DONE screenshots that
 * lack an embedding are counted and reset; embedded or non-DONE rows are left alone.
 */
@RunWith(AndroidJUnit4::class)
class ReprocessDaoInstrumentedTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var db: AppDatabase
    private lateinit var dao: ScreenshotDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries().build()
        dao = db.screenshotDao()
    }

    @After
    fun tearDown() = db.close()

    private fun shot(id: Long, status: ProcessingStatus) = ScreenshotEntity(
        id = id, file_path = "f$id", file_hash = "h$id", media_store_id = id,
        date_added = id, date_processed = id, width = 1, height = 1, status = status.name,
    )

    @Test
    fun countsAndResetsOnlyDoneWithoutEmbedding() = runBlocking {
        dao.insert(shot(1L, ProcessingStatus.DONE)) // DONE, no embedding -> candidate
        dao.insert(shot(2L, ProcessingStatus.DONE)) // DONE, has embedding -> not a candidate
        dao.insert(shot(3L, ProcessingStatus.PENDING)) // not DONE -> not a candidate
        dao.insertEmbedding(EmbeddingEntity(screenshot_id = 2L, vector = ByteArray(2048)))

        assertEquals(1, dao.observeReprocessableCount().first())

        val reset = dao.markMissingEmbeddingsForReprocessing()
        assertEquals(1, reset)

        // row 1 is now PENDING; row 2 stayed DONE; nothing left to reprocess.
        assertEquals(0, dao.observeReprocessableCount().first())
        assertEquals(
            ProcessingStatus.PENDING.name,
            dao.pending().first { it.id == 1L }.status,
        )
    }
}
