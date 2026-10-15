package com.mapmyroad.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos

class TrackProximityTest {
    // A straight track going north along lon 73.8: point i is about 10 m * i from the start, at t = i s.
    private val track = (0..10).map { testPoint(it) }

    /** Degrees of longitude for [meters] east at the track latitude. */
    private fun eastDeg(meters: Double): Double = meters / (111_195.0 * cos(Math.toRadians(18.5)))

    @Test
    fun emptyTrack_hasNoMatch() {
        assertNull(TrackProximity.nearest(emptyList(), 18.5, 73.8))
    }

    @Test
    fun singlePoint_isDistanceToThatPoint() {
        val match = TrackProximity.nearest(listOf(testPoint(0)), 18.5, 73.8 + eastDeg(20.0))
        assertEquals(20.0, match?.distanceM ?: -1.0, 0.1)
        assertEquals(0L, match?.tMs)
    }

    @Test
    fun pointBesideASegment_projectsOntoIt() {
        // Halfway between points 4 and 5 (about 45 m from the start), 12 m east of the track.
        val lat = 18.5 + 4.5 * DEG_PER_10_M
        val match = TrackProximity.nearest(track, lat, 73.8 + eastDeg(12.0))
        requireNotNull(match)
        assertEquals(12.0, match.distanceM, 0.1)
        assertEquals(lat, match.lat, 1e-9)
        assertEquals(73.8, match.lon, 1e-9)
        assertEquals(4_500L, match.tMs)
        assertEquals(4.5 * DEG_PER_10_M * 111_195.0, match.alongTrackM, 0.1)
    }

    @Test
    fun pointBeyondTheEnd_clampsToTheLastPoint() {
        val lat = 18.5 + 12 * DEG_PER_10_M
        val match = TrackProximity.nearest(track, lat, 73.8)
        requireNotNull(match)
        assertEquals(2 * DEG_PER_10_M * 111_195.0, match.distanceM, 0.1)
        assertEquals(10_000L, match.tMs)
    }

    @Test
    fun repeatedPoints_doNotBreakProjection() {
        val stopped = listOf(testPoint(0), testPoint(0), testPoint(1))
        val match = TrackProximity.nearest(stopped, 18.5, 73.8 + eastDeg(5.0))
        assertEquals(5.0, match?.distanceM ?: -1.0, 0.1)
    }

    @Test
    fun matchedPoint_isAlwaysCloserThanTheQueryDistanceToTheHazard() {
        // The feedback location must stay within the server's 50 m: it is on the track, distanceM from the hazard.
        val match = TrackProximity.nearest(track, 18.5 + 7.3 * DEG_PER_10_M, 73.8 + eastDeg(24.0))
        requireNotNull(match)
        assertTrue(match.distanceM <= 25.0)
        assertEquals("TrackMatch(***)", match.toString())
    }
}
