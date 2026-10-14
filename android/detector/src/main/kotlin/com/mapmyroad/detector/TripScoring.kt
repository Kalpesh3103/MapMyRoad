package com.mapmyroad.detector

import kotlin.math.roundToInt

/**
 * Trip-level numbers for the summary (PRD PM-3, M2-8). Pure functions, shared by the app and tests.
 */
object TripScoring {
    /** Great-circle distance in meters (haversine, mean Earth radius 6 371 008.8 m). */
    fun haversineM(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double = GeoMath.haversineM(lat1, lon1, lat2, lon2)

    /**
     * Smoothness 0 to 100 (higher is smoother) for a list of windows, weighted by window length.
     * Null when there is too little data (less than [DetectorConfig.minScoredDistanceM]).
     */
    fun smoothnessScore(
        windows: List<RoughnessWindow>,
        config: DetectorConfig = DetectorConfig.DEFAULT,
    ): Int? {
        var totalLengthM = 0.0
        var weightedScore = 0.0
        for (window in windows) {
            if (!(window.lengthM > 0.0)) continue
            totalLengthM += window.lengthM
            weightedScore += unroundedScore(window.roughness, config) * window.lengthM
        }
        if (totalLengthM <= 0.0 || totalLengthM < config.minScoredDistanceM) return null
        return (weightedScore / totalLengthM).roundToInt().coerceIn(MIN_SCORE, MAX_SCORE)
    }

    /** Maps one roughness index to a 0 to 100 score with the same curve as [smoothnessScore]. */
    fun roughnessToScore(
        roughness: Double,
        config: DetectorConfig = DetectorConfig.DEFAULT,
    ): Int = unroundedScore(roughness, config).roundToInt().coerceIn(MIN_SCORE, MAX_SCORE)

    /**
     * Linear from 100 at [DetectorConfig.roughnessGood] to 0 at [DetectorConfig.roughnessPoor], clamped, so it is
     * monotonic non-increasing in roughness. NaN counts as the worst score. Initial curve, TODO(M1-5): calibrate
     * from field data.
     */
    private fun unroundedScore(
        roughness: Double,
        config: DetectorConfig,
    ): Double {
        if (roughness.isNaN()) return MIN_SCORE.toDouble()
        val fraction = (config.roughnessPoor - roughness) / (config.roughnessPoor - config.roughnessGood)
        return (fraction * MAX_SCORE).coerceIn(MIN_SCORE.toDouble(), MAX_SCORE.toDouble())
    }

    private const val MIN_SCORE = 0
    private const val MAX_SCORE = 100
}
