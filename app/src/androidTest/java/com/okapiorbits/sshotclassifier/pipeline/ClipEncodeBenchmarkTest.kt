package com.okapiorbits.sshotclassifier.pipeline

import android.graphics.BitmapFactory
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipEncoder
import com.okapiorbits.sshotclassifier.pipeline.clip.ClipModelManager
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * On-device benchmark for CLIP image-encode latency (the design section 12 target that had no
 * harness). Encodes a bundled image repeatedly and logs mean / p50 / p95 under "ClipBench".
 *
 * Requires the image model to be installed on the device (push it or let the app download it);
 * if it is absent the test is skipped rather than failed, so CI (Linux, no model) is unaffected.
 * Run: adb-install the app, ensure the model is present, then run this class on the device.
 */
@RunWith(AndroidJUnit4::class)
class ClipEncodeBenchmarkTest {

    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test
    fun measuresEncodeLatency() {
        val encoder = ClipEncoder(appContext, ClipModelManager(appContext))
        assumeTrue("CLIP image model not installed; skipping encode benchmark", encoder.isReady())

        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets
        val bitmap = testAssets.open("testimg/city.jpg").use { BitmapFactory.decodeStream(it) }
        requireNotNull(bitmap) { "could not decode benchmark image" }

        // Warm up (first run pays JIT + delegate init), then measure.
        repeat(3) { encoder.encode(bitmap) }
        val n = 30
        val times = LongArray(n)
        for (i in 0 until n) {
            val t0 = System.nanoTime()
            encoder.encode(bitmap)
            times[i] = (System.nanoTime() - t0) / 1_000_000
        }
        times.sort()
        val mean = times.average()
        val p50 = times[n / 2]
        val p95 = times[(n * 95) / 100]
        Log.i("ClipBench", "CLIP encode over $n runs: mean=${"%.1f".format(mean)}ms p50=${p50}ms p95=${p95}ms")
    }
}
