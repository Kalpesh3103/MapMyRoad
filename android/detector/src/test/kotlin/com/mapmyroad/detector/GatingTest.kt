package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GatingTest {
    private fun severePotholeTrace(
        speedKmh: Double = 30.0,
        accuracyM: Float? = 5f,
        inVehicle: Boolean? = null,
        handling: List<TimeInterval> = emptyList(),
        gpsDropouts: List<TimeInterval> = emptyList(),
        reportsSpeed: Boolean = true,
    ) =
        SyntheticTrace(
            durationS = 14.0,
            speedKmhAt = { speedKmh },
            accuracyM = accuracyM,
            inVehicle = inVehicle,
            handling = handling,
            gpsDropouts = gpsDropouts,
            reportsSpeed = reportsSpeed,
            bumps = listOf(SyntheticBump.pothole(8.0, 11.0, maxOf(speedKmh, 10.0), VehicleType.SCOOTER, 3)),
        )

    @Test
    fun control_severePothole_isDetected() {
        assertEquals(1, severePotholeTrace().run(VehicleType.SCOOTER).events.size)
    }

    @Test
    fun lowSpeed_suppressesEvents() {
        assertTrue(severePotholeTrace(speedKmh = 8.0).run(VehicleType.SCOOTER).events.isEmpty())
    }

    @Test
    fun poorAccuracy_suppressesEvents() {
        assertTrue(severePotholeTrace(accuracyM = 30f).run(VehicleType.SCOOTER).events.isEmpty())
    }

    @Test
    fun unknownAccuracy_countsAsPoor() {
        assertTrue(severePotholeTrace(accuracyM = null).run(VehicleType.SCOOTER).events.isEmpty())
    }

    @Test
    fun notInVehicle_suppressesEvents() {
        assertTrue(severePotholeTrace(inVehicle = false).run(VehicleType.SCOOTER).events.isEmpty())
    }

    @Test
    fun inVehicleUnknownOrTrue_allowsEvents() {
        assertEquals(1, severePotholeTrace(inVehicle = null).run(VehicleType.SCOOTER).events.size)
        assertEquals(1, severePotholeTrace(inVehicle = true).run(VehicleType.SCOOTER).events.size)
    }

    @Test
    fun handling_suppressesEvents() {
        val trace = severePotholeTrace(handling = listOf(TimeInterval(6.5, 8.5)))
        assertTrue(trace.run(VehicleType.SCOOTER).events.isEmpty())
    }

    @Test
    fun handlingOnsetJustAfterBump_dropsThePendingEvent() {
        val trace = severePotholeTrace(handling = listOf(TimeInterval(8.0, 9.0)))
        assertTrue(trace.run(VehicleType.SCOOTER).events.isEmpty())
    }

    @Test
    fun handlingLongBefore_doesNotSuppress() {
        val trace = severePotholeTrace(handling = listOf(TimeInterval(2.0, 3.0)))
        assertEquals(1, trace.run(VehicleType.SCOOTER).events.size)
    }

    @Test
    fun staleGps_suppressesEvents() {
        val trace = severePotholeTrace(gpsDropouts = listOf(TimeInterval(4.5, 12.0)))
        assertTrue(trace.run(VehicleType.SCOOTER).events.isEmpty())
    }

    @Test
    fun missingReportedSpeed_usesSpeedDerivedFromFixes() {
        assertEquals(1, severePotholeTrace(reportsSpeed = false).run(VehicleType.SCOOTER).events.size)
    }

    @Test
    fun largeRotationDuringTurn_isNotHandling() {
        val detector = HandlingDetector(DetectorConfig.DEFAULT)
        detector.onHeadingRate(45.0)
        for (i in 0 until 100) detector.onGyroscope(i * 10_000_000L, 3.0, 0.0, 0.0)
        assertFalse(detector.isHandling(1_000_000_000L))
    }

    @Test
    fun largeRotationWithoutTurn_isHandlingForHoldTime() {
        val config = DetectorConfig.DEFAULT
        val detector = HandlingDetector(config)
        detector.onHeadingRate(Double.NaN)
        var started = 0
        for (i in 0 until 100) if (detector.onGyroscope(i * 10_000_000L, 3.0, 0.0, 0.0)) started++
        assertEquals(1, started)
        assertTrue(detector.isHandling(1_000_000_000L))
        assertFalse(detector.isHandling(990_000_000L + config.handlingHoldMs * 1_000_000L))
    }

    @Test
    fun shortGyroSpikeOfAPotholeHit_isNotHandling() {
        val detector = HandlingDetector(DetectorConfig.DEFAULT)
        for (i in 0 until 100) {
            val rate = if (i in 50..54) 3.0 else 0.02
            detector.onGyroscope(i * 10_000_000L, rate, 0.0, 0.0)
        }
        assertFalse(detector.isHandling(1_000_000_000L))
    }
}
