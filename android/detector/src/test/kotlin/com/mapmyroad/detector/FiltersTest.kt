package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

class FiltersTest {
    @Test
    fun highPass_step_jumpsThenDecaysToZero() {
        val filter = HighPassFilter(0.5)
        val dtS = 0.01
        filter.filter(0.0, dtS)
        val first = filter.filter(1.0, dtS)
        assertTrue("step passes at first, got $first", first > 0.9)
        var last = first
        repeat(300) { last = filter.filter(1.0, dtS) }
        assertTrue("decays after 3 s (about 9 time constants), got $last", abs(last) < 0.01)
    }

    @Test
    fun highPass_firstSample_producesNoStep() {
        val filter = HighPassFilter(0.5)
        assertEquals(0.0, filter.filter(9.81, 0.01), 0.0)
        assertTrue(abs(filter.filter(9.81, 0.01)) < 1e-12)
    }

    @Test
    fun highPass_passesBumpBand_andRejectsSlowMotion() {
        assertTrue(steadyAmplitude(HighPassFilter(0.5)::filter, 10.0, 100.0) > 0.9)
        assertTrue(steadyAmplitude(HighPassFilter(0.5)::filter, 0.05, 100.0) < 0.15)
    }

    @Test
    fun highPass_gainDoesNotDependOnSampleRate() {
        val at50 = steadyAmplitude(HighPassFilter(0.5)::filter, 1.0, 50.0)
        val at200 = steadyAmplitude(HighPassFilter(0.5)::filter, 1.0, 200.0)
        assertEquals(at50, at200, 0.05)
        assertEquals(0.89, at200, 0.05)
    }

    @Test
    fun lowPass_step_settlesToInput() {
        val filter = LowPassFilter(LowPassFilter.timeConstantForCutoffS(22.0))
        filter.filter(0.0, 0.01)
        var last = 0.0
        repeat(20) { last = filter.filter(1.0, 0.01) }
        assertEquals(1.0, last, 1e-6)
    }

    @Test
    fun lowPass_impulse_keepsAreaAndSpreadsPeak() {
        val filter = LowPassFilter(LowPassFilter.timeConstantForCutoffS(22.0))
        val dtS = 0.01
        filter.filter(0.0, dtS)
        var peak = 0.0
        var area = filter.filter(1.0 / dtS, dtS) * dtS
        peak = max(peak, area / dtS)
        repeat(100) {
            val y = filter.filter(0.0, dtS)
            area += y * dtS
            peak = max(peak, y)
        }
        assertEquals("unit DC gain keeps the impulse area", 1.0, area, 0.01)
        assertTrue("peak is smoothed", peak < 1.0 / dtS)
    }

    @Test
    fun lowPass_passesLowFrequency_andAttenuatesVibration() {
        val corner = LowPassFilter.timeConstantForCutoffS(22.0)
        assertTrue(steadyAmplitude(LowPassFilter(corner)::filter, 2.0, 100.0) > 0.97)
        assertTrue(steadyAmplitude(LowPassFilter(corner)::filter, 45.0, 100.0) < 0.7)
    }

    @Test
    fun rollingRms_constantSignal_isThatValue() {
        val rms = RollingRms(capacity = 100, windowNanos = 1_000_000_000L)
        for (i in 0 until 50) rms.add(i * 10_000_000L, -2.0)
        assertEquals(2.0, rms.rms(), 1e-9)
    }

    @Test
    fun rollingRms_evictsSamplesOutsideWindow() {
        val rms = RollingRms(capacity = 1_000, windowNanos = 1_000_000_000L)
        for (i in 0 until 100) rms.add(i * 10_000_000L, 5.0)
        for (i in 100 until 300) rms.add(i * 10_000_000L, 1.0)
        assertEquals(1.0, rms.rms(), 1e-9)
        assertEquals(100, rms.size())
    }

    @Test
    fun rollingRms_overflow_dropsOldestWithoutGrowing() {
        val rms = RollingRms(capacity = 10, windowNanos = 10_000_000_000L)
        for (i in 0 until 10) rms.add(i * 1_000_000L, 4.0)
        for (i in 10 until 20) rms.add(i * 1_000_000L, 3.0)
        assertEquals(10, rms.size())
        assertEquals(3.0, rms.rms(), 1e-9)
    }

    @Test
    fun rollingRms_longRun_staysAccurate() {
        val rms = RollingRms(capacity = 300, windowNanos = 3_000_000_000L)
        for (i in 0 until 200_000) rms.add(i * 10_000_000L, if (i % 2 == 0) 1e3 else 1e-3)
        for (i in 200_000 until 200_300) rms.add(i * 10_000_000L, 0.5)
        assertEquals(0.5, rms.rms(), 1e-9)
    }

    @Test
    fun rollingRms_maxAbsBetween_findsPeakInRange() {
        val rms = RollingRms(capacity = 100, windowNanos = 10_000_000_000L)
        rms.add(1L, 1.0)
        rms.add(2L, -7.0)
        rms.add(3L, 3.0)
        assertEquals(7.0, rms.maxAbsBetween(2L, 3L), 1e-12)
        assertEquals(3.0, rms.maxAbsBetween(3L, 10L), 1e-12)
        assertEquals(0.0, rms.maxAbsBetween(4L, 10L), 0.0)
    }

    private fun steadyAmplitude(
        filter: (Double, Double) -> Double,
        frequencyHz: Double,
        sampleRateHz: Double,
    ): Double {
        val dtS = 1.0 / sampleRateHz
        val settleSamples = (sampleRateHz * max(10.0, 10.0 / frequencyHz)).toInt()
        val measureSamples = (sampleRateHz * max(2.0, 3.0 / frequencyHz)).toInt()
        var peak = 0.0
        for (i in 0 until settleSamples + measureSamples) {
            val y = filter(sin(2.0 * PI * frequencyHz * i * dtS), dtS)
            if (i >= settleSamples) peak = max(peak, abs(y))
        }
        return peak
    }
}
