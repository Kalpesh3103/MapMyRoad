package com.mapmyroad.detector

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class DeterminismTest {
    private fun trace() =
        SyntheticTrace(
            durationS = 60.0,
            speedKmhAt = { tS -> 25.0 + 10.0 * sin(tS / 10.0) },
            rollDeg = 20.0,
            pitchDeg = 70.0,
            bumps =
                listOf(
                    SyntheticBump.pothole(10.0, 11.0, 30.0, VehicleType.SCOOTER, 3),
                    SyntheticBump.pothole(25.0, 6.8, 30.0, VehicleType.SCOOTER, 2),
                    SyntheticBump.speedBreaker(40.0, 0.15, 20.0),
                ),
            roughPatches = listOf(RoughPatch(48.0, 4.0, 4.0)),
        )

    @Test
    fun sameTraceTwice_givesIdenticalOutput() {
        val first = trace().run(VehicleType.SCOOTER)
        val second = trace().run(VehicleType.SCOOTER)
        assertTrue(first.events.isNotEmpty())
        assertTrue(first.windows.isNotEmpty())
        assertEquals(first.events, second.events)
        assertEquals(first.windows, second.windows)
    }

    @Test
    fun reusedDetectorAfterReset_givesIdenticalOutput() {
        val listener = CollectingListener()
        val detector = RoadDetector.create(VehicleType.SCOOTER, listener)
        trace().feed(detector, flush = false)
        detector.reset()
        listener.events.clear()
        listener.windows.clear()
        trace().feed(detector, flush = true)
        val fresh = trace().run(VehicleType.SCOOTER)
        assertEquals(fresh.events, listener.events)
        assertEquals(fresh.windows, listener.windows)
    }

    @Test
    fun resetDoesNotEmit() {
        val listener = CollectingListener()
        val detector = RoadDetector.create(VehicleType.SCOOTER, listener)
        val trace =
            SyntheticTrace(durationS = 8.6, bumps = listOf(SyntheticBump.pothole(8.5, 11.0, 30.0, VehicleType.SCOOTER, 3)))
        trace.feed(detector, flush = false)
        val windowsBefore = listener.windows.size
        detector.reset()
        assertTrue(listener.events.isEmpty())
        assertEquals(windowsBefore, listener.windows.size)
    }

    @Test
    fun flush_emitsPendingEvent() {
        val listener = CollectingListener()
        val detector = RoadDetector.create(VehicleType.SCOOTER, listener)
        val trace =
            SyntheticTrace(durationS = 8.7, bumps = listOf(SyntheticBump.pothole(8.3, 11.0, 30.0, VehicleType.SCOOTER, 3)))
        trace.feed(detector, flush = false)
        assertTrue("still waiting for the next fix", listener.events.isEmpty())
        detector.flush()
        assertEquals(1, listener.events.size)
    }
}
