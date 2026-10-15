package com.mapmyroad.data.community

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

// Wire shapes of backend/src/main/java/com/mapmyroad/backend/api/hazards (HazardGeoJson, FeedbackRequest,
// FeedbackResponse). Fields are nullable with defaults so missing values are caught by the mapper, not the decoder.

/** `GET /v1/hazards`: a GeoJSON FeatureCollection. Features stay raw so each is decoded on its own. */
@Serializable
data class HazardFeatureCollectionDto(
    val type: String? = null,
    val features: List<JsonElement> = emptyList(),
) {
    override fun toString(): String = "HazardFeatureCollectionDto(features=${features.size})"
}

/** One hazard (`GET /v1/hazards/{id}` and each list feature). */
@Serializable
data class HazardFeatureDto(
    val type: String? = null,
    val geometry: PointDto? = null,
    val properties: HazardPropertiesDto? = null,
)

/** GeoJSON Point, `coordinates` is `[lon, lat]`. */
@Serializable
data class PointDto(
    val type: String? = null,
    val coordinates: List<Double> = emptyList(),
) {
    override fun toString(): String = "PointDto(***)"
}

/** `firstSeen` and `lastSeen` are ISO-8601 instants truncated to midnight UTC (SECURITY.md 6.4). */
@Serializable
data class HazardPropertiesDto(
    val id: String? = null,
    val severity: Int? = null,
    val confidence: Double? = null,
    val status: String? = null,
    val firstSeen: String? = null,
    val lastSeen: String? = null,
    val confirmCount: Int? = null,
    val dismissCount: Int? = null,
)

/**
 * Body of `POST /v1/hazards/{id}/feedback`. The location is used by the server only for its 50 m check and is not
 * stored (API_SPEC.md 5.6); [toString] leaves it out.
 */
@Serializable
class FeedbackRequestDto(
    val action: String,
    val lat: Double,
    val lon: Double,
    val observedAt: String,
) {
    override fun toString(): String = "FeedbackRequestDto(action=$action)"
}

@Serializable
data class FeedbackResponseDto(
    val id: String? = null,
    val confidence: Double? = null,
    val status: String? = null,
)
