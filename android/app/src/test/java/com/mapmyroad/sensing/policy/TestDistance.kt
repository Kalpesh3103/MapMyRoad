package com.mapmyroad.sensing.policy

import kotlin.math.cos
import kotlin.math.sqrt

/** Equirectangular distance, accurate to well under 1 % over the short steps used in these tests. */
internal val TestDistance =
    DistanceCalculator { lat1, lon1, lat2, lon2 ->
        val meanLatRad = Math.toRadians((lat1 + lat2) / 2.0)
        val dxM = Math.toRadians(lon2 - lon1) * cos(meanLatRad) * EARTH_RADIUS_M
        val dyM = Math.toRadians(lat2 - lat1) * EARTH_RADIUS_M
        sqrt(dxM * dxM + dyM * dyM)
    }

/** Degrees of latitude per meter. */
internal const val DEG_PER_M = 1.0 / 111_195.0

internal const val BASE_LAT = 18.52
internal const val BASE_LON = 73.85

private const val EARTH_RADIUS_M = 6_371_000.0
