package com.mapmyroad.map

/**
 * The backend vector tiles of the community map (API_SPEC.md 5.4). Tiles are public and need no headers. One tile
 * holds both layers: `roads` (zoom 12 and above) and `hazards` (zoom 13 to 18).
 */
object CommunityTiles {
    /** TileJSON spec version for the client-side tile set. */
    const val TILEJSON_VERSION = "3.0.0"

    /** Below zoom 12 the server always answers 204, so no tiles are requested there. */
    const val SOURCE_MIN_ZOOM = 12f

    /** Hazards are served up to zoom 18; MapLibre overzooms z18 tiles beyond that. */
    const val SOURCE_MAX_ZOOM = 18f

    const val ROADS_MIN_ZOOM = 12f

    /** UI_UX_GUIDELINES.md S6: markers from zoom 13. */
    const val HAZARDS_MIN_ZOOM = 13f

    const val ROADS_SOURCE_LAYER = "roads"
    const val HAZARDS_SOURCE_LAYER = "hazards"

    /** `roads` feature property: 0 to 100, higher is smoother. */
    const val PROP_SCORE = "score"

    /** `hazards` feature properties. */
    const val PROP_ID = "id"
    const val PROP_SEVERITY = "sev"
    const val PROP_CONFIDENCE = "conf"
    const val PROP_STATUS = "status"
    const val PROP_SEEN_DAYS = "seenDays"

    /**
     * Tile URL template built from the API base URL, so the source does not depend on the base URL that the
     * TileJSON advertises (which differs behind adb reverse or a proxy).
     */
    fun tileUrlTemplate(apiBaseUrl: String): String = "${apiBaseUrl.trimEnd('/')}/v1/tiles/{z}/{x}/{y}.mvt"

    fun tileJsonUrl(apiBaseUrl: String): String = "${apiBaseUrl.trimEnd('/')}/v1/tiles/tilejson.json"

    /**
     * Score range of [band] in the `roads` layer, used for the line layer filters. Same thresholds as
     * [RoadBand.fromScore]. [RoadBand.NO_DATA] has no range: the tiles only contain scored segments.
     */
    fun scoreRange(band: RoadBand): ScoreRange? =
        when (band) {
            RoadBand.GOOD -> ScoreRange(minInclusive = RoadBand.GOOD_MIN_SCORE, maxExclusive = null)
            RoadBand.FAIR -> ScoreRange(minInclusive = RoadBand.FAIR_MIN_SCORE, maxExclusive = RoadBand.GOOD_MIN_SCORE)
            RoadBand.POOR -> ScoreRange(minInclusive = null, maxExclusive = RoadBand.FAIR_MIN_SCORE)
            RoadBand.NO_DATA -> null
        }

    /** Bands drawn from the tiles, bottom to top (poor roads draw last). */
    val tileBands: List<RoadBand> = listOf(RoadBand.GOOD, RoadBand.FAIR, RoadBand.POOR)
}

/** Scores from [minInclusive] up to but excluding [maxExclusive]; a null bound is open. */
data class ScoreRange(
    val minInclusive: Int?,
    val maxExclusive: Int?,
) {
    fun contains(score: Int): Boolean = (minInclusive == null || score >= minInclusive) && (maxExclusive == null || score < maxExclusive)
}
