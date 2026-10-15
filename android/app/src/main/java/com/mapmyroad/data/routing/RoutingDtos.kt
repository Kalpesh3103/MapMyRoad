package com.mapmyroad.data.routing

import kotlinx.serialization.Serializable

// Wire DTOs of API_SPEC.md 5.7 and 5.8. Field names match the backend records in
// backend/.../api/geocode/GeocodeResponse.java, GeocodePlace.java and api/routes/RouteRequest.java,
// RouteResponse.java. Every class holding coordinates redacts toString (CODING_STANDARDS.md section 3.7).

/** Body of `GET /v1/geocode` and `GET /v1/geocode/reverse` (reverse has at most one result). */
@Serializable
data class GeocodeResponseDto(
    val results: List<GeocodePlaceDto> = emptyList(),
    val cached: Boolean = false,
) {
    override fun toString(): String = "GeocodeResponseDto(results=${results.size}, cached=$cached)"
}

@Serializable
data class GeocodePlaceDto(
    val name: String? = null,
    val displayName: String? = null,
    val lat: Double,
    val lon: Double,
    val type: String? = null,
) {
    override fun toString(): String = "GeocodePlaceDto(***)"
}

/** Body of `POST /v1/routes`. */
@Serializable
data class RouteRequestDto(
    val origin: PositionDto,
    val destination: PositionDto,
    val vehicleType: String,
    val preference: Double,
) {
    override fun toString(): String = "RouteRequestDto(vehicleType=$vehicleType, preference=$preference)"
}

@Serializable
data class PositionDto(
    val lat: Double,
    val lon: Double,
) {
    override fun toString(): String = "PositionDto(***)"
}

@Serializable
data class RouteResponseDto(
    val requestId: String,
    val candidates: List<RouteCandidateDto> = emptyList(),
    val coverage: CoverageDto,
) {
    override fun toString(): String = "RouteResponseDto(candidates=${candidates.size})"
}

@Serializable
data class RouteCandidateDto(
    val id: String,
    val labels: List<String> = emptyList(),
    val durationSec: Long,
    val distanceM: Long,
    val comfortScore: Int,
    val hazards: HazardCountsDto = HazardCountsDto(),
    /** Encoded polyline, precision 6. */
    val geometry: String,
    val recommended: Boolean = false,
    /** Per-candidate coverage, an additive field (API_SPEC.md 5.8); older servers omit it. */
    val coverage: CoverageDto? = null,
) {
    override fun toString(): String = "RouteCandidateDto(id=$id)"
}

@Serializable
data class HazardCountsDto(
    val severe: Int = 0,
    val moderate: Int = 0,
    val minor: Int = 0,
)

@Serializable
data class CoverageDto(
    val level: String,
    val fractionWithData: Double = 0.0,
)
