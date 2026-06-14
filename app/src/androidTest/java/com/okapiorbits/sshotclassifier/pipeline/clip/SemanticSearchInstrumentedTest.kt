package com.okapiorbits.sshotclassifier.pipeline.clip

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * End-to-end on-device proof that the CLIP text and image encoders run in
 * Android's TFLite runtime and land in the SAME embedding space: a free-text
 * query must rank the visually-matching screenshot highest among distractors.
 *
 * Requires both int8 models pushed into the app's internal storage first:
 *   adb root
 *   adb push out/clip_image_b32_int8w.tflite /data/data/<pkg>/files/models/
 *   adb push out/clip_text_b32_int8w.tflite  /data/data/<pkg>/files/models/
 * If they are absent the test is skipped (assumeTrue), not failed.
 */
@RunWith(AndroidJUnit4::class)
class SemanticSearchInstrumentedTest {

    private val targetContext = InstrumentationRegistry.getInstrumentation().targetContext
    private val testContext = InstrumentationRegistry.getInstrumentation().context

    private lateinit var modelManager: ClipModelManager
    private lateinit var imageEncoder: ClipEncoder
    private lateinit var textEncoder: ClipTextEncoder

    @Before
    fun setUp() {
        modelManager = ClipModelManager(targetContext)
        assumeTrue("CLIP image model not installed; push it first", modelManager.isModelInstalled())
        assumeTrue("CLIP text model not installed; push it first", modelManager.isTextModelInstalled())
        val tokenizer = ClipTokenizer(targetContext)
        imageEncoder = ClipEncoder(targetContext, modelManager)
        textEncoder = ClipTextEncoder(modelManager, tokenizer)
    }

    private fun asset(name: String): Bitmap =
        testContext.assets.open("testimg/$name").use { BitmapFactory.decodeStream(it) }!!

    @Test
    fun textQueryRanksMatchingScreenshotHighest() {
        val images = mapOf(
            "map" to asset("map.png"),
            "code" to asset("code.png"),
            "receipt" to asset("receipt.png"),
            "landscape" to asset("landscape.jpg"),
            "city" to asset("city.jpg"),
        )
        val imageEmb = images.mapValues { (_, bmp) ->
            requireNotNull(imageEncoder.encode(bmp)) { "image encode returned null" }
        }
        imageEmb.values.forEach { assertUnitVector(it) }

        // query -> the image key it should retrieve first
        val cases = mapOf(
            "a map of streets and roads" to "map",
            "program source code in an editor" to "code",
            "a store receipt with prices and total" to "receipt",
        )

        for ((query, expected) in cases) {
            val q = requireNotNull(textEncoder.encode(query)) { "text encode returned null" }
            assertUnitVector(q)
            val ranked = imageEmb.entries
                .map { it.key to dot(q, it.value) }
                .sortedByDescending { it.second }
            android.util.Log.i(
                "SemanticSearchTest",
                "q=\"$query\" -> " + ranked.joinToString { "%s %.3f".format(it.first, it.second) },
            )
            val top = ranked.first().first
            assertEquals(
                "query \"$query\" expected $expected but ranked: $ranked",
                expected, top,
            )
        }
    }

    private fun assertUnitVector(v: FloatArray) {
        assertEquals(ClipModelManager.EMBED_DIM, v.size)
        var sum = 0f
        for (x in v) {
            assertTrue("non-finite component", x.isFinite())
            sum += x * x
        }
        assertTrue("not unit norm: ${sqrt(sum)}", abs(sqrt(sum) - 1f) < 1e-3f)
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        for (i in a.indices) s += a[i] * b[i]
        return s
    }
}
