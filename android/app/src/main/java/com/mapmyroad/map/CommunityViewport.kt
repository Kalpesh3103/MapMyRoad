package com.mapmyroad.map

/**
 * What the community layers render in the visible area once the map is idle: used for the "Limited community data
 * here" banner (UI_UX_GUIDELINES.md S6) and the TalkBack summary (section 11). Counts only, no coordinates.
 */
data class CommunityViewport(
    val zoom: Double,
    val roadFeatureCount: Int,
    val minorCount: Int,
    val moderateCount: Int,
    val severeCount: Int,
) {
    val hazardCount: Int get() = minorCount + moderateCount + severeCount
}

/** Banner state of the community map. */
enum class CommunityCoverage {
    /** Nothing measured yet (style loading, or the map has not been idle in community mode). */
    UNKNOWN,

    /** Below the zoom where tiles have data: ask the user to zoom in instead of showing an empty map. */
    ZOOM_IN,

    /** No community roads or hazards in view. */
    LIMITED,

    /** Something is drawn. */
    AVAILABLE,
    ;

    companion object {
        fun of(viewport: CommunityViewport?): CommunityCoverage =
            when {
                viewport == null -> UNKNOWN
                viewport.zoom < CommunityTiles.SOURCE_MIN_ZOOM -> ZOOM_IN
                viewport.roadFeatureCount == 0 && viewport.hazardCount == 0 -> LIMITED
                else -> AVAILABLE
            }
    }
}
