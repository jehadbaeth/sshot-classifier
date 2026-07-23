package com.okapiorbits.sshotclassifier.pipeline.geometry

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Straightens a user-picked quadrilateral region of [source] into an upright
 * rectangular bitmap, as if the document had been photographed head-on.
 *
 * [corners] must be exactly 4 points in source-bitmap pixel coordinates, in
 * order: top-left, top-right, bottom-right, bottom-left (not necessarily axis
 * aligned — that's the whole point of a corner-drag crop).
 *
 * Output size is derived from the quad's own edge lengths (the longer of each
 * opposing pair) so the result isn't stretched, then capped at [maxDimension]
 * on the long side to keep file size and processing time bounded.
 *
 * Implementation note: `Canvas.drawBitmap` silently drops the perspective terms
 * of a `Matrix`, so this samples the source bitmap directly through the inverse
 * mapping (dst-rect -> src-quad, via [solveHomography]) with bilinear
 * interpolation, pixel by pixel. Call off the main thread.
 */
object PerspectiveCrop {

    fun warpToRect(source: Bitmap, corners: List<PointF>, maxDimension: Int = 2200): Bitmap {
        require(corners.size == 4) { "warpToRect needs exactly 4 corners (TL, TR, BR, BL)" }
        val (tl, tr, br, bl) = corners

        val topWidth = dist(tl, tr)
        val bottomWidth = dist(bl, br)
        val leftHeight = dist(tl, bl)
        val rightHeight = dist(tr, br)

        val outW = max(topWidth, bottomWidth)
        val outH = max(leftHeight, rightHeight)
        val scale = min(1.0, maxDimension / max(outW, outH).coerceAtLeast(1.0))
        val width = max(1, ceil(outW * scale).toInt())
        val height = max(1, ceil(outH * scale).toInt())

        val outputRect = listOf(
            Point2(0.0, 0.0), Point2(width.toDouble(), 0.0),
            Point2(width.toDouble(), height.toDouble()), Point2(0.0, height.toDouble()),
        )
        val quad = corners.map { Point2(it.x.toDouble(), it.y.toDouble()) }
        val homography = solveHomography(outputRect, quad)
            ?: return Bitmap.createBitmap(source, 0, 0, source.width, source.height)

        val srcWidth = source.width
        val srcHeight = source.height
        val srcPixels = IntArray(srcWidth * srcHeight)
        source.getPixels(srcPixels, 0, srcWidth, 0, 0, srcWidth, srcHeight)

        val outPixels = IntArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val sample = homography.apply(Point2(x + 0.5, y + 0.5))
                outPixels[y * width + x] = bilinearSample(srcPixels, srcWidth, srcHeight, sample.x, sample.y)
            }
        }

        return Bitmap.createBitmap(outPixels, width, height, Bitmap.Config.ARGB_8888)
    }

    private fun dist(a: PointF, b: PointF): Double = hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())

    /** Bilinear sample at (x, y); out-of-bounds reads are clamped to the edge pixel. */
    private fun bilinearSample(pixels: IntArray, width: Int, height: Int, x: Double, y: Double): Int {
        val clampedX = x.coerceIn(0.0, (width - 1).toDouble())
        val clampedY = y.coerceIn(0.0, (height - 1).toDouble())
        val x0 = clampedX.toInt()
        val y0 = clampedY.toInt()
        val x1 = min(x0 + 1, width - 1)
        val y1 = min(y0 + 1, height - 1)
        val fx = clampedX - x0
        val fy = clampedY - y0

        val c00 = pixels[y0 * width + x0]
        val c10 = pixels[y0 * width + x1]
        val c01 = pixels[y1 * width + x0]
        val c11 = pixels[y1 * width + x1]

        fun channel(shift: Int): Int {
            val v00 = (c00 shr shift) and 0xFF
            val v10 = (c10 shr shift) and 0xFF
            val v01 = (c01 shr shift) and 0xFF
            val v11 = (c11 shr shift) and 0xFF
            val top = v00 + (v10 - v00) * fx
            val bottom = v01 + (v11 - v01) * fx
            return (top + (bottom - top) * fy).toInt().coerceIn(0, 255)
        }

        val a = channel(24)
        val r = channel(16)
        val g = channel(8)
        val bch = channel(0)
        return (a shl 24) or (r shl 16) or (g shl 8) or bch
    }
}
