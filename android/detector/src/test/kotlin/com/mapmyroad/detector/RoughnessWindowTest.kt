package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RoughnessWindowTest {
    @Test
    fun windows_haveTargetLength_andAreOrdered_andContiguous() {
        // 37 km/h: 10.28 m per 1 s fix, so a window closes after 5 segments (51.4 m); 60 segments give 12 windows.
        val trace = SyntheticTrace(durationS = 60.5, speedKmhAt = { 37.0 })
        val windows = trace.run(VehicleType.CAR).windows
        assertEquals(12, windows.size)
        for (window in windows) {
            assertTrue("length ${window.lengthM}", window.lengthM in 50.0..62.0)
            assertTrue(window.roughness >= 0.0)
            assertEquals(37.0, window.speedKmh, 0.5)
            assertTrue(window.endElapsedNanos > window.startElapsedNanos)
        }
        for (i in 1 until windows.size) {
            assertEquals(windows[i - 1].endElapsedNanos, windows[i].startElapsedNanos)
            assertEquals(windows[i - 1].endLat, windows[i].startLat, 0.0)
            assertEquals(windows[i - 1].endLon, windows[i].startLon, 0.0)
        }
    }

    @Test
    fun windowLength_matchesGeometry() {
        val trace = SyntheticTrace(durationS = 20.0, speedKmhAt = { 54.0 })
        for (window in trace.run(VehicleType.CAR).windows) {
            val straightM = TripScoring.haversineM(window.startLat, window.startLon, window.endLat, window.endLon)
            assertEquals(straightM, window.lengthM, 0.1)
        }
    }

    @Test
    fun roughPatch_hasHigherRoughness() {
        val trace = SyntheticTrace(durationS = 41.0, speedKmhAt = { 36.0 }, roughPatches = listOf(RoughPatch(20.0, 10.0, 5.0)))
        val windows = trace.run(VehicleType.CAR).windows
        val smooth = windows.filter { it.endElapsedNanos <= trace.baseElapsedNanos + 18_000_000_000L }.map { it.roughness }
        val rough =
            windows
                .filter {
                    it.startElapsedNanos >= trace.baseElapsedNanos + 20_000_000_000L &&
                        it.endElapsedNanos <= trace.baseElapsedNanos + 30_000_000_000L
                }
                .map { it.roughness }
        assertTrue(smooth.isNotEmpty() && rough.isNotEmpty())
        assertTrue("smooth $smooth rough $rough", rough.min() > 3.0 * smooth.max())
    }

    @Test
    fun flush_emitsPartialWindow_onlyWhenAtLeastMinimumLength() {
        // 37 km/h: 10.28 m per segment, windows of 5 segments. Fixes 0..18 s = 18 segments: 3 windows + 30.8 m partial.
        val longPartial = SyntheticTrace(durationS = 18.5, speedKmhAt = { 37.0 }).run(VehicleType.CAR).windows
        assertEquals(4, longPartial.size)
        assertEquals(30.8, longPartial.last().lengthM, 0.5)
        // Fixes 0..16 s = 16 segments: 3 windows + a 10.3 m partial that is dropped.
        val shortPartial = SyntheticTrace(durationS = 16.5, speedKmhAt = { 37.0 }).run(VehicleType.CAR).windows
        assertEquals(3, shortPartial.size)
        assertTrue(shortPartial.all { it.lengthM >= 50.0 })
    }

    @Test
    fun gpsGap_breaksTheWindow() {
        val trace = SyntheticTrace(durationS = 30.0, speedKmhAt = { 36.0 }, gpsDropouts = listOf(TimeInterval(12.5, 17.5)))
        val windows = trace.run(VehicleType.CAR).windows
        val gapStart = trace.baseElapsedNanos + 12_000_000_000L
        val gapEnd = trace.baseElapsedNanos + 18_000_000_000L
        assertTrue(windows.size >= 2)
        for (window in windows) {
            assertTrue("window must not span the gap", window.endElapsedNanos <= gapStart || window.startElapsedNanos >= gapEnd)
            assertTrue(window.lengthM >= 20.0 && window.lengthM <= 120.0)
        }
    }

    @Test
    fun stop_breaksTheWindow_andStationaryTimeProducesNothing() {
        val trace = SyntheticTrace(durationS = 40.0, speedKmhAt = { tS -> if (tS in 13.0..27.0) 0.0 else 36.0 })
        val windows = trace.run(VehicleType.CAR).windows
        for (window in windows) {
            val startS = (window.startElapsedNanos - trace.baseElapsedNanos) / 1.0e9
            val endS = (window.endElapsedNanos - trace.baseElapsedNanos) / 1.0e9
            assertTrue("window $startS..$endS overlaps the stop", endS <= 14.0 || startS >= 26.0)
        }
        assertTrue(windows.isNotEmpty())
    }

    @Test
    fun gpsJump_breaksTheWindow() {
        val config = DetectorConfig.DEFAULT
        val accumulator = RoughnessAccumulator(config, 1.0)
        val base = 1_000_000_000_000L
        repeat(30) { accumulator.addSample(0.5) }
        assertNull(accumulator.onFix(LocationFix(base, 0L, 18.0, 73.0, 10f, 5f, 0f), true))
        repeat(30) { accumulator.addSample(0.5) }
        assertNull(accumulator.onFix(LocationFix(base + 1_000_000_000L, 1_000L, 18.0003, 73.0, 10f, 5f, 0f), true))
        // 1 s later, 1 km away: implausible.
        repeat(30) { accumulator.addSample(0.5) }
        val partial = accumulator.onFix(LocationFix(base + 2_000_000_000L, 2_000L, 18.0093, 73.0, 10f, 5f, 0f), true)
        assertNotNull(partial)
        assertEquals(33.4, partial?.lengthM ?: 0.0, 0.5)
        assertNull(accumulator.flushPartial())
    }

    @Test
    fun roughness_isNormalizedBySpeedAndVehicle() {
        val config = DetectorConfig.DEFAULT
        val accumulator = RoughnessAccumulator(config, 0.7)
        val base = 1_000_000_000_000L
        assertNull(accumulator.onFix(LocationFix(base, 0L, 18.0, 73.0, 10f, 5f, 0f), true))
        repeat(100) { accumulator.addSample(if (it % 2 == 0) 1.0 else -1.0) }
        // 60 m in 2 s: 108 km/h.
        val window = accumulator.onFix(LocationFix(base + 2_000_000_000L, 2_000L, 18.0 + 60.0 / 111_195.08, 73.0, 30f, 5f, 0f), true)
        assertNotNull(window)
        assertEquals(108.0, window?.speedKmh ?: 0.0, 0.1)
        val expected = 1.0 / SpeedNormalization.roughnessSpeedFactor(108.0, config) * 0.7
        assertEquals(expected, window?.roughness ?: 0.0, 0.01)
    }
}
