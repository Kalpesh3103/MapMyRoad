package com.mapmyroad.sensing.policy

import com.mapmyroad.sensing.TrackingConfig

/**
 * Decides which fixes become stored track points (ARCHITECTURE.md section 4.3: about 1 point per second).
 *
 * Pure and single-threaded: used on the recorder's sensor thread. Times are elapsed-realtime milliseconds.
 */
class TrackDownsampler(
    private val minIntervalMs: Long = TrackingConfig.TRACK_MIN_INTERVAL_MS,
    private val maxAccuracyM: Float = TrackingConfig.TRACK_MAX_ACCURACY_M,
) {
    private var hasKept = false
    private var lastKeptAtMs = 0L

    /** Returns true if the fix should be kept, and records it as the last kept point. */
    fun shouldKeep(
        fixAtMs: Long,
        accuracyM: Float?,
    ): Boolean {
        if (accuracyM == null || accuracyM > maxAccuracyM) return false
        if (hasKept && fixAtMs - lastKeptAtMs < minIntervalMs) return false
        hasKept = true
        lastKeptAtMs = fixAtMs
        return true
    }

    fun reset() {
        hasKept = false
    }
}
