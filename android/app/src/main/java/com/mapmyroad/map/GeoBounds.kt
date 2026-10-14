package com.mapmyroad.map

/**
 * A latitude and longitude box. Does not handle the antimeridian (not needed for the launch region).
 * [toString] is redacted so coordinates never reach logs by accident (CODING_STANDARDS.md section 3.7).
 */
class GeoBounds(
    val south: Double,
    val west: Double,
    val north: Double,
    val east: Double,
) {
    val centerLat: Double get() = (south + north) / 2.0
    val centerLon: Double get() = (west + east) / 2.0

    /** True when the box has (almost) no extent, so a camera cannot be fitted to it. */
    val isPoint: Boolean get() = north - south < POINT_EPSILON_DEG && east - west < POINT_EPSILON_DEG

    fun contains(
        lat: Double,
        lon: Double,
    ): Boolean = lat in south..north && lon in west..east

    fun intersects(other: GeoBounds): Boolean = other.south <= north && other.north >= south && other.west <= east && other.east >= west

    override fun equals(other: Any?): Boolean =
        other is GeoBounds && south == other.south && west == other.west && north == other.north && east == other.east

    override fun hashCode(): Int {
        var result = south.hashCode()
        result = 31 * result + west.hashCode()
        result = 31 * result + north.hashCode()
        result = 31 * result + east.hashCode()
        return result
    }

    override fun toString(): String = "GeoBounds(***)"

    companion object {
        /** About 1 m. */
        const val POINT_EPSILON_DEG = 0.00001

        /** Bounds of all items, or null when there are none. */
        fun <T> of(
            items: Iterable<T>,
            lat: (T) -> Double,
            lon: (T) -> Double,
        ): GeoBounds? {
            var south = Double.POSITIVE_INFINITY
            var west = Double.POSITIVE_INFINITY
            var north = Double.NEGATIVE_INFINITY
            var east = Double.NEGATIVE_INFINITY
            var any = false
            for (item in items) {
                val itemLat = lat(item)
                val itemLon = lon(item)
                if (itemLat < south) south = itemLat
                if (itemLat > north) north = itemLat
                if (itemLon < west) west = itemLon
                if (itemLon > east) east = itemLon
                any = true
            }
            return if (any) GeoBounds(south = south, west = west, north = north, east = east) else null
        }

        /** The smallest box containing every non-null box, or null when there are none. */
        fun union(boxes: Iterable<GeoBounds?>): GeoBounds? {
            val present = boxes.filterNotNull()
            if (present.isEmpty()) return null
            return GeoBounds(
                south = present.minOf { it.south },
                west = present.minOf { it.west },
                north = present.maxOf { it.north },
                east = present.maxOf { it.east },
            )
        }
    }
}
