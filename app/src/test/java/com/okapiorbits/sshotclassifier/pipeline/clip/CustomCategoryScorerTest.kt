package com.okapiorbits.sshotclassifier.pipeline.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CustomCategoryScorerTest {

    private fun axis(i: Int, dim: Int = 8) = FloatArray(dim).also { it[i] = 1f }

    @Test
    fun keepsOnlyCategoriesAtOrAboveThreshold() {
        val image = axis(0)
        val cats = listOf(
            CustomCategoryScorer.Category("aligned", axis(0)),      // cosine 1.0
            CustomCategoryScorer.Category("orthogonal", axis(1)),   // cosine 0.0
        )
        val hits = CustomCategoryScorer.score(image, cats, threshold = 0.5f)
        assertEquals(listOf("aligned"), hits.map { it.label })
        assertEquals(1.0f, hits.first().weight, 1e-6f)
    }

    @Test
    fun sortsByCosineDescending() {
        // image at 45 deg between axes 0 and 1; closer to a partial-overlap category.
        val image = FloatArray(8).also { it[0] = 0.6f; it[1] = 0.8f }
        val cats = listOf(
            CustomCategoryScorer.Category("weak", axis(0)),   // cosine 0.6
            CustomCategoryScorer.Category("strong", axis(1)), // cosine 0.8
        )
        val hits = CustomCategoryScorer.score(image, cats, threshold = 0.1f)
        assertEquals(listOf("strong", "weak"), hits.map { it.label })
        assertTrue(hits[0].weight > hits[1].weight)
    }

    @Test
    fun noCategories_returnsEmpty() {
        assertTrue(CustomCategoryScorer.score(axis(0), emptyList()).isEmpty())
    }

    @Test
    fun allBelowThreshold_returnsEmpty() {
        val hits = CustomCategoryScorer.score(axis(0), listOf(CustomCategoryScorer.Category("x", axis(1))))
        assertTrue(hits.isEmpty())
    }
}
