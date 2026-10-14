package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class OrientationNormalizationTest {
    @Test
    fun verticalAccel_isIndependentOfRotation() {
        val gravity = SyntheticTrace.GRAVITY_MPS2
        val upAccelMps2 = 2.5
        val horizontalMps2 = 1.7
        for (rollDeg in listOf(0.0, 35.0, 90.0, 180.0, -120.0)) {
            for (pitchDeg in listOf(0.0, 45.0, -80.0)) {
                val r = Math.toRadians(rollDeg)
                val p = Math.toRadians(pitchDeg)
                // Device-to-world rotation Ry(p) * Rx(r); device vectors are R^T * world.
                val world = doubleArrayOf(horizontalMps2, 0.0, gravity + upAccelMps2)
                val acc = rotateToDevice(world, r, p)
                val grav = rotateToDevice(doubleArrayOf(0.0, 0.0, gravity), r, p)
                val vertical = VerticalProjection.verticalAccelMps2(acc[0], acc[1], acc[2], grav[0], grav[1], grav[2])
                assertEquals("roll $rollDeg pitch $pitchDeg", upAccelMps2, vertical, 1e-9)
            }
        }
    }

    @Test
    fun verticalAccel_withoutValidGravity_isNaN() {
        assertTrue(VerticalProjection.verticalAccelMps2(0.0, 0.0, 9.8, 0.0, 0.0, 0.1).isNaN())
    }

    @Test
    fun samePothole_inAnyOrientation_givesSameSeverity() {
        val orientations =
            listOf(
                doubleArrayOf(0.0, 0.0, 0.0),
                doubleArrayOf(90.0, 0.0, 0.0),
                doubleArrayOf(30.0, 75.0, 200.0),
                doubleArrayOf(180.0, 10.0, 45.0),
            )
        val scores =
            orientations.map { o ->
                val trace =
                    SyntheticTrace(
                        durationS = 12.0,
                        rollDeg = o[0],
                        pitchDeg = o[1],
                        yawDeg = o[2],
                        bumps = listOf(SyntheticBump.pothole(6.0, 11.0, 30.0, VehicleType.SCOOTER, 3)),
                    )
                val events = trace.run(VehicleType.SCOOTER).events
                assertEquals("one event for orientation ${o.toList()}", 1, events.size)
                assertEquals(Severity.SEVERE, events[0].severity)
                events[0].peakScore
            }
        val reference = scores[0]
        for (score in scores) assertEquals(reference, score, reference * 0.1)
    }

    @Test
    fun withoutGravitySensor_fallbackEstimate_stillDetects() {
        val trace =
            SyntheticTrace(
                durationS = 14.0,
                rollDeg = 40.0,
                pitchDeg = 60.0,
                hasGravitySensor = false,
                bumps = listOf(SyntheticBump.pothole(8.0, 11.0, 30.0, VehicleType.CAR, 3)),
            )
        val events = trace.run(VehicleType.CAR).events
        assertEquals(1, events.size)
        assertEquals(Severity.SEVERE, events[0].severity)
    }

    private fun rotateToDevice(
        world: DoubleArray,
        rollRad: Double,
        pitchRad: Double,
    ): DoubleArray {
        // R = Ry(p) * Rx(r)
        val cr = cos(rollRad)
        val sr = sin(rollRad)
        val cp = cos(pitchRad)
        val sp = sin(pitchRad)
        val rotation =
            arrayOf(
                doubleArrayOf(cp, sp * sr, sp * cr),
                doubleArrayOf(0.0, cr, -sr),
                doubleArrayOf(-sp, cp * sr, cp * cr),
            )
        return DoubleArray(3) { axis -> rotation[0][axis] * world[0] + rotation[1][axis] * world[1] + rotation[2][axis] * world[2] }
    }
}
