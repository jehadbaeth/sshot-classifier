package com.okapiorbits.sshotclassifier.pipeline.geometry

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.math.abs

class HomographyTest {

    private fun assertClose(expected: Point2, actual: Point2, eps: Double = 1e-6) {
        assertEquals(expected.x, actual.x, eps)
        assertEquals(expected.y, actual.y, eps)
    }

    @Test
    fun identity_mapsPointsToThemselves() {
        val square = listOf(Point2(0.0, 0.0), Point2(10.0, 0.0), Point2(10.0, 10.0), Point2(0.0, 10.0))
        val h = solveHomography(square, square)!!
        assertClose(Point2(5.0, 5.0), h.apply(Point2(5.0, 5.0)))
        assertClose(Point2(0.0, 0.0), h.apply(Point2(0.0, 0.0)))
    }

    @Test
    fun pureScaleAndTranslate_isAffine() {
        val from = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))
        // scale by 2, translate by (3, 4)
        val to = listOf(Point2(3.0, 4.0), Point2(5.0, 4.0), Point2(5.0, 6.0), Point2(3.0, 6.0))
        val h = solveHomography(from, to)!!
        assertClose(Point2(4.0, 5.0), h.apply(Point2(0.5, 0.5)))
        assertClose(Point2(3.0, 4.0), h.apply(Point2(0.0, 0.0)))
        assertClose(Point2(5.0, 6.0), h.apply(Point2(1.0, 1.0)))
    }

    @Test
    fun perspectiveQuad_mapsCornersExactly() {
        // A trapezoid (as if a rectangular page were photographed at an angle) unwarped
        // back to a unit square: the corner correspondences must round-trip exactly.
        val outputRect = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))
        val quad = listOf(Point2(50.0, 40.0), Point2(300.0, 20.0), Point2(320.0, 280.0), Point2(30.0, 260.0))
        val h = solveHomography(outputRect, quad)!!
        outputRect.zip(quad).forEach { (o, q) -> assertClose(q, h.apply(o), eps = 1e-4) }

        // A midpoint of the output square should land inside the quad's bounding box.
        val mid = h.apply(Point2(0.5, 0.5))
        assertEquals(true, mid.x in 30.0..320.0)
        assertEquals(true, mid.y in 20.0..280.0)
    }

    @Test
    fun collinearPoints_returnsNull() {
        val from = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))
        val degenerate = listOf(Point2(0.0, 0.0), Point2(1.0, 0.0), Point2(2.0, 0.0), Point2(3.0, 0.0))
        assertNull(solveHomography(from, degenerate))
    }

    @Test
    fun duplicateSourcePoints_returnsNull() {
        // Two distinct "from" corners collapse onto the same point but are required to map
        // to two different "to" points: no function (let alone a homography) can satisfy that.
        val degenerateFrom = listOf(Point2(0.0, 0.0), Point2(0.0, 0.0), Point2(1.0, 1.0), Point2(0.0, 1.0))
        val to = listOf(Point2(5.0, 5.0), Point2(9.0, 1.0), Point2(9.0, 9.0), Point2(1.0, 9.0))
        assertNull(solveHomography(degenerateFrom, to))
    }

    @Test
    fun rotatedSquare_isSymmetric() {
        val from = listOf(Point2(-1.0, -1.0), Point2(1.0, -1.0), Point2(1.0, 1.0), Point2(-1.0, 1.0))
        // 90 degree rotation
        val to = listOf(Point2(1.0, -1.0), Point2(1.0, 1.0), Point2(-1.0, 1.0), Point2(-1.0, -1.0))
        val h = solveHomography(from, to)!!
        val center = h.apply(Point2(0.0, 0.0))
        assertEquals(0.0, center.x, 1e-9)
        assertEquals(0.0, center.y, 1e-9)
        assertEquals(true, abs(h.g) < 1e-9 && abs(h.h) < 1e-9) // pure rotation has no perspective terms
    }
}
