package com.okapiorbits.sshotclassifier.data.media

import org.junit.Assert.assertEquals
import org.junit.Test

class ReorganizationTest {

    @Test
    fun confidentTag_goesToItsAlbum() {
        assertEquals("map", Reorganization.albumFor(needsReview = false, topLabel = "map"))
    }

    @Test
    fun needsReview_goesToUncategorized() {
        assertEquals(
            Reorganization.UNCATEGORIZED,
            Reorganization.albumFor(needsReview = true, topLabel = "map"),
        )
    }

    @Test
    fun noTag_goesToUncategorized() {
        assertEquals(Reorganization.UNCATEGORIZED, Reorganization.albumFor(needsReview = false, topLabel = null))
        assertEquals(Reorganization.UNCATEGORIZED, Reorganization.albumFor(needsReview = false, topLabel = "  "))
    }

    @Test
    fun slashesAndPunctuationStrippedFromFolderName() {
        // "error / crash" and "chat / messaging" must not create nested folders.
        assertEquals("error crash", Reorganization.sanitizeFolder("error / crash"))
        assertEquals("chat messaging", Reorganization.sanitizeFolder("chat / messaging"))
        assertEquals("browser web", Reorganization.sanitizeFolder("browser / web"))
    }

    @Test
    fun keepsLettersDigitsSpaceDashUnderscore() {
        assertEquals("code editor", Reorganization.sanitizeFolder("Code Editor"))
        assertEquals("street-map_2", Reorganization.sanitizeFolder("street-map_2"))
    }

    @Test
    fun blankAfterSanitizeFallsBackToUncategorized() {
        assertEquals(Reorganization.UNCATEGORIZED, Reorganization.sanitizeFolder("///"))
    }

    // ---- needs-review handling preference ----

    @Test
    fun needsReviewSkipped_returnsNull_whenUserOptsOut() {
        // needsReviewToUncategorized = false means "don't file low-confidence shots".
        assertEquals(
            null,
            Reorganization.albumFor(needsReview = true, topLabel = "map", needsReviewToUncategorized = false),
        )
    }

    @Test
    fun needsReviewToUncategorized_whenEnabled() {
        assertEquals(
            Reorganization.UNCATEGORIZED,
            Reorganization.albumFor(needsReview = true, topLabel = "map", needsReviewToUncategorized = true),
        )
    }

    @Test
    fun confidentTag_unaffectedByNeedsReviewPreference() {
        assertEquals("map", Reorganization.albumFor(needsReview = false, topLabel = "map", needsReviewToUncategorized = false))
        assertEquals("map", Reorganization.albumFor(needsReview = false, topLabel = "map", needsReviewToUncategorized = true))
    }

    // ---- album root sanitization ----

    @Test
    fun blankRootFallsBackToDefault() {
        assertEquals(Reorganization.ROOT, Reorganization.sanitizeRoot(""))
        assertEquals(Reorganization.ROOT, Reorganization.sanitizeRoot("   "))
    }

    @Test
    fun rootSanitizedLikeAFolder() {
        assertEquals("my shots", Reorganization.sanitizeRoot("My/Shots"))
        assertEquals("screenshots_2026", Reorganization.sanitizeRoot("screenshots_2026"))
    }
}
