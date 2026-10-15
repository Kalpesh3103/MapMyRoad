package com.mapmyroad.data.community

import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.CommunityHazard
import com.mapmyroad.domain.model.HazardFeedbackAction
import com.mapmyroad.domain.model.HazardFeedbackOutcome
import com.mapmyroad.domain.model.HazardStatus
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.roundToLong

/**
 * Maps hazards API DTOs to domain models (API_SPEC.md 5.5, 5.6). Malformed features become null and are skipped,
 * so one bad feature never hides the others. Pure, no Android dependencies.
 */
object HazardDtoMapper {
    private val json = Json { ignoreUnknownKeys = true }

    /** Feedback locations are sent with 5 decimals (about 1 m), as precise as the server's 50 m check needs. */
    private const val FEEDBACK_COORDINATE_SCALE = 100_000.0

    fun collection(dto: HazardFeatureCollectionDto): List<CommunityHazard> = dto.features.mapNotNull { feature(it) }

    /** One raw GeoJSON feature, or null when it cannot be read. */
    fun feature(element: JsonElement): CommunityHazard? {
        val dto =
            try {
                json.decodeFromJsonElement(HazardFeatureDto.serializer(), element)
            } catch (e: SerializationException) {
                return null
            } catch (e: IllegalArgumentException) {
                return null
            }
        return hazard(dto)
    }

    fun hazard(dto: HazardFeatureDto): CommunityHazard? {
        val properties = dto.properties ?: return null
        val coordinates = dto.geometry?.coordinates ?: return null
        if (coordinates.size < 2) return null
        val lon = coordinates[0]
        val lat = coordinates[1]
        if (!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
        val id = properties.id?.takeIf { it.isNotBlank() } ?: return null
        val severity = properties.severity?.let { Severity.fromLevel(it) } ?: return null
        val confidence = properties.confidence?.takeIf { it in 0.0..1.0 } ?: return null
        val status = properties.status?.let { HazardStatus.fromCode(it) } ?: return null
        val lastSeenMs = properties.lastSeen?.let { parseDateMs(it) } ?: return null
        val firstSeenMs = properties.firstSeen?.let { parseDateMs(it) } ?: lastSeenMs
        return CommunityHazard(
            id = id,
            lat = lat,
            lon = lon,
            severity = severity,
            confidence = confidence,
            status = status,
            firstSeenMs = firstSeenMs,
            lastSeenMs = lastSeenMs,
            confirmCount = properties.confirmCount?.coerceAtLeast(0) ?: 0,
            dismissCount = properties.dismissCount?.coerceAtLeast(0) ?: 0,
        )
    }

    fun outcome(dto: FeedbackResponseDto): HazardFeedbackOutcome? {
        val id = dto.id?.takeIf { it.isNotBlank() } ?: return null
        val confidence = dto.confidence?.takeIf { it in 0.0..1.0 } ?: return null
        val status = dto.status?.let { HazardStatus.fromCode(it) } ?: return null
        return HazardFeedbackOutcome(hazardId = id, confidence = confidence, status = status)
    }

    fun feedbackRequest(
        action: HazardFeedbackAction,
        lat: Double,
        lon: Double,
        observedAtMs: Long,
    ): FeedbackRequestDto =
        FeedbackRequestDto(
            action = action.code,
            lat = roundCoordinate(lat),
            lon = roundCoordinate(lon),
            observedAt = Instant.ofEpochMilli(observedAtMs).truncatedTo(ChronoUnit.SECONDS).toString(),
        )

    /** `minLon,minLat,maxLon,maxLat` with a dot decimal separator regardless of the device locale. */
    fun bboxParam(
        south: Double,
        west: Double,
        north: Double,
        east: Double,
    ): String = String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%.6f", west, south, east, north)

    /** ISO-8601 instant ("2026-09-02T00:00:00Z"), or a plain date taken as midnight UTC; null when unreadable. */
    internal fun parseDateMs(text: String): Long? =
        try {
            Instant.parse(text).toEpochMilli()
        } catch (notInstant: DateTimeParseException) {
            try {
                LocalDate
                    .parse(text)
                    .atStartOfDay(ZoneOffset.UTC)
                    .toInstant()
                    .toEpochMilli()
            } catch (notDate: DateTimeParseException) {
                null
            }
        }

    private fun roundCoordinate(value: Double): Double = (value * FEEDBACK_COORDINATE_SCALE).roundToLong() / FEEDBACK_COORDINATE_SCALE
}
