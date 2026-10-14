package com.mapmyroad.sensing.policy

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackDownsamplerTest {
    private val downsampler = TrackDownsampler(minIntervalMs = 1_000L, maxAccuracyM = 50f)

    @Test
    fun keepsAtMostOnePointPerInterval() {
        assertTrue(downsampler.shouldKeep(fixAtMs = 0L, accuracyM = 5f))
        assertFalse(downsampler.shouldKeep(fixAtMs = 999L, accuracyM = 5f))
        assertTrue(downsampler.shouldKeep(fixAtMs = 1_000L, accuracyM = 5f))
        assertFalse(downsampler.shouldKeep(fixAtMs = 1_500L, accuracyM = 5f))
        assertTrue(downsampler.shouldKeep(fixAtMs = 2_200L, accuracyM = 5f))
    }

    @Test
    fun rejectsInaccurateAndUnknownAccuracy() {
        assertFalse(downsampler.shouldKeep(fixAtMs = 0L, accuracyM = 50.5f))
        assertFalse(downsampler.shouldKeep(fixAtMs = 1_000L, accuracyM = null))
        assertTrue(downsampler.shouldKeep(fixAtMs = 2_000L, accuracyM = 50f))
    }

    @Test
    fun rejectedFix_doesNotMoveTheInterval() {
        assertTrue(downsampler.shouldKeep(fixAtMs = 0L, accuracyM = 5f))
        assertFalse(downsampler.shouldKeep(fixAtMs = 1_000L, accuracyM = 80f))
        assertTrue(downsampler.shouldKeep(fixAtMs = 1_100L, accuracyM = 5f))
    }

    @Test
    fun reset_keepsNextFix() {
        assertTrue(downsampler.shouldKeep(fixAtMs = 0L, accuracyM = 5f))
        downsampler.reset()
        assertTrue(downsampler.shouldKeep(fixAtMs = 100L, accuracyM = 5f))
    }
}
