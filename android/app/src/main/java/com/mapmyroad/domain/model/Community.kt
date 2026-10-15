package com.mapmyroad.domain.model

import com.mapmyroad.detector.Severity

enum class HazardStatus(
    val code: String,
) {
    ACTIVE(code = "active"),
    STALE(code = "stale"),
    FIXED(code = "fixed"),
    ;

    companion object {
        fun fromCode(code: String): HazardStatus? = entries.firstOrNull { it.code == code }
    }
}

/** A hazard from the shared map (API_SPEC.md 5.5). Dates are truncated to the day by the server. */
data class CommunityHazard(
    val id: String,
    val lat: Double,
    val lon: Double,
    val severity: Severity,
    /** 0 to 1. */
    val confidence: Double,
    val status: HazardStatus,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val confirmCount: Int,
    val dismissCount: Int,
)

/** "Still there" or "Fixed" (PRD CM-4, collected only after the trip or while stopped, ADR-0013). */
enum class HazardFeedbackAction(
    val code: String,
) {
    CONFIRM(code = "confirm"),
    DISMISS(code = "dismiss"),
}

data class HazardFeedbackOutcome(
    val hazardId: String,
    val confidence: Double,
    val status: HazardStatus,
)
