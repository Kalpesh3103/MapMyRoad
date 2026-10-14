package com.mapmyroad.sensing.policy

import com.mapmyroad.sensing.TrackingConfig

/**
 * Auto-pause and auto-stop while stationary (PRD TS-7, PRD flow 7.2, task M2-6).
 *
 * Movement evidence:
 * - any fix with a speed of at least [stationarySpeedMps] (whatever its accuracy: pausing a moving trip in a poor
 *   GPS stretch would lose data, so the policy errs towards recording), or
 * - an accurate fix more than [stationaryRadiusM] from the anchor (for providers that report no speed).
 *
 * While recording, the trip pauses after [pauseAfterMs] without movement evidence, but only while fixes are fresh
 * (a tunnel is not a stop). A gap in fixes longer than [fixFreshnessMs] restarts the stationary clock.
 * While paused, an accurate fix at [resumeSpeedMps] or more, or more than [resumeRadiusM] from the anchor, resumes.
 * The trip stops after [stopAfterMs] without movement, counted from the last movement (so it includes the time
 * before the pause), whether or not fixes still arrive (a parked vehicle in a basement has no fix).
 *
 * Pure and single-threaded: used on the recorder's sensor thread. Times are elapsed-realtime milliseconds.
 * The caller only consults this policy when auto-pause is enabled and the trip is not manually paused.
 */
class AutoPausePolicy(
    private val stationarySpeedMps: Float = TrackingConfig.STATIONARY_SPEED_MPS,
    private val stationaryRadiusM: Double = TrackingConfig.STATIONARY_RADIUS_M,
    private val pauseAfterMs: Long = TrackingConfig.STATIONARY_PAUSE_AFTER_MS,
    private val stopAfterMs: Long = TrackingConfig.STATIONARY_STOP_AFTER_MS,
    private val resumeSpeedMps: Float = TrackingConfig.MOVING_RESUME_SPEED_MPS,
    private val resumeAccuracyM: Float = TrackingConfig.MOVING_RESUME_ACCURACY_M,
    private val resumeRadiusM: Double = TrackingConfig.MOVING_RESUME_RADIUS_M,
    private val fixFreshnessMs: Long = TrackingConfig.AUTO_PAUSE_FIX_FRESHNESS_MS,
    private val distanceCalculator: DistanceCalculator = DistanceCalculator.HAVERSINE,
) {
    enum class Decision {
        NONE,
        PAUSE,
        RESUME,
        STOP,
    }

    private var hasMovementTime = false
    private var lastMovementAtMs = 0L
    private var hasFix = false
    private var lastFixAtMs = 0L
    private var hasAnchor = false
    private var anchorLat = 0.0
    private var anchorLon = 0.0

    /** Treats [nowMs] as the last movement, for example at trip start or after a manual resume. */
    fun reset(nowMs: Long) {
        hasMovementTime = true
        lastMovementAtMs = nowMs
        hasFix = false
        hasAnchor = false
    }

    /** [isPaused] is true while auto-paused (PauseReason.STATIONARY). */
    fun onFix(
        fixAtMs: Long,
        lat: Double,
        lon: Double,
        speedMps: Float?,
        accuracyM: Float?,
        isPaused: Boolean,
    ): Decision {
        val hadGap = !hasFix || fixAtMs - lastFixAtMs > fixFreshnessMs
        hasFix = true
        lastFixAtMs = fixAtMs
        if (!hasMovementTime || (hadGap && !isPaused)) {
            // Unknown what happened during the gap: do not count it as standing still.
            markMovement(fixAtMs)
        }
        val isAccurate = accuracyM != null && accuracyM <= resumeAccuracyM
        return if (isPaused) {
            onFixWhilePaused(fixAtMs, lat, lon, speedMps, isAccurate)
        } else {
            onFixWhileRecording(fixAtMs, lat, lon, speedMps, isAccurate)
        }
    }

    /** Called about once per second, so that decisions are taken even when fixes stop arriving. */
    fun onTick(
        nowMs: Long,
        isPaused: Boolean,
    ): Decision {
        if (!hasMovementTime) markMovement(nowMs)
        return evaluate(nowMs, isPaused)
    }

    private fun onFixWhileRecording(
        fixAtMs: Long,
        lat: Double,
        lon: Double,
        speedMps: Float?,
        isAccurate: Boolean,
    ): Decision {
        val isMovingBySpeed = speedMps != null && speedMps >= stationarySpeedMps
        if (isMovingBySpeed) {
            markMovement(fixAtMs)
            if (isAccurate) setAnchor(lat, lon)
            return Decision.NONE
        }
        if (isAccurate) {
            if (!hasAnchor) {
                setAnchor(lat, lon)
            } else if (distanceCalculator.distanceM(anchorLat, anchorLon, lat, lon) > stationaryRadiusM) {
                markMovement(fixAtMs)
                setAnchor(lat, lon)
                return Decision.NONE
            }
        }
        return evaluate(fixAtMs, isPaused = false)
    }

    private fun onFixWhilePaused(
        fixAtMs: Long,
        lat: Double,
        lon: Double,
        speedMps: Float?,
        isAccurate: Boolean,
    ): Decision {
        if (isAccurate) {
            val isFast = speedMps != null && speedMps >= resumeSpeedMps
            val isDisplaced = hasAnchor && distanceCalculator.distanceM(anchorLat, anchorLon, lat, lon) > resumeRadiusM
            if (isFast || isDisplaced) {
                markMovement(fixAtMs)
                setAnchor(lat, lon)
                return Decision.RESUME
            }
            if (!hasAnchor) setAnchor(lat, lon)
        }
        return evaluate(fixAtMs, isPaused = true)
    }

    private fun evaluate(
        nowMs: Long,
        isPaused: Boolean,
    ): Decision {
        val stationaryMs = nowMs - lastMovementAtMs
        if (isPaused) return if (stationaryMs >= stopAfterMs) Decision.STOP else Decision.NONE
        val hasFreshFix = hasFix && nowMs - lastFixAtMs <= fixFreshnessMs
        return if (hasFreshFix && stationaryMs >= pauseAfterMs) Decision.PAUSE else Decision.NONE
    }

    private fun markMovement(atMs: Long) {
        hasMovementTime = true
        lastMovementAtMs = atMs
    }

    private fun setAnchor(
        lat: Double,
        lon: Double,
    ) {
        hasAnchor = true
        anchorLat = lat
        anchorLon = lon
    }
}
