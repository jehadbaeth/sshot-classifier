package com.okapiorbits.sshotclassifier.pipeline.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Proves the Kotlin BPE port is byte-identical to open_clip's SimpleTokenizer.
 * Oracle ids were captured from open_clip 3.3.0, ViT-B-32, in the spike venv.
 * If these drift, the text encoder and stored image embeddings stop lining up.
 */
class BpeTokenizerTest {

    private lateinit var tok: BpeTokenizer

    @Before
    fun setUp() {
        // testDebugUnitTest runs with the module dir as cwd; fall back to repo-root run.
        val candidates = listOf(
            File("src/main/assets/clip/bpe_merges.txt"),
            File("app/src/main/assets/clip/bpe_merges.txt"),
        )
        val merges = candidates.first { it.exists() }
        tok = merges.inputStream().use { BpeTokenizer.fromStream(it) }
    }

    @Test
    fun `special token ids match open_clip`() {
        assertEquals(49406, tok.sotToken)
        assertEquals(49407, tok.eotToken)
    }

    @Test
    fun `encode matches python oracle`() {
        assertEquals(listOf(3306, 1002), tok.encode("hello world"))
        assertEquals(listOf(320, 2723, 4684, 6827), tok.encode("a bank account balance"))
        assertEquals(listOf(3424, 962, 518, 5873), tok.encode("sunset over the mountains"))
        assertEquals(listOf(607, 3795, 10086, 3511), tok.encode("my flight boarding pass"))
        // lowercasing + single-digit tokens + punctuation
        assertEquals(
            listOf(12097, 275, 271, 275, 281, 2504, 783, 1546, 256),
            tok.encode("Error 404: page not found!"),
        )
        assertEquals(
            listOf(1320, 273, 6295, 530, 273, 271, 273, 275),
            tok.encode("CO2 levels in 2024"),
        )
    }

    @Test
    fun `tokenize wraps with sot eot and zero pads to context length`() {
        val ids = tok.tokenize("a map with directions")
        assertEquals(77, ids.size)
        assertEquals(
            listOf(49406, 320, 3923, 593, 16440, 49407),
            ids.take(6),
        )
        assertTrue(ids.drop(6).all { it == 0 })
    }

    @Test
    fun `over-length input is truncated and still ends in eot`() {
        val long = (1..200).joinToString(" ") { "word$it" }
        val ids = tok.tokenize(long, contextLength = 77)
        assertEquals(77, ids.size)
        assertEquals(49406, ids.first())
        assertEquals(49407, ids.last())
    }
}
