package com.mapmyroad.data.routing

import com.mapmyroad.domain.model.CoverageLevel
import com.mapmyroad.domain.model.Place
import com.mapmyroad.domain.model.RouteCandidate
import com.mapmyroad.domain.model.RouteCoverage
import com.mapmyroad.domain.model.RouteLabel
import com.mapmyroad.domain.model.RouteOptions
import com.mapmyroad.domain.model.RouteQuery
import kotlin.math.roundToLong

/** Pure DTO and domain conversions for geocoding and routes, unit tested with JSON fixtures. */
object RoutingMappers {
    /** API_SPEC.md 5.7: the query must have at least 3 characters after trimming and collapsing whitespace. */
    const val MIN_QUERY_LENGTH = 3

    /** Results asked for per search (API_SPEC.md 5.7 allows up to 10). */
    const val SEARCH_LIMIT = 8

    private const val BIAS_DECIMALS_FACTOR = 100.0
    private val WHITESPACE = Regex("\\s+")

    /** Trims and collapses whitespace runs, as the server does. */
    fun normalizeQuery(query: String): String = query.trim().replace(WHITESPACE, " ")

    /**
     * Rounds a search bias coordinate to 2 decimals (about 1 km). The server rounds the same way before it uses the
     * bias (API_SPEC.md 5.7), so rounding on the phone loses nothing and keeps the exact position off the network.
     */
    fun roundBias(value: Double): Double = (value * BIAS_DECIMALS_FACTOR).roundToLong() / BIAS_DECIMALS_FACTOR

    /** Places without any name are dropped: there is nothing to show for them. */
    fun toPlaces(response: GeocodeResponseDto): List<Place> = response.results.mapNotNull(::toPlace)

    fun toPlace(dto: GeocodePlaceDto): Place? {
        val name = dto.name?.takeIf { it.isNotBlank() } ?: dto.displayName?.takeIf { it.isNotBlank() } ?: return null
        val displayName = dto.displayName?.takeIf { it.isNotBlank() } ?: name
        if (!dto.lat.isFinite() || !dto.lon.isFinite()) return null
        return Place(name = name, displayName = displayName, lat = dto.lat, lon = dto.lon, type = dto.type)
    }

    fun toRequest(query: RouteQuery): RouteRequestDto =
        RouteRequestDto(
            origin = PositionDto(lat = query.originLat, lon = query.originLon),
            destination = PositionDto(lat = query.destinationLat, lon = query.destinationLon),
            vehicleType = query.vehicleType.code,
            preference = query.preference.coerceIn(0.0, 1.0),
        )

    fun toRouteOptions(response: RouteResponseDto): RouteOptions =
        RouteOptions(
            requestId = response.requestId,
            candidates = response.candidates.map(::toCandidate),
            coverage = toCoverage(response.coverage),
        )

    fun toCandidate(dto: RouteCandidateDto): RouteCandidate =
        RouteCandidate(
            id = dto.id,
            // Unknown labels from a newer server are ignored (API_SPEC.md section 7).
            labels = dto.labels.mapNotNull(RouteLabel::fromCode).toSet(),
            durationS = dto.durationSec.toSafeInt(),
            distanceM = dto.distanceM.toSafeInt(),
            comfortScore = dto.comfortScore.coerceIn(0, MAX_SCORE),
            severeCount = dto.hazards.severe.coerceAtLeast(0),
            moderateCount = dto.hazards.moderate.coerceAtLeast(0),
            minorCount = dto.hazards.minor.coerceAtLeast(0),
            encodedPolyline6 = dto.geometry,
            isRecommended = dto.recommended,
            coverage = dto.coverage?.let(::toCoverage),
        )

    /**
     * An unknown coverage level from a newer server is treated as PARTIAL: the app then shows the "limited data"
     * notice but still shows the score, which is the cautious middle ground.
     */
    fun toCoverage(dto: CoverageDto): RouteCoverage =
        RouteCoverage(
            level = CoverageLevel.fromCode(dto.level) ?: CoverageLevel.PARTIAL,
            fractionWithData = if (dto.fractionWithData.isFinite()) dto.fractionWithData.coerceIn(0.0, 1.0) else 0.0,
        )

    private const val MAX_SCORE = 100

    private fun Long.toSafeInt(): Int = coerceIn(0L, Int.MAX_VALUE.toLong()).toInt()
}
