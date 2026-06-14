package com.okapiorbits.sshotclassifier.data.media

/**
 * Pure helpers for the copy-into-albums reorganization. Kept dependency-free so
 * the album-naming and gating rules are unit-testable without MediaStore.
 */
object Reorganization {

    const val ROOT = "ScreenshotClassifier"
    const val UNCATEGORIZED = "uncategorized"

    /**
     * Album for a screenshot. [needsReview] is the persisted margin+OCR gate result
     * (true == not confidently tagged), so uncertain or untagged images go to
     * "uncategorized" rather than a confidently-wrong folder. Otherwise the top tag.
     */
    fun albumFor(needsReview: Boolean, topLabel: String?): String =
        if (needsReview || topLabel.isNullOrBlank()) UNCATEGORIZED else sanitizeFolder(topLabel)

    /**
     * Makes a tag label safe as a single folder name: lowercase, drop characters
     * that aren't letters/digits/space/_/- (e.g. the "/" in "error / crash" or
     * "chat / messaging"), and collapse whitespace. Never returns blank.
     */
    fun sanitizeFolder(label: String): String {
        val cleaned = label.lowercase()
            .replace(Regex("[^a-z0-9 _-]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        return cleaned.ifBlank { UNCATEGORIZED }
    }
}
