package com.mapmyroad.detector

import kotlin.math.pow

/**
 * Speed normalization (ARCHITECTURE.md section 5 step 7).
 *
 * A defect gives a bigger jolt at higher speed. The score divides the raw value by
 * speedFactor(v) = clamp((v / vRef)^exponent, minFactor, maxFactor). With exponent >= 0 the factor is monotonic
 * non-decreasing in speed, which gives two properties that the tests check (TESTING_STRATEGY.md section 3.3):
 * - the same raw jolt at a higher speed never scores higher (it is a smaller defect), and
 * - a defect whose raw jolt grows like speedFactor(v) keeps the same score and severity at every speed, so the same
 *   defect at higher speed does not produce a lower severity.
 * The real growth of jolt with speed is not known yet; the exponent is a TODO(M1-5) calibration value.
 */
internal object SpeedNormalization {
    fun factor(
        speedKmh: Double,
        referenceSpeedKmh: Double,
        exponent: Double,
        minFactor: Double,
        maxFactor: Double,
    ): Double {
        if (!(speedKmh > 0.0)) return minFactor
        return (speedKmh / referenceSpeedKmh).pow(exponent).coerceIn(minFactor, maxFactor)
    }

    fun eventSpeedFactor(
        speedKmh: Double,
        config: DetectorConfig,
    ): Double = factor(speedKmh, config.referenceSpeedKmh, config.speedExponent, config.minSpeedFactor, config.maxSpeedFactor)

    fun roughnessSpeedFactor(
        speedKmh: Double,
        config: DetectorConfig,
    ): Double =
        factor(
            speedKmh,
            config.roughnessReferenceSpeedKmh,
            config.roughnessSpeedExponent,
            config.roughnessMinSpeedFactor,
            config.roughnessMaxSpeedFactor,
        )

    /** Normalized peak score of a bump: raw peak / speed factor * vehicle calibration (M2-5). */
    fun peakScore(
        peakMps2: Double,
        speedKmh: Double,
        vehicleType: VehicleType,
        config: DetectorConfig,
    ): Double = peakMps2 / eventSpeedFactor(speedKmh, config) * config.vehicleCalibration.getValue(vehicleType)
}
