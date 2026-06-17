package com.okapiorbits.sshotclassifier.pipeline.clip

/**
 * Groups near-duplicate images by CLIP embedding similarity. Exact duplicates are already
 * prevented at ingest (the content sha256 is a unique index), so this catches *visually*
 * near-identical shots: the same screen captured twice, a photo burst, a lightly cropped or
 * recompressed copy.
 *
 * Embeddings are L2-normalized, so cosine similarity is a dot product. Pairs at or above the
 * threshold are unioned into groups; only groups of two or more are returned. O(n^2) like the
 * existing brute-force search, which the scale test showed is fine to a few thousand images.
 *
 * Pure (no Android/DB), so the grouping is unit-tested directly.
 */
object DuplicateFinder {

    /** Default cosine cutoff for "near-duplicate". High, so only genuinely near-identical pairs group. */
    const val DEFAULT_THRESHOLD = 0.96f

    /**
     * @param items (screenshotId, L2-normalized embedding) pairs.
     * @return groups of ids that are mutually near-duplicate, each group sorted ascending,
     *   groups ordered by descending size then by first id. Singletons are omitted.
     */
    fun groups(items: List<Pair<Long, FloatArray>>, threshold: Float = DEFAULT_THRESHOLD): List<List<Long>> {
        val n = items.size
        if (n < 2) return emptyList()
        val parent = IntArray(n) { it }

        fun find(x: Int): Int {
            var r = x
            while (parent[r] != r) r = parent[r]
            var c = x
            while (parent[c] != c) { val nx = parent[c]; parent[c] = r; c = nx }
            return r
        }
        fun union(a: Int, b: Int) { parent[find(a)] = find(b) }

        for (i in 0 until n) {
            for (j in i + 1 until n) {
                if (dot(items[i].second, items[j].second) >= threshold) union(i, j)
            }
        }

        val byRoot = HashMap<Int, MutableList<Long>>()
        for (i in 0 until n) byRoot.getOrPut(find(i)) { mutableListOf() }.add(items[i].first)
        return byRoot.values
            .filter { it.size >= 2 }
            .map { it.sorted() }
            .sortedWith(compareByDescending<List<Long>> { it.size }.thenBy { it.first() })
    }

    private fun dot(a: FloatArray, b: FloatArray): Float {
        var s = 0f
        val n = minOf(a.size, b.size)
        for (i in 0 until n) s += a[i] * b[i]
        return s
    }
}
