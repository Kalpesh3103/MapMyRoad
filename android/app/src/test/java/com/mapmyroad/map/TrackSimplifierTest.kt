package com.mapmyroad.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackSimplifierTest {
    @Test
    fun shortLists_areReturnedUnchanged() {
        val two = listOf(testPoint(0), testPoint(1, lat = 18.5))
        assertSame(two, TrackSimplifier.simplify(two))
        assertTrue(TrackSimplifier.simplify(emptyList()).isEmpty())
    }

    @Test
    fun pointsCloserThanSpacing_areDropped_firstAndLastKept() {
        // One point per meter over 20 m.
        val points = (0..20).map { testPoint(it, lat = 18.5 + it * DEG_PER_10_M / 10.0) }
        val simplified = TrackSimplifier.simplify(points, minSpacingM = 5.0)

        assertSame(points.first(), simplified.first())
        assertSame(points.last(), simplified.last())
        assertEquals(5, simplified.size)
        for (index in 1 until simplified.size) {
            assertTrue(TrackSimplifier.approxDistanceM(simplified[index - 1], simplified[index]) >= 4.9)
        }
    }

    @Test
    fun widelySpacedPoints_areAllKept() {
        val points = (0 until 6).map { testPoint(it) }
        assertEquals(points, TrackSimplifier.simplify(points, minSpacingM = 5.0))
    }

    @Test
    fun identicalPoints_collapseToFirstAndLast() {
        val points = (0 until 5).map { testPoint(it, lat = 18.5) }
        val simplified = TrackSimplifier.simplify(points)
        assertEquals(listOf(points.first(), points.last()), simplified)
    }

    @Test
    fun approxDistance_matchesKnownLatitudeSpacing() {
        val distance = TrackSimplifier.approxDistanceM(testPoint(0, lat = 18.5), testPoint(1, lat = 18.501))
        assertEquals(111.2, distance, 0.5)
    }
}
