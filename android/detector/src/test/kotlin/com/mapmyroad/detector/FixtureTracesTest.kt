package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.Locale
import kotlin.math.abs

class FixtureTracesTest {
    @Test
    fun miniJson_parsesTheLabelsShape() {
        val json =
            """
            {
              "fixture": "pune_route_a_scooter_phone_mount_01",
              "vehicleType": "scooter",
              "phonePlacement": "mount",
              "note": "escaped \"quote\" A",
              "labels": [
                { "tMs": 183400, "type": "pothole", "severity": 3 },
                { "tMs": 204100, "type": "speed_breaker" },
                { "tMs": 251900, "type": "rough_patch", "durationMs": 2500 }
              ],
              "flags": [true, false, null, -1.5e2]
            }
            """.trimIndent()
        val root = MiniJson.parse(json) as Map<*, *>
        assertEquals("escaped \"quote\" A", root["note"])
        assertEquals(listOf(true, false, null, -150.0), root["flags"])
        val fixture = FixtureTraces.parseLabels("pune", File("pune.csv"), json)
        assertEquals(VehicleType.SCOOTER, fixture.vehicleType)
        assertEquals(3, fixture.labels.size)
        assertEquals(TraceLabel(183400L, "pothole", 3, null), fixture.labels[0])
        assertNull(fixture.labels[1].severity)
        assertEquals(2500L, fixture.labels[2].durationMs)
    }

    @Test(expected = IllegalArgumentException::class)
    fun miniJson_rejectsMalformedInput() {
        MiniJson.parse("{\"a\": [1, 2}")
    }

    @Test
    fun csvReplay_matchesDirectRun() {
        val trace =
            SyntheticTrace(
                durationS = 30.0,
                bumps =
                    listOf(
                        SyntheticBump.pothole(8.0, 11.0, 30.0, VehicleType.SCOOTER, 3),
                        SyntheticBump.pothole(18.0, 6.8, 30.0, VehicleType.SCOOTER, 2),
                    ),
            )
        val recorder = CsvRecorder()
        trace.feed(recorder, flush = false)
        val listener = CollectingListener()
        val detector = RoadDetector.create(VehicleType.SCOOTER, listener)
        FixtureTraces.replay(recorder.lines, detector)
        detector.flush()
        val direct = trace.run(VehicleType.SCOOTER).events
        assertEquals(direct.map { it.severity }, listener.events.map { it.severity })
        for (i in direct.indices) assertTrue(abs(direct[i].elapsedNanos - listener.events[i].elapsedNanos) <= 1_000_000L)
    }

    @Test
    fun fixtureFolder_loadsWithoutErrors() {
        // Only a README until M1-8; load() fails if a CSV has no labels file or a malformed one.
        val fixtures = FixtureTraces.load()
        assertTrue(fixtures.all { it.csvFile.isFile })
    }

    /** Writes the fixture CSV format from a live feed (one row per accelerometer sample). */
    private class CsvRecorder : RoadDetector {
        val lines = mutableListOf("# synthetic", "t_ms,ax,ay,az,gx,gy,gz,grav_x,grav_y,grav_z,lat,lon,speed_mps,accuracy_m")
        private var gyro = ",,"
        private var gravity = ",,"
        private var gps = ",,,"
        private var baseNanos: Long? = null

        override fun onAccelerometer(
            elapsedNanos: Long,
            x: Float,
            y: Float,
            z: Float,
        ) {
            val tMs = (elapsedNanos - base(elapsedNanos)) / 1.0e6
            lines += String.format(Locale.ROOT, "%.3f,%s,%s,%s,%s,%s,%s", tMs, x, y, z, gyro, gravity, gps)
        }

        override fun onGyroscope(
            elapsedNanos: Long,
            x: Float,
            y: Float,
            z: Float,
        ) {
            gyro = "$x,$y,$z"
        }

        override fun onGravity(
            elapsedNanos: Long,
            x: Float,
            y: Float,
            z: Float,
        ) {
            gravity = "$x,$y,$z"
        }

        override fun onLocation(fix: LocationFix) {
            base(fix.elapsedNanos)
            gps = "${fix.lat},${fix.lon},${fix.speedMps ?: ""},${fix.accuracyM ?: ""}"
        }

        override fun onInVehicle(inVehicle: Boolean?) = Unit

        override fun flush() = Unit

        override fun reset() = Unit

        private fun base(elapsedNanos: Long): Long = baseNanos ?: elapsedNanos.also { baseNanos = it }
    }
}
