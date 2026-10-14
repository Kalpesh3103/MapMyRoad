package com.mapmyroad.domain.model

import com.mapmyroad.detector.Severity
import com.mapmyroad.detector.VehicleType

enum class StartMode(
    val code: String,
) {
    MANUAL(code = "manual"),
    AUTO(code = "auto"),
}

/** Why a trip ended. Matches the `stop_reason` analytics parameter (PRD section 9). */
enum class StopReason(
    val code: String,
) {
    MANUAL(code = "manual"),
    AUTO(code = "auto"),
    LOW_BATTERY(code = "low_battery"),
    /** The service died (process killed, crash) and the trip was closed on the next start (UI_UX section 8). */
    SERVICE_KILLED(code = "service_killed"),
    PERMISSION_REVOKED(code = "permission_revoked"),
}

/** A recorded trip (DATABASE_SCHEMA.md Part B, `trip`). [endedAtMs] is null while recording. */
data class Trip(
    val id: String,
    val vehicleType: VehicleType,
    val startMode: StartMode,
    val startedAtMs: Long,
    val endedAtMs: Long?,
    val distanceM: Double,
    val durationS: Int,
    /** 0 to 100, higher is smoother. Null when there was too little data. */
    val smoothnessScore: Int?,
    val minorCount: Int,
    val moderateCount: Int,
    val severeCount: Int,
    val stopReason: StopReason?,
) {
    val isFinished: Boolean get() = endedAtMs != null
}

/** Downsampled track point for display (DATABASE_SCHEMA.md B1, `trip_point`). Never uploaded. */
data class TripPoint(
    val tripId: String,
    val tMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Double,
    val accuracyM: Double,
    /** Roughness of the window this point falls in, when known. Used to color the trip line. */
    val roughness: Double?,
)

/** A bump detected on one of the user's own trips (DATABASE_SCHEMA.md B1, `hazard_event_local`). */
data class RecordedHazard(
    val id: String,
    val tripId: String,
    val lat: Double,
    val lon: Double,
    val observedAtMs: Long,
    val severity: Severity,
    val speedKmh: Double,
    val peakScore: Double,
    val headingDeg: Int?,
)

/** A roughness window (DATABASE_SCHEMA.md B1, `segment_sample_local`). Uploaded from v2 only. */
data class SegmentSample(
    val id: String,
    val tripId: String,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val observedAtMs: Long,
    val lengthM: Double,
    val roughness: Double,
    val speedKmh: Double,
)

/** One trip's track for the personal map. */
data class TripTrack(
    val tripId: String,
    val smoothnessScore: Int?,
    val points: List<TripPoint>,
)
