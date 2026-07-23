package com.okapiorbits.sshotclassifier.pipeline.geometry

import android.graphics.Bitmap
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Turns a perspective-warped photo of a document into something closer to what
 * you'd get out of an actual scanner: a per-channel auto-levels pass that
 * removes color cast and flattens the background toward white, plus an
 * optional adaptive black-and-white mode for monotone printing.
 *
 * Call off the main thread; both passes are pixel-by-pixel over the full
 * bitmap.
 */
object DocumentEnhance {

    /**
     * Per-channel histogram stretch: each of R/G/B is independently remapped
     * so its 1st percentile -> 0 and 99th percentile -> 255. This both removes
     * a color cast (e.g. warm indoor lighting) and boosts contrast, since a
     * cast shows up as one channel being systematically dimmer than the others.
     */
    fun autoLevels(source: Bitmap): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val histR = IntArray(256)
        val histG = IntArray(256)
        val histB = IntArray(256)
        for (p in pixels) {
            histR[(p shr 16) and 0xFF]++
            histG[(p shr 8) and 0xFF]++
            histB[p and 0xFF]++
        }
        val total = width * height
        val (loR, hiR) = percentileRange(histR, total)
        val (loG, hiG) = percentileRange(histG, total)
        val (loB, hiB) = percentileRange(histB, total)

        val lutR = buildLut(loR, hiR)
        val lutG = buildLut(loG, hiG)
        val lutB = buildLut(loB, hiB)

        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p shr 24) and 0xFF
            val r = lutR[(p shr 16) and 0xFF]
            val g = lutG[(p shr 8) and 0xFF]
            val b = lutB[p and 0xFF]
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
        return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
    }

    /**
     * Adaptive black-and-white for monotone printing. Bright, saturated colors
     * (highlighter marks, colored backgrounds) are treated as background and
     * pushed to white rather than rendered as gray/black blocks that waste
     * toner; everything else is thresholded against its local neighborhood
     * (Sauvola's method) so shadows from an uneven scan don't wash out text.
     */
    fun binarize(source: Bitmap, windowFraction: Double = 0.02, k: Double = 0.34): Bitmap {
        val width = source.width
        val height = source.height
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)

        val gray = IntArray(width * height)
        for (i in pixels.indices) {
            val p = pixels[i]
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            val maxc = max(r, max(g, b))
            val minc = min(r, min(g, b))
            val sat = if (maxc == 0) 0f else (maxc - minc).toFloat() / maxc
            val value = maxc / 255f
            val isHighlight = sat > 0.35f && value > 0.55f
            gray[i] = if (isHighlight) 255 else (0.299 * r + 0.587 * g + 0.114 * b).toInt().coerceIn(0, 255)
        }

        // Integral images of gray and gray^2 for O(1) windowed mean/variance.
        val sum = LongArray((width + 1) * (height + 1))
        val sumSq = LongArray((width + 1) * (height + 1))
        val stride = width + 1
        for (y in 0 until height) {
            var rowSum = 0L
            var rowSumSq = 0L
            for (x in 0 until width) {
                val v = gray[y * width + x].toLong()
                rowSum += v
                rowSumSq += v * v
                val idx = (y + 1) * stride + (x + 1)
                sum[idx] = sum[idx - stride] + rowSum
                sumSq[idx] = sumSq[idx - stride] + rowSumSq
            }
        }

        val window = (max(width, height) * windowFraction).toInt().coerceIn(15, 200)
        val half = window / 2
        val dynamicRange = 128.0

        val out = IntArray(width * height)
        for (y in 0 until height) {
            val y0 = max(0, y - half)
            val y1 = min(height - 1, y + half)
            for (x in 0 until width) {
                val x0 = max(0, x - half)
                val x1 = min(width - 1, x + half)
                val n = (x1 - x0 + 1).toLong() * (y1 - y0 + 1).toLong()
                val boxSum = boxQuery(sum, stride, x0, y0, x1, y1)
                val boxSumSq = boxQuery(sumSq, stride, x0, y0, x1, y1)
                val mean = boxSum.toDouble() / n
                val variance = max(0.0, boxSumSq.toDouble() / n - mean * mean)
                val std = sqrt(variance)
                val threshold = mean * (1.0 + k * (std / dynamicRange - 1.0))
                val v = gray[y * width + x]
                out[y * width + x] = if (v < threshold) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
            }
        }
        return Bitmap.createBitmap(out, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun boxQuery(integral: LongArray, stride: Int, x0: Int, y0: Int, x1: Int, y1: Int): Long {
        val a = integral[y0 * stride + x0]
        val b = integral[y0 * stride + (x1 + 1)]
        val c = integral[(y1 + 1) * stride + x0]
        val d = integral[(y1 + 1) * stride + (x1 + 1)]
        return d - b - c + a
    }

    /** Finds the 1st/99th percentile values of a channel histogram, guarding against a flat image. */
    private fun percentileRange(hist: IntArray, total: Int): Pair<Int, Int> {
        val loCount = (total * 0.01).toInt()
        val hiCount = (total * 0.99).toInt()
        var running = 0
        var lo = 0
        for (v in 0..255) {
            running += hist[v]
            if (running >= loCount) { lo = v; break }
        }
        running = 0
        var hi = 255
        for (v in 255 downTo 0) {
            running += hist[v]
            if (running >= total - hiCount) { hi = v; break }
        }
        if (hi <= lo) return 0 to 255
        return lo to hi
    }

    private fun buildLut(lo: Int, hi: Int): IntArray {
        val range = (hi - lo).coerceAtLeast(1)
        return IntArray(256) { v -> (((v - lo) * 255) / range).coerceIn(0, 255) }
    }
}
