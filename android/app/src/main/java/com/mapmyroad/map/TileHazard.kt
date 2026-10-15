package com.mapmyroad.map

import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.HazardStatus

/**
 * A hazard as read from a community tile feature (API_SPEC.md 5.4). Used to open the detail sheet right away and as
 * the fallback when the detail request fails. Holds no coordinates.
 */
data class TileHazard(
    val id: String,
    val severity: Severity,
    /** 0 to 1, null when missing or invalid. */
    val confidence: Double?,
    val status: HazardStatus?,
    /** Whole days since last seen, null when missing. */
    val seenDays: Int?,
) {
    companion object {
        /** Null when the feature has no ID or no valid severity, so a malformed feature is never tappable. */
        fun fromProperties(
            id: String?,
            severity: Number?,
            confidence: Number?,
            status: String?,
            seenDays: Number?,
        ): TileHazard? {
            if (id.isNullOrBlank()) return null
            val parsedSeverity = severity?.toInt()?.let { Severity.fromLevel(it) } ?: return null
            val parsedConfidence = confidence?.toDouble()?.takeIf { it in 0.0..1.0 }
            return TileHazard(
                id = id,
                severity = parsedSeverity,
                confidence = parsedConfidence,
                status = status?.let { HazardStatus.fromCode(it) },
                seenDays = seenDays?.toInt()?.takeIf { it >= 0 },
            )
        }
    }
}
