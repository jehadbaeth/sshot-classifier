package com.okapiorbits.sshotclassifier.pipeline.clip

import com.okapiorbits.sshotclassifier.pipeline.TagCandidate
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Fuses CLIP zero-shot scores with OCR heuristic candidates per the spike's
 * conclusion (docs/spikes/clip-findings.md): CLIP is trusted for visually
 * distinctive categories, OCR for text-heavy ones, and a margin gate decides
 * whether the top tag is confident enough to be treated as primary.
 */
@Singleton
class TagFuser @Inject constructor() {

    /** Categories where the screen content (text) is the reliable signal, not CLIP vision. */
    private val ocrAuthoritative = setOf(
        "code editor", "error / crash", "receipt", "finance", "calendar",
        "chat / messaging", "document", "shopping", "news", "browser / web",
        // Email and forum/reddit screens are text-heavy; CLIP misreads them as
        // documents, so OCR carries these. Without this they would be downweighted
        // to a 0.15 nudge and lose to CLIP's "document" vote.
        "email", "social media",
    )

    /** Result of fusion: weighted tags plus whether the top tag cleared the gate. */
    data class Result(val tags: List<TagCandidate>, val primaryConfident: Boolean)

    fun fuse(
        clipScores: Map<String, Float>,
        ocrCandidates: List<TagCandidate>,
    ): Result {
        val ocr = ocrCandidates.associate { it.label to it.weight }
        val tags = (clipScores.keys + ocr.keys).toSet()

        val fused = tags.associateWith { tag ->
            val clip = clipScores[tag] ?: 0f
            val o = ocr[tag] ?: 0f
            if (tag in ocrAuthoritative) {
                // Trust OCR more for text-heavy categories where CLIP is unreliable.
                0.4f * clip + 0.6f * o
            } else {
                // Visually distinctive categories: trust CLIP, small OCR nudge.
                0.85f * clip + 0.15f * o
            }
        }.filter { it.value > 0f }

        val sorted = fused.entries.sortedByDescending { it.value }
        if (sorted.isEmpty()) return Result(emptyList(), false)

        val top = sorted[0]
        val second = sorted.getOrNull(1)?.value ?: 0f
        val margin = top.value - second
        val ocrCorroborates = (ocr[top.key] ?: 0f) >= 0.34f
        val primaryConfident = (margin >= MARGIN) || ocrCorroborates

        val kept = sorted
            .filter { it.value >= KEEP_THRESHOLD }
            .take(MAX_TAGS)
            .map { TagCandidate(it.key, it.value) }

        return Result(kept, primaryConfident)
    }

    companion object {
        private const val MARGIN = 0.15f
        private const val KEEP_THRESHOLD = 0.10f
        private const val MAX_TAGS = 5
    }
}
