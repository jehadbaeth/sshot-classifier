package com.okapiorbits.sshotclassifier.data.prefs

import com.okapiorbits.sshotclassifier.data.media.Reorganization

/** How reorganization places files. */
enum class ReorgMode {
    /** Copy into albums, originals untouched (non-destructive). */
    COPY,

    /** Copy into albums, then delete the originals (needs user consent; API 30+). */
    MOVE,
}

/**
 * User-configurable reorganization behaviour. Defaults reproduce the original
 * hardcoded behaviour: non-destructive copy, the "ScreenshotClassifier" album root,
 * needs-review screenshots routed to "uncategorized", and no automatic runs.
 */
data class ReorgPreferences(
    val mode: ReorgMode = ReorgMode.COPY,
    val albumRoot: String = Reorganization.ROOT,
    /** true: needs-review screenshots go to the "uncategorized" album; false: skip them. */
    val needsReviewToUncategorized: Boolean = true,
    /** Copy new screenshots into albums automatically after each processing pass. */
    val autoRun: Boolean = false,
)
