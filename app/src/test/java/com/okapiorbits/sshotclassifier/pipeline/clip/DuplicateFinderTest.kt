package com.okapiorbits.sshotclassifier.pipeline.clip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import kotlin.math.sqrt
import org.junit.Test

class DuplicateFinderTest {

    private fun norm(vararg v: Float): FloatArray {
        val n = sqrt(v.sumOf { (it * it).toDouble() }).toFloat()
        return FloatArray(v.size) { v[it] / n }
    }

    @Test
    fun groupsNearIdenticalPairsAndOmitsSingletons() {
        val a1 = norm(1f, 0f, 0f)
        val a2 = norm(0.999f, 0.04f, 0f) // ~identical to a1
        val b = norm(0f, 1f, 0f)         // distinct
        val groups = DuplicateFinder.groups(listOf(1L to a1, 2L to a2, 3L to b), threshold = 0.96f)
        assertEquals(1, groups.size)
        assertEquals(listOf(1L, 2L), groups[0])
    }

    @Test
    fun transitiveChainFormsOneGroup() {
        // a~b and b~c (but a and c slightly further) still union into one group.
        val a = norm(1f, 0f, 0f)
        val b = norm(0.99f, 0.14f, 0f)
        val c = norm(0.96f, 0.28f, 0f)
        val groups = DuplicateFinder.groups(listOf(10L to a, 11L to b, 12L to c), threshold = 0.95f)
        assertEquals(1, groups.size)
        assertEquals(listOf(10L, 11L, 12L), groups[0])
    }

    @Test
    fun nothingGroupsWhenAllDistinct() {
        val groups = DuplicateFinder.groups(
            listOf(1L to norm(1f, 0f, 0f), 2L to norm(0f, 1f, 0f), 3L to norm(0f, 0f, 1f)),
        )
        assertTrue(groups.isEmpty())
    }

    @Test
    fun emptyAndSingleInputReturnNoGroups() {
        assertTrue(DuplicateFinder.groups(emptyList()).isEmpty())
        assertTrue(DuplicateFinder.groups(listOf(1L to norm(1f, 0f))).isEmpty())
    }

    @Test
    fun largerGroupsSortFirst() {
        val x = norm(1f, 0f, 0f)
        val xs = listOf(1L to x, 2L to x.copyOf(), 3L to x.copyOf()) // group of 3
        val y = norm(0f, 1f, 0f)
        val ys = listOf(8L to y, 9L to y.copyOf())                   // group of 2
        val groups = DuplicateFinder.groups(xs + ys, threshold = 0.99f)
        assertEquals(2, groups.size)
        assertEquals(3, groups[0].size)
        assertEquals(2, groups[1].size)
    }
}
