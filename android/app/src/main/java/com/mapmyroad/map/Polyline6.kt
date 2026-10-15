package com.mapmyroad.map

import kotlin.math.roundToLong

/**
 * A latitude and longitude in decimal degrees. [toString] is redacted so coordinates never reach logs by accident
 * (CODING_STANDARDS.md section 3.7).
 */
class GeoPoint(
    val lat: Double,
    val lon: Double,
) {
    override fun equals(other: Any?): Boolean = other is GeoPoint && lat == other.lat && lon == other.lon

    override fun hashCode(): Int = 31 * lat.hashCode() + lon.hashCode()

    override fun toString(): String = "GeoPoint(***)"
}

/**
 * Encoded polyline with precision 6 (six decimal places), the format of route `geometry` in API_SPEC.md 5.8
 * (Valhalla's default). Same algorithm as Google's encoded polyline format, with a factor of 1e6 instead of 1e5.
 * Pure Kotlin, so it runs in JVM tests.
 */
object Polyline6 {
    private const val FACTOR = 1e6
    private const val CHUNK_BITS = 5
    private const val CHUNK_MASK = 0x1f
    private const val CONTINUATION_BIT = 0x20
    private const val ASCII_OFFSET = 63

    /** Real values need far fewer bits; this guards against runaway continuation bits in corrupt input. */
    private const val MAX_SHIFT = 60

    /**
     * Decodes [encoded] into points. Malformed input never throws: decoding stops at the first incomplete or
     * invalid value and returns the points decoded so far.
     */
    fun decode(encoded: String): List<GeoPoint> {
        val points = ArrayList<GeoPoint>(encoded.length / 4)
        var index = 0
        var lat = 0L
        var lon = 0L
        while (index < encoded.length) {
            val latResult = readValue(encoded, index) ?: break
            val lonResult = readValue(encoded, latResult.nextIndex) ?: break
            lat += latResult.delta
            lon += lonResult.delta
            index = lonResult.nextIndex
            points.add(GeoPoint(lat = lat / FACTOR, lon = lon / FACTOR))
        }
        return points
    }

    /** Encodes [points]; the inverse of [decode] up to rounding to six decimals. Used by tests and fixtures. */
    fun encode(points: List<GeoPoint>): String {
        val builder = StringBuilder()
        var previousLat = 0L
        var previousLon = 0L
        for (point in points) {
            val lat = (point.lat * FACTOR).roundToLong()
            val lon = (point.lon * FACTOR).roundToLong()
            writeValue(builder, lat - previousLat)
            writeValue(builder, lon - previousLon)
            previousLat = lat
            previousLon = lon
        }
        return builder.toString()
    }

    private class ValueResult(
        val delta: Long,
        val nextIndex: Int,
    )

    private fun readValue(
        encoded: String,
        start: Int,
    ): ValueResult? {
        var index = start
        var result = 0L
        var shift = 0
        while (true) {
            if (index >= encoded.length || shift > MAX_SHIFT) return null
            val chunk = encoded[index].code - ASCII_OFFSET
            index++
            if (chunk < 0 || chunk > (CHUNK_MASK or CONTINUATION_BIT)) return null
            result = result or ((chunk and CHUNK_MASK).toLong() shl shift)
            shift += CHUNK_BITS
            if ((chunk and CONTINUATION_BIT) == 0) break
        }
        val delta = if ((result and 1L) != 0L) (result shr 1).inv() else result shr 1
        return ValueResult(delta = delta, nextIndex = index)
    }

    private fun writeValue(
        builder: StringBuilder,
        value: Long,
    ) {
        var remaining = if (value < 0) (value shl 1).inv() else value shl 1
        while (remaining >= CONTINUATION_BIT) {
            builder.append(((CONTINUATION_BIT or (remaining and CHUNK_MASK.toLong()).toInt()) + ASCII_OFFSET).toChar())
            remaining = remaining shr CHUNK_BITS
        }
        builder.append((remaining.toInt() + ASCII_OFFSET).toChar())
    }
}
