package com.mapmyroad.data.upload

import kotlinx.serialization.Serializable

// Request and response bodies of `POST /v1/events` (API_SPEC.md 5.3).

/** One upload request. Built only by [UploadPayloadBuilder] from the output of the TripTrimmer. */
@Serializable
data class EventUploadRequest(
    /** Random per trip (`trip.trip_token`), shared by every request of the trip. */
    val tripToken: String,
    /** [com.mapmyroad.detector.VehicleType.code]. */
    val vehicleType: String,
    val events: List<EventDto>,
    val windows: List<WindowDto>,
) {
    // Never print a payload: it contains precise coordinates and the trip token (CODING_STANDARDS.md 3.7).
    override fun toString(): String = "EventUploadRequest(events=${events.size}, windows=${windows.size})"
}

@Serializable
data class EventDto(
    /** Same id as `hazard_event_local.id` (DATABASE_SCHEMA.md B4), which makes retries idempotent. */
    val id: String,
    val lat: Double,
    val lon: Double,
    /** ISO-8601 UTC. */
    val observedAt: String,
    val severity: Int,
    val speedKmh: Double,
    val peakScore: Double,
    /** 0 to 359, omitted when unknown. */
    val headingDeg: Int? = null,
) {
    override fun toString(): String = "EventDto(***)"
}

@Serializable
data class WindowDto(
    /** Same id as `segment_sample_local.id` (DATABASE_SCHEMA.md B4). */
    val id: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    /** ISO-8601 UTC. */
    val observedAt: String,
    val lengthM: Double,
    val roughness: Double,
    val speedKmh: Double,
) {
    override fun toString(): String = "WindowDto(***)"
}

/** `202` body. Defaults keep parsing tolerant of fields the server may omit. */
@Serializable
data class EventUploadResponse(
    val accepted: ItemCounts = ItemCounts(),
    val duplicates: ItemCounts = ItemCounts(),
    val rejected: List<RejectedItem> = emptyList(),
)

@Serializable
data class ItemCounts(
    val events: Int = 0,
    val windows: Int = 0,
)

@Serializable
data class RejectedItem(
    val id: String = "",
    /** `event` or `window`. */
    val kind: String = "",
    /** OUT_OF_REGION, IMPLAUSIBLE_SPEED, FUTURE_TIMESTAMP, TOO_OLD, INVALID_SEVERITY, INVALID_WINDOW. */
    val reason: String = "",
)
