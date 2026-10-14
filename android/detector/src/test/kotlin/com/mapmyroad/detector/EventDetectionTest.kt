package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EventDetectionTest {
    @Test
    fun onePothole_withRearWheelHit_isOneEvent_forEveryVehicle() {
        for (vehicleType in VehicleType.entries) {
            for (speedKmh in listOf(12.0, 20.0, 35.0, 60.0)) {
                val trace =
                    SyntheticTrace(
                        durationS = 14.0,
                        speedKmhAt = { speedKmh },
                        wheelbaseM = DetectorConfig.DEFAULT.wheelbaseM.getValue(vehicleType),
                        bumps = listOf(SyntheticBump.pothole(8.0, 11.0, speedKmh, vehicleType, 3, rearWheelFactor = 0.9)),
                    )
                val events = trace.run(vehicleType).events
                assertEquals("$vehicleType at $speedKmh km/h", 1, events.size)
            }
        }
    }

    @Test
    fun twoHitsWithinRefractoryPeriod_countOnce() {
        val trace =
            SyntheticTrace(
                durationS = 14.0,
                bumps =
                    listOf(
                        SyntheticBump.pothole(8.0, 11.0, 30.0, VehicleType.MOTORCYCLE, 3, rearWheelFactor = 0.0),
                        SyntheticBump.pothole(8.25, 11.0, 30.0, VehicleType.MOTORCYCLE, 3, rearWheelFactor = 0.0),
                    ),
            )
        assertEquals(1, trace.run(VehicleType.MOTORCYCLE).events.size)
    }

    @Test
    fun hitsTwoSecondsApart_countTwice() {
        val trace =
            SyntheticTrace(
                durationS = 16.0,
                bumps =
                    listOf(
                        SyntheticBump.pothole(8.0, 11.0, 30.0, VehicleType.MOTORCYCLE, 3),
                        SyntheticBump.pothole(10.0, 11.0, 30.0, VehicleType.MOTORCYCLE, 3),
                    ),
            )
        val events = trace.run(VehicleType.MOTORCYCLE).events
        assertEquals(2, events.size)
        assertTrue(events[0].elapsedNanos < events[1].elapsedNanos)
    }

    @Test
    fun severityBins_followTargetScores() {
        val expected = mapOf(11.0 to Severity.SEVERE, 6.8 to Severity.MODERATE, 4.1 to Severity.MINOR)
        for ((target, severity) in expected) {
            val trace =
                SyntheticTrace(
                    durationS = 14.0,
                    speedKmhAt = { 35.0 },
                    bumps = listOf(SyntheticBump.pothole(8.0, target, 35.0, VehicleType.CAR, severity.level)),
                )
            val events = trace.run(VehicleType.CAR).events
            assertEquals(1, events.size)
            assertEquals("target $target", severity, events[0].severity)
        }
    }

    @Test
    fun severity_doesNotDependOnSampleRate() {
        for (sampleRateHz in listOf(50.0, 100.0, 200.0)) {
            val trace =
                SyntheticTrace(
                    durationS = 14.0,
                    sampleRateHz = sampleRateHz,
                    bumps = listOf(SyntheticBump.pothole(8.0, 11.0, 30.0, VehicleType.CAR, 3)),
                )
            assertEquals("$sampleRateHz Hz", Severity.SEVERE, trace.run(VehicleType.CAR).events.single().severity)
        }
    }

    @Test
    fun smallJolt_belowMinorThreshold_isNoEvent() {
        val trace =
            SyntheticTrace(
                durationS = 14.0,
                bumps = listOf(SyntheticBump.pothole(8.0, 1.5, 30.0, VehicleType.CAR, null)),
            )
        assertTrue(trace.run(VehicleType.CAR).events.isEmpty())
    }

    @Test
    fun speedFactor_isMonotonicNonDecreasing() {
        val config = DetectorConfig.DEFAULT
        var previous = 0.0
        var speedKmh = 0.0
        while (speedKmh <= 200.0) {
            val factor = SpeedNormalization.eventSpeedFactor(speedKmh, config)
            assertTrue("at $speedKmh km/h", factor >= previous)
            previous = factor
            speedKmh += 0.5
        }
    }

    @Test
    fun sameRawJolt_atHigherSpeed_neverScoresHigher() {
        val amplitudeMps2 = 9.0
        val scores =
            listOf(15.0, 25.0, 35.0, 50.0, 70.0).map { speedKmh ->
                val trace =
                    SyntheticTrace(
                        durationS = 14.0,
                        speedKmhAt = { speedKmh },
                        bumps = listOf(SyntheticBump(8.0, BumpShape.IMPACT, amplitudeMps2, 80.0, null, rearWheelFactor = 0.0)),
                    )
                trace.run(VehicleType.CAR).events.single().peakScore
            }
        for (i in 1 until scores.size) assertTrue("scores $scores", scores[i] <= scores[i - 1] * 1.03)
    }

    @Test
    fun sameDefect_atHigherSpeed_doesNotGetLowerSeverity() {
        for (target in listOf(11.0, 6.8)) {
            val severities =
                listOf(15.0, 25.0, 35.0, 50.0).map { speedKmh ->
                    val trace =
                        SyntheticTrace(
                            durationS = 14.0,
                            speedKmhAt = { speedKmh },
                            bumps = listOf(SyntheticBump.pothole(8.0, target, speedKmh, VehicleType.SCOOTER, null)),
                        )
                    trace.run(VehicleType.SCOOTER).events.single().severity
                }
            for (i in 1 until severities.size) assertTrue("target $target: $severities", severities[i].level >= severities[i - 1].level)
        }
    }

    @Test
    fun vehicleCalibration_scalesPeakScore() {
        val config = DetectorConfig.DEFAULT
        val car = SpeedNormalization.peakScore(10.0, 30.0, VehicleType.CAR, config)
        val scooter = SpeedNormalization.peakScore(10.0, 30.0, VehicleType.SCOOTER, config)
        assertEquals(10.0, car, 1e-9)
        assertEquals(7.0, scooter, 1e-9)
    }

    @Test
    fun speedBreakerShape_atLowSpeed_isDowngradedToMinor() {
        val breaker = SyntheticBump.speedBreaker(8.0, heightM = 0.2, speedKmh = 20.0)
        val trace = SyntheticTrace(durationS = 14.0, speedKmhAt = { 20.0 }, wheelbaseM = 2.5, bumps = listOf(breaker))
        val events = trace.run(VehicleType.CAR).events
        assertEquals(1, events.size)
        assertEquals(Severity.MINOR, events[0].severity)
        assertTrue("raw score would be severe: ${events[0].peakScore}", events[0].peakScore >= 7.0)
    }

    @Test
    fun sameHumpShape_aboveBreakerSpeed_isNotDowngraded() {
        val hump = SyntheticBump(8.0, BumpShape.HUMP, 10.0, 500.0, null)
        val trace = SyntheticTrace(durationS = 14.0, speedKmhAt = { 30.0 }, wheelbaseM = 2.5, bumps = listOf(hump))
        val events = trace.run(VehicleType.CAR).events
        assertEquals(1, events.size)
        assertNotEquals(Severity.MINOR, events[0].severity)
    }

    @Test
    fun pothole_atLowSpeed_isNotTakenForSpeedBreaker() {
        for (vehicleType in VehicleType.entries) {
            val trace =
                SyntheticTrace(
                    durationS = 14.0,
                    speedKmhAt = { 15.0 },
                    wheelbaseM = DetectorConfig.DEFAULT.wheelbaseM.getValue(vehicleType),
                    bumps = listOf(SyntheticBump.pothole(8.0, 11.0, 15.0, vehicleType, 3)),
                )
            assertEquals(vehicleType.name, Severity.SEVERE, trace.run(vehicleType).events.single().severity)
        }
    }

    @Test
    fun event_reportsSpeedAndHeading() {
        val trace =
            SyntheticTrace(
                durationS = 14.0,
                speedKmhAt = { 36.0 },
                headingDeg = 120.0,
                bumps = listOf(SyntheticBump.pothole(8.0, 11.0, 36.0, VehicleType.CAR, 3)),
            )
        val event = trace.run(VehicleType.CAR).events.single()
        assertEquals(36.0, event.speedKmh, 0.1)
        assertEquals(120, event.headingDeg)
    }
}
