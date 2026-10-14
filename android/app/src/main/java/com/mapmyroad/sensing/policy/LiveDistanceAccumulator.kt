package com.mapmyroad.sensing.policy

import com.mapmyroad.sensing.TrackingConfig

/**
 * Live trip distance for the S3 screen. The stored summary distance is computed by the repository (M2-8).
 *
 * Only accurate fixes count. Steps shorter than [minStepM] are treated as jitter: the anchor stays, so slow motion
 * still adds up once it exceeds [minStepM]. Steps implying more than [maxPlausibleSpeedMps] are GPS jumps and are
 * rejected; after [maxConsecutiveRejects] rejections in a row the new position is trusted and re-anchored without
 * adding distance (the receiver really is somewhere else, for example after a long tunnel).
 *
 * Pure and single-threaded: used on the recorder's sensor thread. Times are elapsed-realtime milliseconds.
 */
class LiveDistanceAccumulator(
    private val maxAccuracyM: Float = TrackingConfig.DISTANCE_MAX_ACCURACY_M,
    private val minStepM: Double = TrackingConfig.DISTANCE_MIN_STEP_M,
    private val maxPlausibleSpeedMps: Double = TrackingConfig.DISTANCE_MAX_PLAUSIBLE_SPEED_MPS,
    private val maxConsecutiveRejects: Int = TrackingConfig.DISTANCE_MAX_CONSECUTIVE_REJECTS,
    private val distanceCalculator: DistanceCalculator = DistanceCalculator.HAVERSINE,
) {
    var distanceM: Double = 0.0
        private set

    private var hasAnchor = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0
    private var anchorAtMs = 0L
    private var consecutiveRejects = 0

    /** Returns true if the fix added distance. */
    fun onFix(
        fixAtMs: Long,
        lat: Double,
        lon: Double,
        accuracyM: Float?,
    ): Boolean {
        if (accuracyM == null || accuracyM > maxAccuracyM) return false
        if (!hasAnchor) {
            setAnchor(fixAtMs, lat, lon)
            return false
        }
        val stepM = distanceCalculator.distanceM(anchorLat, anchorLon, lat, lon)
        if (stepM < minStepM) return false
        val dtS = (fixAtMs - anchorAtMs) / MILLIS_PER_SECOND
        val isJump = dtS <= 0.0 || stepM / dtS > maxPlausibleSpeedMps
        if (isJump) {
            consecutiveRejects++
            if (consecutiveRejects >= maxConsecutiveRejects) setAnchor(fixAtMs, lat, lon)
            return false
        }
        distanceM += stepM
        setAnchor(fixAtMs, lat, lon)
        return true
    }

    /** Starts a new segment: the next fix becomes the anchor without adding distance (after a pause). */
    fun breakSegment() {
        hasAnchor = false
        consecutiveRejects = 0
    }

    private fun setAnchor(
        fixAtMs: Long,
        lat: Double,
        lon: Double,
    ) {
        hasAnchor = true
        anchorLat = lat
        anchorLon = lon
        anchorAtMs = fixAtMs
        consecutiveRejects = 0
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000.0
    }
}
