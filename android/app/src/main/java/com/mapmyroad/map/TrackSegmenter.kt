package com.mapmyroad.map

import com.mapmyroad.domain.model.TripPoint

/** A contiguous part of a trip track in one [band]. Usually has at least two points (see [TrackSegmenter]). */
data class TrackRun(
    val band: RoadBand,
    val points: List<TripPoint>,
)

/**
 * Splits a trip track into runs of consecutive points in the same road band, so that each run can be drawn as one
 * line with its band's style. Pure and deterministic.
 *
 * The line between two consecutive points takes the band of the first of them. When the band changes at point `i`,
 * the current run ends at point `i` and the next run starts at point `i`, so the drawn lines connect without gaps.
 * A track with a single point gives one run with one point (nothing to draw as a line). When only the last point
 * changes band, no extra one-point run is emitted, because that point already ends the previous run.
 */
object TrackSegmenter {
    fun segment(
        points: List<TripPoint>,
        bandOf: (TripPoint) -> RoadBand,
    ): List<TrackRun> {
        if (points.isEmpty()) return emptyList()
        val runs = ArrayList<TrackRun>()
        var runBand = bandOf(points[0])
        var current = ArrayList<TripPoint>()
        current.add(points[0])
        for (index in 1 until points.size) {
            val point = points[index]
            val band = bandOf(point)
            current.add(point)
            if (band != runBand) {
                runs.add(TrackRun(band = runBand, points = current))
                current = ArrayList()
                current.add(point)
                runBand = band
            }
        }
        if (current.size >= 2 || runs.isEmpty()) {
            runs.add(TrackRun(band = runBand, points = current))
        }
        return runs
    }
}
