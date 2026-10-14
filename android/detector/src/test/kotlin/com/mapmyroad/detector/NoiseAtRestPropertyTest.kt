package com.mapmyroad.detector

import org.junit.Assert.assertTrue
import org.junit.Test

class NoiseAtRestPropertyTest {
    @Test
    fun noiseAtRest_neverProducesEvents() {
        for (seed in 1L..10L) {
            for (vehicleType in VehicleType.entries) {
                val trace =
                    SyntheticTrace(durationS = 30.0, speedKmhAt = { 0.0 }, verticalNoiseMps2 = 1.5, horizontalNoiseMps2 = 1.0, seed = seed)
                val output = trace.run(vehicleType)
                assertTrue("seed $seed $vehicleType", output.events.isEmpty())
                assertTrue("seed $seed $vehicleType", output.windows.isEmpty())
            }
        }
    }

    @Test
    fun roadNoiseWhileMoving_neverProducesSevereEvents() {
        for (seed in 1L..10L) {
            for (vehicleType in VehicleType.entries) {
                val trace =
                    SyntheticTrace(
                        durationS = 60.0,
                        speedKmhAt = { 30.0 },
                        verticalNoiseMps2 = 0.6,
                        seed = seed,
                        rollDeg = seed * 17.0,
                        pitchDeg = seed * 31.0,
                    )
                val events = trace.run(vehicleType).events
                assertTrue("seed $seed $vehicleType: $events", events.none { it.severity == Severity.SEVERE })
            }
        }
    }

    @Test
    fun roughPatch_doesNotProduceSevereEvents() {
        for (seed in 1L..5L) {
            val trace = SyntheticTrace(durationS = 30.0, seed = seed, roughPatches = listOf(RoughPatch(10.0, 10.0, 4.0)))
            val events = trace.run(VehicleType.SCOOTER).events
            assertTrue("seed $seed: $events", events.none { it.severity == Severity.SEVERE })
        }
    }
}
