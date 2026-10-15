package com.mapmyroad.domain.model

import com.mapmyroad.detector.VehicleType

/** A geocoding result (API_SPEC.md 5.7). */
data class Place(
    val name: String,
    val displayName: String,
    val lat: Double,
    val lon: Double,
    val type: String?,
)

enum class RouteLabel(
    val code: String,
) {
    FASTEST(code = "fastest"),
    SMOOTHEST(code = "smoothest"),
    BALANCED(code = "balanced"),
    ;

    companion object {
        fun fromCode(code: String): RouteLabel? = entries.firstOrNull { it.code == code }
    }
}

enum class CoverageLevel(
    val code: String,
) {
    NONE(code = "none"),
    PARTIAL(code = "partial"),
    GOOD(code = "good"),
    ;

    companion object {
        fun fromCode(code: String): CoverageLevel? = entries.firstOrNull { it.code == code }
    }
}

data class RouteCoverage(
    val level: CoverageLevel,
    /** 0 to 1. */
    val fractionWithData: Double,
)

/** One route candidate (API_SPEC.md 5.8). */
data class RouteCandidate(
    val id: String,
    val labels: Set<RouteLabel>,
    val durationS: Int,
    val distanceM: Int,
    /** 0 to 100, higher is smoother. Meaningless when coverage is NONE (UI_UX S8). */
    val comfortScore: Int,
    val severeCount: Int,
    val moderateCount: Int,
    val minorCount: Int,
    /** Encoded polyline, precision 6. */
    val encodedPolyline6: String,
    val isRecommended: Boolean,
    val coverage: RouteCoverage?,
)

data class RouteOptions(
    val requestId: String,
    val candidates: List<RouteCandidate>,
    val coverage: RouteCoverage,
)

data class RouteQuery(
    val originLat: Double,
    val originLon: Double,
    val destinationLat: Double,
    val destinationLon: Double,
    val vehicleType: VehicleType,
    /** 0 prefers fastest, 1 prefers smoothest (PRD RT-3). */
    val preference: Double,
)
