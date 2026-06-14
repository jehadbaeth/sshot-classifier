package com.okapiorbits.sshotclassifier.data.repository

/**
 * Pure, dependency-free helpers for hybrid search. Kept separate from
 * [ScreenshotRepository] so the genuinely error-prone bits (rank fusion, query
 * sanitization, result reordering) are unit-testable on the JVM without Room,
 * Android, or the CLIP models.
 */
internal object SearchFusion {

    /**
     * Reciprocal rank fusion over several ranked id lists. Each list contributes
     * 1/(k + rank + 1) to an id's score; ids are returned by fused score, highest
     * first. Ties keep first-seen order (stable sort). Duplicates within a single
     * list only count at their first (best) position. RRF is used instead of a
     * weighted score merge because the inputs (CLIP cosine vs a binary FTS hit)
     * live on incompatible scales; fusing by rank sidesteps that entirely.
     */
    fun reciprocalRankFusion(rankings: List<List<Long>>, k: Int = 60): List<Long> {
        val scores = LinkedHashMap<Long, Double>()
        for (ranking in rankings) {
            val seen = HashSet<Long>()
            ranking.forEachIndexed { rank, id ->
                if (seen.add(id)) {
                    scores[id] = (scores[id] ?: 0.0) + 1.0 / (k + rank + 1)
                }
            }
        }
        return scores.entries
            .sortedByDescending { it.value }
            .map { it.key }
    }

    /**
     * Turns free user text into a safe FTS4 MATCH expression: lowercase, drop
     * everything that is not a letter, number, or whitespace, then prefix-match
     * each remaining token (implicit AND). Returns null when nothing usable
     * remains, so the caller can skip the OCR branch entirely.
     */
    fun toFtsPrefixQuery(raw: String): String? {
        val tokens = raw.lowercase()
            .replace(Regex("""[^\p{L}\p{N}\s]"""), " ")
            .split(Regex("""\s+"""))
            .filter { it.isNotBlank() }
        if (tokens.isEmpty()) return null
        return tokens.joinToString(" ") { "$it*" }
    }

    /**
     * Reorders rows fetched by a `WHERE id IN (...)` query (which returns them in
     * arbitrary order) back into the fused ranking, dropping any id with no row.
     */
    fun <T> reorderTo(rankedIds: List<Long>, byId: Map<Long, T>): List<T> =
        rankedIds.mapNotNull { byId[it] }
}
