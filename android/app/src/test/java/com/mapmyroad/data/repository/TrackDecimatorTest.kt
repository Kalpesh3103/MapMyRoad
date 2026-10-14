package com.mapmyroad.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackDecimatorTest {
    @Test
    fun shortList_isReturnedUnchanged() {
        val points = (0 until 10).toList()

        assertSame(points, TrackDecimator.decimate(points, maxPoints = 10))
    }

    @Test
    fun longList_isCappedAndKeepsFirstAndLast() {
        val points = (0 until 10_001).toList()

        val decimated = TrackDecimator.decimate(points)

        assertEquals(TrackDecimator.MAX_POINTS_PER_TRIP, decimated.size)
        assertEquals(0, decimated.first())
        assertEquals(10_000, decimated.last())
        assertTrue(decimated.zipWithNext().all { (a, b) -> b > a })
    }

    @Test
    fun listOneLongerThanMax_isCapped() {
        val points = (0 until 6).toList()

        val decimated = TrackDecimator.decimate(points, maxPoints = 5)

        assertEquals(5, decimated.size)
        assertEquals(0, decimated.first())
        assertEquals(5, decimated.last())
        assertTrue(decimated.zipWithNext().all { (a, b) -> b > a })
    }

    @Test(expected = IllegalArgumentException::class)
    fun maxPointsBelowTwo_isRejected() {
        TrackDecimator.decimate(listOf(1, 2, 3), maxPoints = 1)
    }
}
