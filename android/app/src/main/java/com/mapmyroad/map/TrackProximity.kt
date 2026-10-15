package com.mapmyroad.map

import com.mapmyroad.domain.model.TripPoint
import kotlin.math.cos
import kotlin.math.roundToLong
import kotlin.math.sqrt

/**
 * The point of a track closest to a query point. [lat], [lon] and [tMs] are interpolated on the closest track
 * segment, so they lie on the recorded path. [alongTrackM] is the distance from the start of the track.
 * [toString] is redacted so coordinates never reach logs (CODING_STANDARDS.md section 3.7).
 */
class TrackMatch(
    val distanceM: Double,
    val lat: Double,
    val lon: Double,
    val tMs: Long,
    val alongTrackM: Double,
) {
    override fun toString(): String = "TrackMatch(***)"
}

/**
 * Point-to-track distance for the post-trip hazard review (UI_UX_GUIDELINES.md S12). Uses a local equirectangular
 * projection around the query point, accurate to well under a meter at the tens of meters compared here. Pure and
 * deterministic.
 */
object TrackProximity {
    private const val EARTH_RADIUS_M = 6_371_000.0

    /** Closest point of [points] (in track order) to [lat]/[lon], or null when the track is empty. */
    fun nearest(
        points: List<TripPoint>,
        lat: Double,
        lon: Double,
    ): TrackMatch? {
        if (points.isEmpty()) return null
        val cosLat = cos(Math.toRadians(lat))
        val first = points.first()
        var best =
            TrackMatch(
                distanceM = hypot(x(first.lon, lon, cosLat), y(first.lat, lat)),
                lat = first.lat,
                lon = first.lon,
                tMs = first.tMs,
                alongTrackM = 0.0,
            )
        var alongM = 0.0
        for (index in 1 until points.size) {
            val a = points[index - 1]
            val b = points[index]
            // Segment a->b in meters, relative to the query point at the origin.
            val ax = x(a.lon, lon, cosLat)
            val ay = y(a.lat, lat)
            val dx = x(b.lon, lon, cosLat) - ax
            val dy = y(b.lat, lat) - ay
            val lengthSq = dx * dx + dy * dy
            val t = if (lengthSq == 0.0) 0.0 else ((-ax * dx - ay * dy) / lengthSq).coerceIn(0.0, 1.0)
            val px = ax + t * dx
            val py = ay + t * dy
            val distanceM = hypot(px, py)
            val segmentM = sqrt(lengthSq)
            if (distanceM < best.distanceM) {
                best =
                    TrackMatch(
                        distanceM = distanceM,
                        lat = a.lat + t * (b.lat - a.lat),
                        lon = a.lon + t * (b.lon - a.lon),
                        tMs = a.tMs + (t * (b.tMs - a.tMs)).roundToLong(),
                        alongTrackM = alongM + t * segmentM,
                    )
            }
            alongM += segmentM
        }
        return best
    }

    private fun x(
        pointLon: Double,
        originLon: Double,
        cosLat: Double,
    ): Double = Math.toRadians(pointLon - originLon) * cosLat * EARTH_RADIUS_M

    private fun y(
        pointLat: Double,
        originLat: Double,
    ): Double = Math.toRadians(pointLat - originLat) * EARTH_RADIUS_M

    private fun hypot(
        x: Double,
        y: Double,
    ): Double = sqrt(x * x + y * y)
}
