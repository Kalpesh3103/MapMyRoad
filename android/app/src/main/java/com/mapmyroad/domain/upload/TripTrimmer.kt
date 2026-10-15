package com.mapmyroad.domain.upload

import com.mapmyroad.detector.TripScoring
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.TripPoint

/** The items of one trip that may leave the phone, produced only by [TripTrimmer]. */
data class TrimmedTrip(
    val hazards: List<RecordedHazard>,
    val samples: List<SegmentSample>,
    /** Along-path length of the recorded track in meters (sum of straight lines between consecutive fixes). */
    val trackLengthM: Double,
) {
    val isEmpty: Boolean get() = hazards.isEmpty() && samples.isEmpty()
}

/**
 * Removes the start and end of a trip before upload (PRD PV-2, API_SPEC.md 5.3, SECURITY.md T1). This is the only
 * code that decides which recorded items may leave the phone (CODING_STANDARDS.md section 3.7).
 *
 * Rules:
 * - The track is the time-ordered list of fixes. Its along-path length is the sum of the straight lines between
 *   consecutive fixes. A track shorter than [MIN_TRIP_DISTANCE_M] uploads nothing.
 * - An item is kept only when its position along the path is at least [TRIM_DISTANCE_M] from both ends. The position
 *   comes from the item's time: an item between two fixes is bounded by those fixes, so a GPS gap never moves an item
 *   closer to the middle than the fixes around it. With duplicated timestamps the least favorable fix is used.
 *   A roughness window additionally keeps its own length as margin on both sides, so both of its endpoints are
 *   inside the kept section whichever end its timestamp refers to.
 * - An item is kept only when every one of its points is at least [TRIM_DISTANCE_M] in a straight line from the first
 *   and the last fix. This catches loops that end where they started (home to shop and back).
 * - Items whose time lies outside the track (before the first or after the last fix) are dropped.
 *
 * The along-path distance from straight lines between fixes is never longer than the real road distance, so both
 * rules err on the side of dropping data. The track itself never leaves the phone.
 *
 * Pure and deterministic. Allocates two primitive arrays per call (plus a sorted copy only when the input is not
 * in time order) and the output lists; it runs once per trip in the upload worker, not in the sensor hot path.
 */
object TripTrimmer {
    /** Distance removed at each end of a trip (PRD PV-2). Initial value from the docs, not a measured optimum. */
    const val TRIM_DISTANCE_M = 500.0

    /** Trips shorter than this upload nothing (API_SPEC.md 5.3: "about 1 km"). */
    const val MIN_TRIP_DISTANCE_M = 1_000.0

    fun trim(
        points: List<TripPoint>,
        hazards: List<RecordedHazard>,
        samples: List<SegmentSample>,
    ): TrimmedTrip {
        val track = Track.of(points) ?: return TrimmedTrip(emptyList(), emptyList(), trackLengthM = 0.0)
        if (track.totalM < MIN_TRIP_DISTANCE_M) {
            return TrimmedTrip(emptyList(), emptyList(), trackLengthM = track.totalM)
        }
        val keptHazards =
            hazards.filter { hazard ->
                track.isInsideKeptSection(hazard.observedAtMs, marginM = 0.0) &&
                    track.isFarFromEnds(hazard.lat, hazard.lon)
            }
        val keptSamples =
            samples.filter { sample ->
                sample.lengthM >= 0.0 &&
                    track.isInsideKeptSection(sample.observedAtMs, marginM = sample.lengthM) &&
                    track.isFarFromEnds(sample.startLat, sample.startLon) &&
                    track.isFarFromEnds(sample.endLat, sample.endLon)
            }
        return TrimmedTrip(keptHazards, keptSamples, trackLengthM = track.totalM)
    }

    /** Time-ordered fixes with cumulative along-path distance. */
    private class Track(
        private val timesMs: LongArray,
        private val cumulativeM: DoubleArray,
        private val startLat: Double,
        private val startLon: Double,
        private val endLat: Double,
        private val endLon: Double,
    ) {
        val totalM: Double = cumulativeM[cumulativeM.size - 1]

        fun isInsideKeptSection(
            tMs: Long,
            marginM: Double,
        ): Boolean {
            val count = timesMs.size
            if (tMs < timesMs[0] || tMs > timesMs[count - 1]) return false
            val lower = lowerBound(tMs)
            val upper = upperBound(tMs)
            // Fewest meters from the start the item can be: at the first fix with the same time, else at the fix before.
            val minAlongM = if (lower < count && timesMs[lower] == tMs) cumulativeM[lower] else cumulativeM[lower - 1]
            // Most meters from the start: at the last fix with the same time, else at the fix after.
            val maxAlongM = if (upper > 0 && timesMs[upper - 1] == tMs) cumulativeM[upper - 1] else cumulativeM[upper]
            return minAlongM - marginM >= TRIM_DISTANCE_M && maxAlongM + marginM <= totalM - TRIM_DISTANCE_M
        }

        fun isFarFromEnds(
            lat: Double,
            lon: Double,
        ): Boolean =
            TripScoring.haversineM(startLat, startLon, lat, lon) >= TRIM_DISTANCE_M &&
                TripScoring.haversineM(endLat, endLon, lat, lon) >= TRIM_DISTANCE_M

        /** First index whose time is >= [tMs]. */
        private fun lowerBound(tMs: Long): Int {
            var low = 0
            var high = timesMs.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (timesMs[mid] < tMs) low = mid + 1 else high = mid
            }
            return low
        }

        /** First index whose time is > [tMs]. */
        private fun upperBound(tMs: Long): Int {
            var low = 0
            var high = timesMs.size
            while (low < high) {
                val mid = (low + high) ushr 1
                if (timesMs[mid] <= tMs) low = mid + 1 else high = mid
            }
            return low
        }

        companion object {
            /** Null when fewer than two usable fixes remain. Fixes with non-finite coordinates are ignored. */
            fun of(points: List<TripPoint>): Track? {
                val usable = points.filter { it.lat.isFinite() && it.lon.isFinite() }
                if (usable.size < 2) return null
                val ordered = if (isTimeOrdered(usable)) usable else usable.sortedBy { it.tMs }
                val count = ordered.size
                val timesMs = LongArray(count)
                val cumulativeM = DoubleArray(count)
                var previous = ordered[0]
                timesMs[0] = previous.tMs
                for (index in 1 until count) {
                    val point = ordered[index]
                    timesMs[index] = point.tMs
                    cumulativeM[index] =
                        cumulativeM[index - 1] + TripScoring.haversineM(previous.lat, previous.lon, point.lat, point.lon)
                    previous = point
                }
                val first = ordered[0]
                val last = ordered[count - 1]
                return Track(timesMs, cumulativeM, first.lat, first.lon, last.lat, last.lon)
            }

            private fun isTimeOrdered(points: List<TripPoint>): Boolean {
                for (index in 1 until points.size) {
                    if (points[index].tMs < points[index - 1].tMs) return false
                }
                return true
            }
        }
    }
}
