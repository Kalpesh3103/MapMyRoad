package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TripScoringTest {
    private fun window(
        lengthM: Double,
        roughness: Double,
    ) = RoughnessWindow(0L, 1L, 0L, 18.0, 73.0, 18.0, 73.0, lengthM, roughness, 30.0)

    @Test
    fun haversine_puneToMumbai_isAbout120Km() {
        val distanceM = TripScoring.haversineM(18.5204, 73.8567, 19.0760, 72.8777)
        assertTrue("got $distanceM", distanceM in 115_000.0..125_000.0)
    }

    @Test
    fun haversine_oneDegreeOfLatitude_andZero() {
        assertEquals(111_195.08, TripScoring.haversineM(18.0, 73.0, 19.0, 73.0), 1.0)
        assertEquals(0.0, TripScoring.haversineM(18.0, 73.0, 18.0, 73.0), 0.0)
    }

    @Test
    fun roughnessToScore_isBoundedAndMonotonic() {
        val config = DetectorConfig.DEFAULT
        assertEquals(100, TripScoring.roughnessToScore(0.0, config))
        assertEquals(100, TripScoring.roughnessToScore(config.roughnessGood, config))
        assertEquals(0, TripScoring.roughnessToScore(config.roughnessPoor, config))
        assertEquals(0, TripScoring.roughnessToScore(50.0, config))
        assertEquals(0, TripScoring.roughnessToScore(Double.NaN, config))
        var previous = 100
        var roughness = 0.0
        while (roughness < 3.0) {
            val score = TripScoring.roughnessToScore(roughness, config)
            assertTrue(score in 0..previous)
            previous = score
            roughness += 0.01
        }
    }

    @Test
    fun smoothnessScore_isNullWhenTooShort() {
        assertNull(TripScoring.smoothnessScore(emptyList()))
        assertNull(TripScoring.smoothnessScore(listOf(window(50.0, 0.1), window(50.0, 0.1), window(50.0, 0.1))))
    }

    @Test
    fun smoothnessScore_isLengthWeighted() {
        val config = DetectorConfig.DEFAULT
        // 100 m scoring 100 and 300 m scoring 0: 25.
        val windows = listOf(window(100.0, 0.0), window(100.0, 5.0), window(100.0, 5.0), window(100.0, 5.0))
        assertEquals(25, TripScoring.smoothnessScore(windows, config))
        assertEquals(100, TripScoring.smoothnessScore(List(5) { window(50.0, 0.1) }, config))
    }
}
