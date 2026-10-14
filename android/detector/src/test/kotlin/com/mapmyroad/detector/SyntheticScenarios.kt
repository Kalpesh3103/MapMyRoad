package com.mapmyroad.detector

/**
 * Fixed synthetic scenarios for the regression gate (M2-20) until real fixtures from M1-8 exist. They only check
 * that the pipeline behaves as designed on idealized signals; they say nothing about real-road accuracy.
 */
internal object SyntheticScenarios {
    // Targets are for the injected world-frame jolt. The first-order low-pass and sampling reduce the measured peak
    // of an 80 ms pulse by roughly 15 to 20 percent, so the targets sit above the band centers to land mid-band.
    const val SEVERE_TARGET_SCORE = 11.0
    const val MODERATE_TARGET_SCORE = 6.8
    const val MINOR_TARGET_SCORE = 4.1
    private const val POTHOLE_SPACING_S = 8.0
    private const val POTHOLE_COUNT = 12
    private const val BREAKER_SPACING_S = 10.0
    private const val BREAKER_COUNT = 9

    private val potholeSpeedsKmh = listOf(20.0, 35.0, 50.0)
    private val breakerSpeedsKmh = listOf(15.0, 18.0, 22.0)
    private val breakerHeightsM = listOf(0.10, 0.15, 0.20)

    /** Orientations (roll, pitch, yaw in degrees): mount, pocket, bag. */
    private val orientations =
        listOf(
            doubleArrayOf(70.0, 0.0, 10.0),
            doubleArrayOf(-15.0, 80.0, 120.0),
            doubleArrayOf(150.0, -30.0, 250.0),
        )

    data class Scenario(
        val name: String,
        val vehicleType: VehicleType,
        val trace: SyntheticTrace,
    )

    fun all(config: DetectorConfig = DetectorConfig.DEFAULT): List<Scenario> = VehicleType.entries.flatMap { forVehicle(it, config) }

    fun forVehicle(
        vehicleType: VehicleType,
        config: DetectorConfig = DetectorConfig.DEFAULT,
    ): List<Scenario> {
        val wheelbaseM = config.wheelbaseM.getValue(vehicleType)
        val seedBase = vehicleType.dbCode * 100L
        val potholes =
            potholeSpeedsKmh.mapIndexed { index, speedKmh ->
                val orientation = orientations[index % orientations.size]
                val bumps =
                    (0 until POTHOLE_COUNT).map { i ->
                        val severity = 3 - i % 3
                        val target =
                            when (severity) {
                                3 -> SEVERE_TARGET_SCORE
                                2 -> MODERATE_TARGET_SCORE
                                else -> MINOR_TARGET_SCORE
                            }
                        SyntheticBump.pothole(6.0 + i * POTHOLE_SPACING_S, target, speedKmh, vehicleType, severity, config)
                    }
                val roughStartS = 6.0 + POTHOLE_COUNT * POTHOLE_SPACING_S
                Scenario(
                    name = "potholes_${vehicleType.code}_${speedKmh.toInt()}kmh",
                    vehicleType = vehicleType,
                    trace =
                        SyntheticTrace(
                            durationS = roughStartS + 12.0,
                            speedKmhAt = { speedKmh },
                            wheelbaseM = wheelbaseM,
                            rollDeg = orientation[0],
                            pitchDeg = orientation[1],
                            yawDeg = orientation[2],
                            seed = seedBase + index,
                            bumps = bumps,
                            roughPatches = listOf(RoughPatch(roughStartS, 4.0, 4.0)),
                        ),
                )
            }
        val breakers =
            breakerSpeedsKmh.mapIndexed { index, speedKmh ->
                val orientation = orientations[(index + 1) % orientations.size]
                val bumps =
                    (0 until BREAKER_COUNT).map { i ->
                        SyntheticBump.speedBreaker(6.0 + i * BREAKER_SPACING_S, breakerHeightsM[i % breakerHeightsM.size], speedKmh)
                    }
                Scenario(
                    name = "breakers_${vehicleType.code}_${speedKmh.toInt()}kmh",
                    vehicleType = vehicleType,
                    trace =
                        SyntheticTrace(
                            durationS = 6.0 + BREAKER_COUNT * BREAKER_SPACING_S,
                            speedKmhAt = { speedKmh },
                            wheelbaseM = wheelbaseM,
                            rollDeg = orientation[0],
                            pitchDeg = orientation[1],
                            yawDeg = orientation[2],
                            seed = seedBase + 50 + index,
                            bumps = bumps,
                        ),
                )
            }
        return potholes + breakers
    }

    fun run(
        scenario: Scenario,
        config: DetectorConfig = DetectorConfig.DEFAULT,
    ): LabeledRun {
        val output = scenario.trace.run(scenario.vehicleType, config)
        return LabeledRun(
            name = scenario.name,
            vehicleType = scenario.vehicleType,
            labels = scenario.trace.labels(),
            detections = output.events.map { Detection(output.eventTimeMs(it), it.severity.level) },
        )
    }
}
