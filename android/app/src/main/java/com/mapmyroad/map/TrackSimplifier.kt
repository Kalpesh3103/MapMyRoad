package com.mapmyroad.map

import com.mapmyroad.domain.model.TripPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Light geometry simplification for map display (CODING_STANDARDS.md section 9): drops points closer than a minimum
 * spacing to the previous kept point. The first and last points are always kept, so runs from [TrackSegmenter]
 * still connect after simplification. Pure and deterministic.
 */
object TrackSimplifier {
    /** Below this spacing extra points add nothing visible at street zoom levels. */
    const val DEFAULT_MIN_SPACING_M = 5.0

    private const val EARTH_RADIUS_M = 6_371_000.0

    fun simplify(
        points: List<TripPoint>,
        minSpacingM: Double = DEFAULT_MIN_SPACING_M,
    ): List<TripPoint> {
        if (points.size <= 2) return points
        val kept = ArrayList<TripPoint>(points.size)
        kept.add(points.first())
        for (index in 1 until points.lastIndex) {
            val point = points[index]
            if (approxDistanceM(kept.last(), point) >= minSpacingM) {
                kept.add(point)
            }
        }
        val last = points.last()
        // The last point is always kept. If the previous kept point is too close to it, that one goes instead,
        // except the first point, which also must stay.
        if (kept.size > 1 && approxDistanceM(kept.last(), last) < minSpacingM) {
            kept.removeAt(kept.lastIndex)
        }
        kept.add(last)
        return kept
    }

    /** Equirectangular approximation, accurate to well under a meter over the short distances compared here. */
    internal fun approxDistanceM(
        a: TripPoint,
        b: TripPoint,
    ): Double {
        val meanLatRad = Math.toRadians((a.lat + b.lat) / 2.0)
        val dx = Math.toRadians(b.lon - a.lon) * cos(meanLatRad)
        val dy = Math.toRadians(b.lat - a.lat)
        return sqrt(dx * dx + dy * dy) * EARTH_RADIUS_M
    }
}
