package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PositionInterpolatorTest {
    private val config = DetectorConfig.DEFAULT
    private val baseNanos = 1_000_000_000_000L

    private fun fix(
        tMs: Long,
        lat: Double,
        lon: Double,
        speedMps: Float? = 10f,
        bearingDeg: Float? = 0f,
    ) = LocationFix(baseNanos + tMs * 1_000_000L, 1_760_000_000_000L + tMs, lat, lon, speedMps, 5f, bearingDeg)

    @Test
    fun noFix_cannotLocate() {
        assertFalse(PositionInterpolator(config).locate(baseNanos, EventPosition()))
    }

    @Test
    fun betweenFixes_interpolatesLinearlyByTime() {
        val interpolator = PositionInterpolator(config)
        interpolator.onFix(fix(0, 18.0, 73.0))
        interpolator.onFix(fix(1_000, 18.0001, 73.0002))
        val out = EventPosition()
        assertTrue(interpolator.locate(baseNanos + 250_000_000L, out))
        assertEquals(18.000025, out.lat, 1e-9)
        assertEquals(73.00005, out.lon, 1e-9)
        assertEquals(1_760_000_000_250L, out.wallTimeMs)
        assertEquals(0, out.headingDeg)
    }

    @Test
    fun afterNewestFix_extrapolatesBySpeedAndBearing() {
        val interpolator = PositionInterpolator(config)
        interpolator.onFix(fix(0, 18.0, 73.0, speedMps = 10f, bearingDeg = 0f))
        val out = EventPosition()
        interpolator.locate(baseNanos + 500_000_000L, out)
        assertEquals(5.0, TripScoring.haversineM(18.0, 73.0, out.lat, out.lon), 0.01)
        assertTrue(out.lat > 18.0)
        assertEquals(73.0, out.lon, 1e-12)
    }

    @Test
    fun farAfterNewestFix_usesNewestPosition() {
        val interpolator = PositionInterpolator(config)
        interpolator.onFix(fix(0, 18.0, 73.0))
        val out = EventPosition()
        interpolator.locate(baseNanos + (config.maxExtrapolationMs + 500) * 1_000_000L, out)
        assertEquals(18.0, out.lat, 0.0)
        assertEquals(73.0, out.lon, 0.0)
    }

    @Test
    fun acrossLongGap_usesNearestFixInsteadOfInterpolating() {
        val interpolator = PositionInterpolator(config)
        interpolator.onFix(fix(0, 18.0, 73.0))
        interpolator.onFix(fix(20_000, 18.01, 73.0))
        val out = EventPosition()
        interpolator.locate(baseNanos + 2_000_000_000L, out)
        assertEquals(18.0, out.lat, 0.0)
        interpolator.locate(baseNanos + 19_000_000_000L, out)
        assertEquals(18.01, out.lat, 0.0)
    }

    @Test
    fun beforeOlderFix_usesOlderPosition() {
        val interpolator = PositionInterpolator(config)
        interpolator.onFix(fix(1_000, 18.0, 73.0))
        interpolator.onFix(fix(2_000, 18.0001, 73.0))
        val out = EventPosition()
        interpolator.locate(baseNanos, out)
        assertEquals(18.0, out.lat, 0.0)
        assertEquals(1_760_000_000_000L, out.wallTimeMs)
    }

    @Test
    fun withoutBearing_headingComesFromTheTwoFixes() {
        val interpolator = PositionInterpolator(config)
        interpolator.onFix(fix(0, 18.0, 73.0, bearingDeg = null))
        interpolator.onFix(fix(1_000, 18.0, 73.0001, bearingDeg = null))
        val out = EventPosition()
        interpolator.locate(baseNanos + 500_000_000L, out)
        assertEquals(90, out.headingDeg)
    }

    @Test
    fun stationaryWithoutBearing_headingUnknown() {
        val interpolator = PositionInterpolator(config)
        interpolator.onFix(fix(0, 18.0, 73.0, bearingDeg = null))
        interpolator.onFix(fix(1_000, 18.0, 73.0, bearingDeg = null))
        val out = EventPosition()
        interpolator.locate(baseNanos + 500_000_000L, out)
        assertEquals(EventPosition.UNKNOWN_HEADING, out.headingDeg)
    }

    @Test
    fun bearingNear360_roundsToZero() {
        val interpolator = PositionInterpolator(config)
        interpolator.onFix(fix(0, 18.0, 73.0, bearingDeg = 359.7f))
        val out = EventPosition()
        interpolator.locate(baseNanos, out)
        assertEquals(0, out.headingDeg)
    }

    @Test
    fun outOfOrderFix_isIgnored() {
        val interpolator = PositionInterpolator(config)
        assertTrue(interpolator.onFix(fix(1_000, 18.0, 73.0)))
        assertFalse(interpolator.onFix(fix(500, 19.0, 73.0)))
        assertNull(interpolator.previous)
    }

    @Test
    fun headingRate_fromConsecutiveBearings() {
        val interpolator = PositionInterpolator(config)
        interpolator.onFix(fix(0, 18.0, 73.0, bearingDeg = 350f))
        interpolator.onFix(fix(2_000, 18.0001, 73.0, bearingDeg = 30f))
        assertEquals(20.0, interpolator.headingRateDegPerS, 1e-6)
    }

    @Test
    fun detectedEvent_isPlacedAtTheTruePosition() {
        val speedKmh = 36.0
        val trace =
            SyntheticTrace(
                durationS = 14.0,
                speedKmhAt = { speedKmh },
                headingDeg = 30.0,
                bumps = listOf(SyntheticBump.pothole(8.5, 11.0, speedKmh, VehicleType.CAR, 3)),
            )
        val event = trace.run(VehicleType.CAR).events.single()
        val trueDistanceM = speedKmh / 3.6 * 8.5
        val trueLat = GeoMath.offsetLat(trace.startLat, trueDistanceM, 30.0)
        val trueLon = GeoMath.offsetLon(trace.startLat, trace.startLon, trueDistanceM, 30.0)
        assertTrue(TripScoring.haversineM(event.lat, event.lon, trueLat, trueLon) < 2.0)
        assertTrue(abs(event.wallTimeMs - (trace.baseWallTimeMs + 8_500L)) <= 20L)
    }

    @Test
    fun eventDuringGpsDropout_isEmittedAfterTimeout_atExtrapolatedOrLastPosition() {
        val trace =
            SyntheticTrace(
                durationS = 14.0,
                gpsDropouts = listOf(TimeInterval(8.5, 11.5)),
                bumps = listOf(SyntheticBump.pothole(8.3, 11.0, 30.0, VehicleType.CAR, 3)),
            )
        val event = trace.run(VehicleType.CAR).events.single()
        val lastFixDistanceM = 30.0 / 3.6 * 8.0
        val eventDistanceM = 30.0 / 3.6 * 8.3
        val lastLat = GeoMath.offsetLat(trace.startLat, lastFixDistanceM, trace.headingDeg)
        val lastLon = GeoMath.offsetLon(trace.startLat, trace.startLon, lastFixDistanceM, trace.headingDeg)
        val distanceM = TripScoring.haversineM(event.lat, event.lon, lastLat, lastLon)
        assertEquals(eventDistanceM - lastFixDistanceM, distanceM, 1.0)
    }
}
