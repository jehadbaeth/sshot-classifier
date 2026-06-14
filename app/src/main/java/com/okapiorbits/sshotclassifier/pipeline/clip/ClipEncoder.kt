package com.okapiorbits.sshotclassifier.pipeline.clip

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import dagger.hilt.android.qualifiers.ApplicationContext
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.sqrt

/**
 * Runs the CLIP ViT-B/32 image encoder (TFLite, int8 weight-only) on a screenshot
 * and returns an L2-normalized 512-dim embedding. Preprocessing matches open_clip:
 * resize shorter side to 224, center crop 224, normalize with CLIP mean/std, NCHW.
 *
 * The interpreter is created lazily and only if the model is installed; returns
 * null otherwise so the pipeline degrades to OCR-only tagging.
 */
@Singleton
class ClipEncoder @Inject constructor(
    @ApplicationContext private val context: Context,
    private val modelManager: ClipModelManager,
) {
    private val size = ClipModelManager.IMAGE_SIZE
    private val mean = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    private val std = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    @Volatile private var interpreter: Interpreter? = null

    private fun ensureInterpreter(): Interpreter? {
        interpreter?.let { return it }
        if (!modelManager.isModelInstalled()) return null
        return synchronized(this) {
            interpreter ?: runCatching {
                Interpreter(modelManager.modelFile, Interpreter.Options().apply { numThreads = 4 })
            }.getOrNull()?.also { interpreter = it }
        }
    }

    fun isReady(): Boolean = modelManager.isModelInstalled()

    /** Returns the L2-normalized image embedding, or null if model missing or decode fails. */
    fun encode(uri: Uri): FloatArray? {
        val interp = ensureInterpreter() ?: return null
        val bitmap = decode(uri) ?: return null
        val input = preprocess(bitmap)
        bitmap.recycle()

        val output = Array(1) { FloatArray(ClipModelManager.EMBED_DIM) }
        return try {
            interp.run(input, output)
            l2normalize(output[0])
        } catch (e: Exception) {
            null
        }
    }

    private fun decode(uri: Uri): Bitmap? = try {
        context.contentResolver.openInputStream(uri)?.use { stream ->
            // Downsample on decode to keep memory low; we only need ~224px.
            val opts = BitmapFactory.Options().apply { inSampleSize = sampleSizeFor(uri) }
            BitmapFactory.decodeStream(stream, null, opts)
        }
    } catch (e: Exception) {
        null
    }

    private fun sampleSizeFor(uri: Uri): Int {
        return try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeStream(stream, null, bounds)
                val shorter = minOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
                var s = 1
                while (shorter / (s * 2) >= size) s *= 2
                s
            } ?: 1
        } catch (e: Exception) {
            1
        }
    }

    /** Resize shorter side to 224, center crop 224x224, normalize, pack NCHW float32. */
    private fun preprocess(src: Bitmap): ByteBuffer {
        val w = src.width
        val h = src.height
        val scale = size.toFloat() / minOf(w, h)
        val rw = Math.round(w * scale)
        val rh = Math.round(h * scale)
        val scaled = Bitmap.createScaledBitmap(src, rw, rh, true)
        val left = (rw - size) / 2
        val top = (rh - size) / 2
        val cropped = Bitmap.createBitmap(scaled, left, top, size, size)
        if (scaled != cropped) scaled.recycle()

        val pixels = IntArray(size * size)
        cropped.getPixels(pixels, 0, size, 0, 0, size, size)
        if (cropped != src) cropped.recycle()

        val buf = ByteBuffer.allocateDirect(3 * size * size * 4).order(ByteOrder.nativeOrder())
        // NCHW: all R, then all G, then all B.
        for (c in 0 until 3) {
            val m = mean[c]
            val s = std[c]
            for (i in pixels.indices) {
                val p = pixels[i]
                val v = when (c) {
                    0 -> (p shr 16 and 0xFF)
                    1 -> (p shr 8 and 0xFF)
                    else -> (p and 0xFF)
                }
                buf.putFloat(((v / 255f) - m) / s)
            }
        }
        buf.rewind()
        return buf
    }

    private fun l2normalize(v: FloatArray): FloatArray {
        var sum = 0f
        for (x in v) sum += x * x
        val norm = sqrt(sum).coerceAtLeast(1e-8f)
        for (i in v.indices) v[i] /= norm
        return v
    }
}
