package com.mapmyroad.map

/**
 * Road quality band (UI_UX_GUIDELINES.md section 4.2). [key] is the value of the GeoJSON `band` property that the
 * map line layers filter on, so it must stay stable.
 */
enum class RoadBand(
    val key: String,
) {
    /** Score 70 to 100, "Smooth", solid line. */
    GOOD(key = "good"),

    /** Score 40 to 69, "Rough", dashed line. */
    FAIR(key = "fair"),

    /** Score 0 to 39, "Very rough", dotted and thicker line. */
    POOR(key = "poor"),

    /** No score, "No data yet", thin grey line. */
    NO_DATA(key = "no_data"),
    ;

    companion object {
        /** Lowest score in the Good band (UI_UX_GUIDELINES.md section 4.2). */
        const val GOOD_MIN_SCORE = 70

        /** Lowest score in the Fair band (UI_UX_GUIDELINES.md section 4.2). */
        const val FAIR_MIN_SCORE = 40

        fun fromScore(score: Int?): RoadBand =
            when {
                score == null -> NO_DATA
                score >= GOOD_MIN_SCORE -> GOOD
                score >= FAIR_MIN_SCORE -> FAIR
                else -> POOR
            }
    }
}
