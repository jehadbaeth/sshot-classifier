package com.okapiorbits.sshotclassifier.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SearchFusionTest {

    // ---- reciprocalRankFusion ----

    @Test
    fun singleRanking_preservesOrder() {
        val fused = SearchFusion.reciprocalRankFusion(listOf(listOf(3L, 1L, 2L)))
        assertEquals(listOf(3L, 1L, 2L), fused)
    }

    @Test
    fun overlap_promotesIdAppearingHighInBothLists() {
        // id 9 is rank 0 in list A and rank 0 in list B -> must end up first.
        val visual = listOf(9L, 5L, 7L)
        val text = listOf(9L, 8L)
        val fused = SearchFusion.reciprocalRankFusion(listOf(visual, text))
        assertEquals(9L, fused.first())
        // every id appears exactly once
        assertEquals(fused.size, fused.toSet().size)
        assertEquals(setOf(9L, 5L, 7L, 8L), fused.toSet())
    }

    @Test
    fun idInBothListsBeatsHigherRankedSingletons() {
        // 2 is rank1+rank1 (appears twice); 1 is rank0 in only one list.
        // 1/(60+2) + 1/(60+2) = 0.03226 > 1/(60+1) = 0.01639, so 2 wins.
        val a = listOf(1L, 2L)
        val b = listOf(3L, 2L)
        val fused = SearchFusion.reciprocalRankFusion(listOf(a, b))
        assertEquals(2L, fused.first())
    }

    @Test
    fun duplicateWithinOneList_countsOnlyBestPosition() {
        // If 5 is listed twice in the same ranking it must not be double-counted
        // nor appear twice in the output.
        val withDup = listOf(5L, 1L, 5L)
        val fused = SearchFusion.reciprocalRankFusion(listOf(withDup))
        assertEquals(listOf(5L, 1L), fused)
    }

    @Test
    fun emptyInputs_returnEmpty() {
        assertEquals(emptyList<Long>(), SearchFusion.reciprocalRankFusion(emptyList()))
        assertEquals(
            emptyList<Long>(),
            SearchFusion.reciprocalRankFusion(listOf(emptyList(), emptyList())),
        )
    }

    @Test
    fun oneEmptyOneFull_returnsTheFullRanking() {
        val fused = SearchFusion.reciprocalRankFusion(listOf(emptyList(), listOf(4L, 6L)))
        assertEquals(listOf(4L, 6L), fused)
    }

    // ---- toFtsPrefixQuery ----

    @Test
    fun query_becomesPrefixedAndedTokens() {
        assertEquals("hello* world*", SearchFusion.toFtsPrefixQuery("hello world"))
    }

    @Test
    fun query_lowercasesAndStripsPunctuation() {
        assertEquals("error* 404*", SearchFusion.toFtsPrefixQuery("Error 404:"))
    }

    @Test
    fun query_collapsesRepeatedAndEdgeWhitespace() {
        assertEquals("a* b*", SearchFusion.toFtsPrefixQuery("  a   b  "))
    }

    @Test
    fun query_punctuationOnly_isNull() {
        assertNull(SearchFusion.toFtsPrefixQuery("!@#  $%^"))
    }

    @Test
    fun query_blank_isNull() {
        assertNull(SearchFusion.toFtsPrefixQuery("   "))
    }

    @Test
    fun query_keepsUnicodeLetters() {
        // accented letters are \p{L}, must survive
        assertTrue(SearchFusion.toFtsPrefixQuery("café")!!.startsWith("café"))
    }

    // ---- reorderTo ----

    @Test
    fun reorder_followsRankedOrderAndDropsMissing() {
        val byId = mapOf(2L to "b", 1L to "a", 3L to "c")
        // 9 has no row -> dropped; order follows the ranked id list, not the map.
        assertEquals(listOf("c", "a", "b"), SearchFusion.reorderTo(listOf(3L, 9L, 1L, 2L), byId))
    }

    @Test
    fun reorder_emptyRanking_isEmpty() {
        assertEquals(emptyList<String>(), SearchFusion.reorderTo(emptyList(), mapOf(1L to "a")))
    }
}
