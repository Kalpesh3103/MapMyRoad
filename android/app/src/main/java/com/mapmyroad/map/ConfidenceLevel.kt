package com.mapmyroad.map

/**
 * Confidence of a community hazard as shown to people (UI_UX_GUIDELINES.md sections 4.3 and 5, S7). The thresholds
 * are initial values, not measured facts. The map draws the same levels as marker opacity, so the sheet text and
 * the marker always agree.
 */
enum class ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH,
    ;

    companion object {
        /** Lowest confidence shown as medium. */
        const val MEDIUM_MIN = 0.5

        /** Lowest confidence shown as high. */
        const val HIGH_MIN = 0.8

        /** Marker opacity per level: low confidence is lighter (UI_UX_GUIDELINES.md section 4.3). */
        const val LOW_OPACITY = 0.55f
        const val MEDIUM_OPACITY = 0.8f
        const val HIGH_OPACITY = 1f

        /** Confidence is 0 to 1; anything invalid counts as low. */
        fun of(confidence: Double): ConfidenceLevel =
            when {
                confidence.isNaN() || confidence < MEDIUM_MIN -> LOW
                confidence < HIGH_MIN -> MEDIUM
                else -> HIGH
            }
    }
}
