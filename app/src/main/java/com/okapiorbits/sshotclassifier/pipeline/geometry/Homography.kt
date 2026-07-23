package com.okapiorbits.sshotclassifier.pipeline.geometry

/**
 * A 2D point with double precision, kept free of android.graphics so the
 * projective-transform math is plain-JVM unit testable.
 */
data class Point2(val x: Double, val y: Double)

/**
 * A 3x3 projective transform (row-major, last entry fixed at 1):
 * [a b c]
 * [d e f]
 * [g h 1]
 * Maps (x, y) -> ((a*x + b*y + c) / (g*x + h*y + 1), (d*x + e*y + f) / (g*x + h*y + 1)).
 */
data class Homography(
    val a: Double, val b: Double, val c: Double,
    val d: Double, val e: Double, val f: Double,
    val g: Double, val h: Double,
) {
    fun apply(p: Point2): Point2 {
        val denom = g * p.x + h * p.y + 1.0
        return Point2((a * p.x + b * p.y + c) / denom, (d * p.x + e * p.y + f) / denom)
    }
}

/**
 * Solves the projective transform mapping each `from[i]` to `to[i]` for exactly
 * four non-degenerate point correspondences (the corners of a quadrilateral).
 * Used to unwarp a user-picked document quad into a straight rectangle: `from`
 * is the axis-aligned output rectangle's corners, `to` is the quad the user
 * drew on the source image, so sampling `apply` per output pixel reads the
 * right source pixel (a document-scanner "dst grid samples src quad" mapping).
 *
 * Returns null if the four points don't determine a valid homography (e.g.
 * collinear or duplicate points making the linear system singular).
 */
fun solveHomography(from: List<Point2>, to: List<Point2>): Homography? {
    require(from.size == 4 && to.size == 4) { "solveHomography needs exactly 4 point pairs" }

    // Build the 8x8 linear system A*u = v for unknowns [a,b,c,d,e,f,g,h].
    val m = Array(8) { DoubleArray(9) }
    for (i in 0 until 4) {
        val (x, y) = from[i]
        val (xp, yp) = to[i]
        m[2 * i] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -x * xp, -y * xp, xp)
        m[2 * i + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -x * yp, -y * yp, yp)
    }

    val solved = gaussianEliminate(m) ?: return null
    return Homography(
        a = solved[0], b = solved[1], c = solved[2],
        d = solved[3], e = solved[4], f = solved[5],
        g = solved[6], h = solved[7],
    )
}

/** Gaussian elimination with partial pivoting on an 8x9 augmented matrix. Null if singular. */
private fun gaussianEliminate(input: Array<DoubleArray>): DoubleArray? {
    val n = input.size
    val m = Array(n) { input[it].copyOf() }

    for (col in 0 until n) {
        var pivotRow = col
        var maxAbs = kotlin.math.abs(m[col][col])
        for (row in col + 1 until n) {
            val v = kotlin.math.abs(m[row][col])
            if (v > maxAbs) { maxAbs = v; pivotRow = row }
        }
        if (maxAbs < 1e-10) return null
        if (pivotRow != col) { val tmp = m[col]; m[col] = m[pivotRow]; m[pivotRow] = tmp }

        val pivot = m[col][col]
        for (row in 0 until n) {
            if (row == col) continue
            val factor = m[row][col] / pivot
            if (factor == 0.0) continue
            for (k in col until n + 1) m[row][k] -= factor * m[col][k]
        }
    }

    return DoubleArray(n) { m[it][n] / m[it][it] }
}
