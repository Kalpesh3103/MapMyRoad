package com.mapmyroad.detector

/** Vehicle profile (PRD TS-2). [code] matches API_SPEC.md section 1.1, [dbCode] matches DATABASE_SCHEMA.md A1. */
enum class VehicleType(
    val code: String,
    val dbCode: Int,
) {
    MOTORCYCLE(code = "motorcycle", dbCode = 1),
    SCOOTER(code = "scooter", dbCode = 2),
    CAR(code = "car", dbCode = 3),
    AUTO_RICKSHAW(code = "auto_rickshaw", dbCode = 4),
    ;

    companion object {
        fun fromDbCode(dbCode: Int): VehicleType? = entries.firstOrNull { it.dbCode == dbCode }

        fun fromCode(code: String): VehicleType? = entries.firstOrNull { it.code == code }
    }
}

/** Bump severity (PRD TS-4). [level] is the stored and uploaded value: 1 minor, 2 moderate, 3 severe. */
enum class Severity(
    val level: Int,
) {
    MINOR(level = 1),
    MODERATE(level = 2),
    SEVERE(level = 3),
    ;

    companion object {
        fun fromLevel(level: Int): Severity? = entries.firstOrNull { it.level == level }
    }
}

/**
 * One GPS fix. Fixes arrive about once per second, so this is a regular class (allocation is fine here, unlike
 * the sensor path). [elapsedNanos] is on the elapsed-realtime clock shared with sensor timestamps.
 */
data class LocationFix(
    val elapsedNanos: Long,
    val wallTimeMs: Long,
    val lat: Double,
    val lon: Double,
    /** Null when the provider reported no speed. */
    val speedMps: Float?,
    /** Horizontal accuracy radius. Null when unknown, which the detector treats as poor accuracy. */
    val accuracyM: Float?,
    /** Null when the provider reported no bearing. */
    val bearingDeg: Float?,
)

/** A detected bump (PRD TS-4), positioned by interpolation between the neighbouring fixes (M2-3). */
data class BumpEvent(
    val elapsedNanos: Long,
    val wallTimeMs: Long,
    val lat: Double,
    val lon: Double,
    val severity: Severity,
    val speedKmh: Double,
    /** Unitless, already normalized for speed and vehicle. Higher is a stronger jolt (API_SPEC.md 5.3). */
    val peakScore: Double,
    /** 0 to 359, null when unknown. */
    val headingDeg: Int?,
)

/** Roughness over about [DetectorConfig.windowLengthM] of travel (PRD TS-9, M2-4). */
data class RoughnessWindow(
    val startElapsedNanos: Long,
    val endElapsedNanos: Long,
    /** Wall time at the end of the window. */
    val wallTimeMs: Long,
    val startLat: Double,
    val startLon: Double,
    val endLat: Double,
    val endLon: Double,
    val lengthM: Double,
    /** Unitless normalized roughness index, 0 or greater (API_SPEC.md 5.3). */
    val roughness: Double,
    val speedKmh: Double,
)

/** Receives detector output. Called synchronously on the thread that feeds the detector. */
interface DetectorListener {
    fun onBump(event: BumpEvent)

    fun onRoughnessWindow(window: RoughnessWindow)
}
