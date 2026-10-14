package com.mapmyroad.detector

import kotlin.math.roundToInt

/**
 * Keeps the two newest GPS fixes and positions events between them (ARCHITECTURE.md section 5 step 9, M2-3).
 *
 * - Event between the two fixes: linear interpolation of lat and lon by time, unless the fixes are more than
 *   maxInterpolationGapMs apart (a gap or pause), in which case the nearer fix is used.
 * - Event after the newest fix: extrapolated along speed and course for at most maxExtrapolationMs, otherwise the
 *   newest fix position.
 * - Event before the older fix: the older fix position.
 * Heading is the newest fix bearing, else the course computed from the two fixes when they are at least
 * minCourseDistanceM apart. Wall time is the newest fix wall time shifted by the elapsed-time difference.
 *
 * It also exposes primitive copies of the newest fix (speed, accuracy, course, heading rate) for the per-sample
 * gate, so the hot path never touches nullable boxed fields.
 *
 * Threading: not thread-safe, owned by one detector. Fixes arrive about once per second; [locate] allocates nothing.
 */
internal class PositionInterpolator(
    private val config: DetectorConfig,
) {
    private val maxGapNanos = config.maxInterpolationGapMs * NANOS_PER_MS
    private val maxExtrapolationNanos = config.maxExtrapolationMs * NANOS_PER_MS

    var previous: LocationFix? = null
        private set
    var newest: LocationFix? = null
        private set

    /** Elapsed time of the newest fix; only meaningful when [newest] is not null. */
    var newestNanos = 0L
        private set

    /** Speed of the newest fix (reported, else derived from the two fixes), NaN when unknown. */
    var speedMps = Double.NaN
        private set

    /** Accuracy of the newest fix, NaN when unknown (treated as poor by the gate). */
    var accuracyM = Double.NaN
        private set

    /** Course of the newest fix (bearing, else computed), NaN when unknown. */
    var courseDeg = Double.NaN
        private set

    /** Heading change rate between the two newest fixes, deg/s, NaN when unknown. */
    var headingRateDegPerS = Double.NaN
        private set

    /** Accepts [fix] if it is newer than the newest fix; returns false (and ignores it) otherwise. */
    fun onFix(fix: LocationFix): Boolean {
        val last = newest
        if (last != null && fix.elapsedNanos <= last.elapsedNanos) return false
        val previousCourseDeg = courseDeg
        previous = last
        newest = fix
        newestNanos = fix.elapsedNanos
        accuracyM = fix.accuracyM?.toDouble() ?: Double.NaN
        val dtS = if (last != null) (fix.elapsedNanos - last.elapsedNanos) / NANOS_PER_S else Double.NaN
        val isRecentPair = last != null && fix.elapsedNanos - last.elapsedNanos <= maxGapNanos
        val distanceM = if (last != null) GeoMath.haversineM(last.lat, last.lon, fix.lat, fix.lon) else 0.0
        val reportedSpeed = fix.speedMps
        speedMps =
            when {
                reportedSpeed != null -> reportedSpeed.toDouble()
                isRecentPair -> distanceM / dtS
                else -> Double.NaN
            }
        val reportedBearing = fix.bearingDeg
        courseDeg =
            when {
                reportedBearing != null -> GeoMath.normalizeDeg(reportedBearing.toDouble())
                last != null && isRecentPair && distanceM >= config.minCourseDistanceM ->
                    GeoMath.bearingDeg(last.lat, last.lon, fix.lat, fix.lon)
                else -> Double.NaN
            }
        headingRateDegPerS =
            if (isRecentPair && !previousCourseDeg.isNaN() && !courseDeg.isNaN()) {
                GeoMath.headingDifferenceDeg(courseDeg, previousCourseDeg) / dtS
            } else {
                Double.NaN
            }
        return true
    }

    /** Positions an event at [elapsedNanos] into [out]. Returns false when no fix has been seen. */
    fun locate(
        elapsedNanos: Long,
        out: EventPosition,
    ): Boolean {
        val last = newest ?: return false
        val older = previous
        when {
            elapsedNanos >= last.elapsedNanos -> {
                val aheadNanos = elapsedNanos - last.elapsedNanos
                if (aheadNanos <= maxExtrapolationNanos && !speedMps.isNaN() && !courseDeg.isNaN()) {
                    val distanceM = speedMps * (aheadNanos / NANOS_PER_S)
                    out.lat = GeoMath.offsetLat(last.lat, distanceM, courseDeg)
                    out.lon = GeoMath.offsetLon(last.lat, last.lon, distanceM, courseDeg)
                } else {
                    out.lat = last.lat
                    out.lon = last.lon
                }
            }
            older != null && elapsedNanos >= older.elapsedNanos -> {
                val spanNanos = last.elapsedNanos - older.elapsedNanos
                if (spanNanos <= maxGapNanos) {
                    val fraction = (elapsedNanos - older.elapsedNanos).toDouble() / spanNanos.toDouble()
                    out.lat = older.lat + (last.lat - older.lat) * fraction
                    out.lon = older.lon + (last.lon - older.lon) * fraction
                } else if (elapsedNanos - older.elapsedNanos <= last.elapsedNanos - elapsedNanos) {
                    out.lat = older.lat
                    out.lon = older.lon
                } else {
                    out.lat = last.lat
                    out.lon = last.lon
                }
            }
            else -> {
                val oldest = older ?: last
                out.lat = oldest.lat
                out.lon = oldest.lon
            }
        }
        out.headingDeg = if (courseDeg.isNaN()) EventPosition.UNKNOWN_HEADING else roundHeading(courseDeg)
        out.wallTimeMs = last.wallTimeMs + Math.floorDiv(elapsedNanos - last.elapsedNanos, NANOS_PER_MS)
        return true
    }

    fun reset() {
        previous = null
        newest = null
        newestNanos = 0L
        speedMps = Double.NaN
        accuracyM = Double.NaN
        courseDeg = Double.NaN
        headingRateDegPerS = Double.NaN
    }

    /** [courseDeg] is already in [0, 360); rounding 359.5 and above wraps to 0. */
    private fun roundHeading(courseDeg: Double): Int = courseDeg.roundToInt() % FULL_CIRCLE_DEG

    private companion object {
        const val FULL_CIRCLE_DEG = 360
    }
}
