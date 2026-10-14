package com.mapmyroad.map

import com.mapmyroad.domain.model.TripPoint

/** About 11.1 m of latitude per 0.0001 degree. */
internal const val DEG_PER_10_M = 0.00009

internal fun testPoint(
    index: Int,
    roughness: Double? = null,
    lat: Double = 18.5 + index * DEG_PER_10_M,
    lon: Double = 73.8,
): TripPoint =
    TripPoint(
        tripId = "trip",
        tMs = index * 1_000L,
        lat = lat,
        lon = lon,
        speedMps = 8.0,
        accuracyM = 5.0,
        roughness = roughness,
    )
