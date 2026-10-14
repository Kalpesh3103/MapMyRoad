package com.mapmyroad.sensing.policy

import com.mapmyroad.domain.session.GpsStatus
import com.mapmyroad.sensing.TrackingConfig

/**
 * Derives the live GPS status (UI_UX_GUIDELINES.md section 8, "GPS weak").
 *
 * Pure and single-threaded: used on the recorder's sensor thread. Times are elapsed-realtime milliseconds.
 */
class GpsStatusEvaluator(
    private val goodAccuracyM: Float = TrackingConfig.GPS_GOOD_ACCURACY_M,
    private val maxFixAgeMs: Long = TrackingConfig.GPS_MAX_FIX_AGE_MS,
) {
    private var hasFix = false
    private var lastFixAtMs = 0L
    private var lastAccuracyM: Float? = null

    fun onFix(
        fixAtMs: Long,
        accuracyM: Float?,
    ) {
        hasFix = true
        lastFixAtMs = fixAtMs
        lastAccuracyM = accuracyM
    }

    fun evaluate(nowMs: Long): GpsStatus {
        if (!hasFix || nowMs - lastFixAtMs > maxFixAgeMs) return GpsStatus.WAITING
        val accuracyM = lastAccuracyM
        return if (accuracyM != null && accuracyM <= goodAccuracyM) GpsStatus.GOOD else GpsStatus.WEAK
    }

    /** Forgets the last fix, for example while location updates are off during a manual pause. */
    fun reset() {
        hasFix = false
        lastAccuracyM = null
    }
}
