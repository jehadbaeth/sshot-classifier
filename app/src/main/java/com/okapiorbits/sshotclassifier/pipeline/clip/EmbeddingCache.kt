package com.okapiorbits.sshotclassifier.pipeline.clip

import com.okapiorbits.sshotclassifier.data.db.ScreenshotDao
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory cache of decoded image embeddings for semantic search.
 *
 * Without this, [SemanticSearcher] read and re-deserialized every embedding blob
 * from SQLite on every query, which the scale test (docs/spikes/scale-test.md)
 * showed to be the search bottleneck (linear, ~126 ms at 10k images). Decoding once
 * and reusing the FloatArrays makes repeat searches near-instant; only the first
 * query after an embedding changes pays the rebuild.
 *
 * Invalidated explicitly whenever embeddings change (see [ImageProcessor]); the
 * cache is a process singleton, so a destructive DB recreate on restart starts
 * fresh anyway.
 */
@Singleton
class EmbeddingCache @Inject constructor(
    private val dao: ScreenshotDao,
) {
    data class Entry(val screenshotId: Long, val vector: FloatArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Entry) return false
            return screenshotId == other.screenshotId && vector.contentEquals(other.vector)
        }

        override fun hashCode(): Int = 31 * screenshotId.hashCode() + vector.contentHashCode()
    }

    private val mutex = Mutex()
    @Volatile
    private var cached: List<Entry>? = null

    /** Decoded embeddings, built from the DB on first use and reused until invalidated. */
    suspend fun entries(): List<Entry> {
        cached?.let { return it }
        return mutex.withLock {
            // Re-check inside the lock: another coroutine may have built it while we waited.
            cached ?: dao.allEmbeddings()
                .map { Entry(it.screenshot_id, EmbeddingCodec.toFloats(it.vector)) }
                .also { cached = it }
        }
    }

    /** Drops the cache so the next [entries] call rebuilds from the DB. */
    fun invalidate() {
        cached = null
    }
}
