package com.mapmyroad.sensing.policy

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDistanceAccumulatorTest {
    private val accumulator =
        LiveDistanceAccumulator(
            maxAccuracyM = 20f,
            minStepM = 3.0,
            maxPlausibleSpeedMps = 55.0,
            maxConsecutiveRejects = 3,
            distanceCalculator = TestDistance,
        )

    private fun latAtM(northM: Double) = BASE_LAT + northM * DEG_PER_M

    @Test
    fun accumulatesConsecutiveSteps() {
        accumulator.onFix(0L, latAtM(0.0), BASE_LON, 5f)
        for (i in 1..10) assertTrue(accumulator.onFix(i * 1_000L, latAtM(i * 10.0), BASE_LON, 5f))
        assertEquals(100.0, accumulator.distanceM, 0.5)
    }

    @Test
    fun ignoresInaccurateFixes() {
        accumulator.onFix(0L, latAtM(0.0), BASE_LON, 5f)
        assertFalse(accumulator.onFix(1_000L, latAtM(10.0), BASE_LON, 25f))
        assertFalse(accumulator.onFix(2_000L, latAtM(20.0), BASE_LON, null))
        assertTrue(accumulator.onFix(3_000L, latAtM(30.0), BASE_LON, 5f))
        assertEquals(30.0, accumulator.distanceM, 0.5)
    }

    @Test
    fun rejectsImplausibleJump() {
        accumulator.onFix(0L, latAtM(0.0), BASE_LON, 5f)
        // 500 m in 1 s is 1800 km/h.
        assertFalse(accumulator.onFix(1_000L, latAtM(500.0), BASE_LON, 5f))
        assertTrue(accumulator.onFix(2_000L, latAtM(20.0), BASE_LON, 5f))
        assertEquals(20.0, accumulator.distanceM, 0.5)
    }

    @Test
    fun reanchorsAfterRepeatedRejections_withoutAddingDistance() {
        accumulator.onFix(0L, latAtM(0.0), BASE_LON, 5f)
        assertFalse(accumulator.onFix(1_000L, latAtM(5_000.0), BASE_LON, 5f))
        assertFalse(accumulator.onFix(2_000L, latAtM(5_010.0), BASE_LON, 5f))
        assertFalse(accumulator.onFix(3_000L, latAtM(5_020.0), BASE_LON, 5f))
        // Re-anchored at 5020 m: the next step counts.
        assertTrue(accumulator.onFix(4_000L, latAtM(5_030.0), BASE_LON, 5f))
        assertEquals(10.0, accumulator.distanceM, 0.5)
    }

    @Test
    fun jitterBelowMinStep_isIgnored_butSlowMotionAddsUp() {
        accumulator.onFix(0L, latAtM(0.0), BASE_LON, 5f)
        assertFalse(accumulator.onFix(1_000L, latAtM(1.0), BASE_LON, 5f))
        assertFalse(accumulator.onFix(2_000L, latAtM(2.0), BASE_LON, 5f))
        assertTrue(accumulator.onFix(3_000L, latAtM(3.5), BASE_LON, 5f))
        assertEquals(3.5, accumulator.distanceM, 0.1)
    }

    @Test
    fun breakSegment_skipsTheGap() {
        accumulator.onFix(0L, latAtM(0.0), BASE_LON, 5f)
        accumulator.onFix(1_000L, latAtM(10.0), BASE_LON, 5f)
        accumulator.breakSegment()
        assertFalse(accumulator.onFix(60_000L, latAtM(1_000.0), BASE_LON, 5f))
        assertTrue(accumulator.onFix(61_000L, latAtM(1_010.0), BASE_LON, 5f))
        assertEquals(20.0, accumulator.distanceM, 0.5)
    }
}
