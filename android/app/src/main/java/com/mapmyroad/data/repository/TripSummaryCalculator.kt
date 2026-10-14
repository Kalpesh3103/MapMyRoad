package com.mapmyroad.data.repository

import com.mapmyroad.detector.RoughnessWindow
import com.mapmyroad.detector.Severity
import com.mapmyroad.detector.TripScoring
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.TripPoint
import javax.inject.Inject

/** Summary numbers written to `trip` when a trip ends (M2-8, PRD PM-3). */
data class TripSummary(
    val distanceM: Double,
    val durationS: Int,
    /** 0 to 100, null when there was too little data. */
    val smoothnessScore: Int?,
    val minorCount: Int,
    val moderateCount: Int,
    val severeCount: Int,
)

/**
 * Computes a [TripSummary] from the stored points, hazards and roughness windows. Pure Kotlin (no Android
 * dependencies) and deterministic, so it is unit-tested on the JVM.
 *
 * [smoothnessScorer] is injectable for tests; production uses [TripScoring.smoothnessScore].
 */
class TripSummaryCalculator(
    private val smoothnessScorer: (List<RoughnessWindow>) -> Int?,
) {
    @Inject
    constructor() : this(smoothnessScorer = { windows -> TripScoring.smoothnessScore(windows) })

    fun calculate(
        startedAtMs: Long,
        endedAtMs: Long,
        points: List<TripPoint>,
        hazards: List<RecordedHazard>,
        samples: List<SegmentSample>,
    ): TripSummary =
        TripSummary(
            distanceM = distanceM(points),
            durationS = durationS(startedAtMs, endedAtMs),
            smoothnessScore = smoothnessScorer(samples.sortedBy { it.observedAtMs }.map { it.toRoughnessWindow() }),
            minorCount = hazards.count { it.severity == Severity.MINOR },
            moderateCount = hazards.count { it.severity == Severity.MODERATE },
            severeCount = hazards.count { it.severity == Severity.SEVERE },
        )

    /**
     * Sum of great-circle distances between consecutive points (sorted by time). A step that implies more than
     * [MAX_PLAUSIBLE_SPEED_KMH] is a GPS jump: it is skipped and the last accepted point stays the anchor, so the
     * distance to the next good fix still counts. After [MAX_CONSECUTIVE_REJECTIONS] rejections in a row the
     * anchor itself is assumed to be the bad fix and the current point becomes the new anchor (without adding
     * distance). Steps with no time difference are skipped.
     */
    fun distanceM(points: List<TripPoint>): Double {
        if (points.size < 2) return 0.0
        val sorted = points.sortedBy { it.tMs }
        var anchor = sorted.first()
        var rejections = 0
        var totalM = 0.0
        for (i in 1 until sorted.size) {
            val point = sorted[i]
            val dtS = (point.tMs - anchor.tMs) / MS_PER_S
            if (dtS <= 0.0) continue
            val stepM = TripScoring.haversineM(anchor.lat, anchor.lon, point.lat, point.lon)
            val speedKmh = stepM / dtS * MPS_TO_KMH
            if (speedKmh > MAX_PLAUSIBLE_SPEED_KMH) {
                rejections++
                if (rejections >= MAX_CONSECUTIVE_REJECTIONS) {
                    anchor = point
                    rejections = 0
                }
                continue
            }
            totalM += stepM
            anchor = point
            rejections = 0
        }
        return totalM
    }

    fun durationS(
        startedAtMs: Long,
        endedAtMs: Long,
    ): Int = ((endedAtMs - startedAtMs) / MS_PER_S_LONG).coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()

    companion object {
        /** No road vehicle in scope (two-wheelers, cars, auto-rickshaws) legitimately exceeds this. */
        const val MAX_PLAUSIBLE_SPEED_KMH = 200.0

        /** Rejections in a row after which the anchor point is treated as the outlier. */
        const val MAX_CONSECUTIVE_REJECTIONS = 3

        private const val MS_PER_S = 1000.0
        private const val MS_PER_S_LONG = 1000L
        private const val MPS_TO_KMH = 3.6
    }
}

/**
 * Stored samples keep wall time only, not the elapsed-realtime clock, so the elapsed fields are 0. Smoothness
 * scoring weights windows by [RoughnessWindow.lengthM] and does not read the elapsed fields.
 */
internal fun SegmentSample.toRoughnessWindow(): RoughnessWindow =
    RoughnessWindow(
        startElapsedNanos = 0L,
        endElapsedNanos = 0L,
        wallTimeMs = observedAtMs,
        startLat = startLat,
        startLon = startLon,
        endLat = endLat,
        endLon = endLon,
        lengthM = lengthM,
        roughness = roughness,
        speedKmh = speedKmh,
    )
