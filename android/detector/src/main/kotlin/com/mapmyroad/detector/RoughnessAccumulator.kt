package com.mapmyroad.detector

import kotlin.math.sqrt

/**
 * Roughness windows over about [DetectorConfig.windowLengthM] of travel (ARCHITECTURE.md section 5 step 11, PRD TS-9,
 * M2-4).
 *
 * Gated sensor samples are summed (a^2 and count) into the current segment, the interval since the last fix. At
 * each fix the segment is added to the window when the segment is valid: both fixes pass the gate (speed, accuracy,
 * in vehicle), they are at most maxWindowFixGapMs apart, and the implied speed is plausible (no GPS jump). The
 * window closes at the first fix where its length reaches windowLengthM, so a window is 50 m plus at most one fix
 * step (under 120 m below 200 km/h). An invalid segment (pause, stale GPS, jump) ends the window: it is emitted if
 * at least minPartialWindowM long, otherwise dropped, and a new window starts at the next valid segment.
 *
 * roughness = RMS(a) / roughnessSpeedFactor(mean speed) * roughnessCalibration[vehicle]. Windows with fewer than
 * minRoughnessSamples gated samples (for example the phone was handled the whole time) are skipped.
 *
 * Threading: not thread-safe, owned by one detector. [addSample] is allocation-free; windows are allocated only
 * when emitted, about once per 50 m.
 */
internal class RoughnessAccumulator(
    private val config: DetectorConfig,
    private val calibration: Double,
) {
    private val maxGapNanos = config.maxWindowFixGapMs * NANOS_PER_MS

    private var segmentSumSquares = 0.0
    private var segmentCount = 0

    private var hasAnchor = false
    private var anchorNanos = 0L
    private var anchorLat = 0.0
    private var anchorLon = 0.0

    private var isWindowOpen = false
    private var startNanos = 0L
    private var startLat = 0.0
    private var startLon = 0.0
    private var endNanos = 0L
    private var endLat = 0.0
    private var endLon = 0.0
    private var endWallTimeMs = 0L
    private var lengthM = 0.0
    private var sumSquares = 0.0
    private var count = 0

    /** Adds one gated, filtered vertical acceleration sample to the current segment. */
    fun addSample(valueMps2: Double) {
        segmentSumSquares += valueMps2 * valueMps2
        segmentCount++
    }

    /**
     * Ends the current segment at [fix]. [isFixGood] is the gate state at this fix. Returns the window to emit, if
     * any.
     */
    fun onFix(
        fix: LocationFix,
        isFixGood: Boolean,
    ): RoughnessWindow? {
        var result: RoughnessWindow? = null
        val dtNanos = fix.elapsedNanos - anchorNanos
        val distanceM = if (hasAnchor) GeoMath.haversineM(anchorLat, anchorLon, fix.lat, fix.lon) else 0.0
        val isSegmentValid =
            isFixGood &&
                hasAnchor &&
                dtNanos in 1..maxGapNanos &&
                distanceM / (dtNanos / NANOS_PER_S) * KMH_PER_MPS <= config.maxPlausibleSpeedKmh
        if (isSegmentValid) {
            if (!isWindowOpen) openWindow()
            lengthM += distanceM
            sumSquares += segmentSumSquares
            count += segmentCount
            endNanos = fix.elapsedNanos
            endLat = fix.lat
            endLon = fix.lon
            endWallTimeMs = fix.wallTimeMs
            if (lengthM >= config.windowLengthM) {
                result = buildWindow()
                isWindowOpen = false
            }
        } else {
            if (isWindowOpen && lengthM >= config.minPartialWindowM) result = buildWindow()
            isWindowOpen = false
        }
        hasAnchor = isFixGood
        anchorNanos = fix.elapsedNanos
        anchorLat = fix.lat
        anchorLon = fix.lon
        segmentSumSquares = 0.0
        segmentCount = 0
        return result
    }

    /** Returns the open window if it is at least minPartialWindowM long, and closes it. */
    fun flushPartial(): RoughnessWindow? {
        val result = if (isWindowOpen && lengthM >= config.minPartialWindowM) buildWindow() else null
        isWindowOpen = false
        return result
    }

    fun reset() {
        segmentSumSquares = 0.0
        segmentCount = 0
        hasAnchor = false
        anchorNanos = 0L
        anchorLat = 0.0
        anchorLon = 0.0
        isWindowOpen = false
        lengthM = 0.0
        sumSquares = 0.0
        count = 0
    }

    private fun openWindow() {
        isWindowOpen = true
        startNanos = anchorNanos
        startLat = anchorLat
        startLon = anchorLon
        lengthM = 0.0
        sumSquares = 0.0
        count = 0
    }

    private fun buildWindow(): RoughnessWindow? {
        if (count < config.minRoughnessSamples) return null
        val durationS = (endNanos - startNanos) / NANOS_PER_S
        val meanSpeedKmh = if (durationS > 0.0) lengthM / durationS * KMH_PER_MPS else 0.0
        val rmsMps2 = sqrt(sumSquares / count)
        val roughness = rmsMps2 / SpeedNormalization.roughnessSpeedFactor(meanSpeedKmh, config) * calibration
        return RoughnessWindow(
            startElapsedNanos = startNanos,
            endElapsedNanos = endNanos,
            wallTimeMs = endWallTimeMs,
            startLat = startLat,
            startLon = startLon,
            endLat = endLat,
            endLon = endLon,
            lengthM = lengthM,
            roughness = roughness,
            speedKmh = meanSpeedKmh,
        )
    }
}
