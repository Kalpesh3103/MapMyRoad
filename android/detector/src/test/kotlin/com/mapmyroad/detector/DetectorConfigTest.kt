package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetectorConfigTest {
    @Test
    fun default_isValid_andCoversEveryVehicle() {
        val config = DetectorConfig.DEFAULT
        for (vehicleType in VehicleType.entries) {
            config.severityThresholds.getValue(vehicleType)
            config.vehicleCalibration.getValue(vehicleType)
        }
        assertEquals(50.0, config.windowLengthM, 0.0)
        assertEquals(200.0, config.minScoredDistanceM, 0.0)
    }

    @Test(expected = IllegalArgumentException::class)
    fun negativeSpeedExponent_isRejected() {
        DetectorConfig(speedExponent = -0.5)
    }

    @Test(expected = IllegalArgumentException::class)
    fun missingVehicleCalibration_isRejected() {
        DetectorConfig(vehicleCalibration = mapOf(VehicleType.CAR to 1.0))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unorderedSeverityThresholds_areRejected() {
        SeverityThresholds(minorPeakScore = 3.0, moderatePeakScore = 2.0, severePeakScore = 5.0)
    }

    @Test
    fun severityThresholds_classify() {
        val thresholds = SeverityThresholds(2.5, 4.5, 7.0)
        assertNull(thresholds.classify(2.49))
        assertNull(thresholds.classify(Double.NaN))
        assertEquals(Severity.MINOR, thresholds.classify(2.5))
        assertEquals(Severity.MODERATE, thresholds.classify(4.5))
        assertEquals(Severity.SEVERE, thresholds.classify(7.0))
    }
}
