package com.mapmyroad.map

import com.mapmyroad.detector.Severity

/** Source, layer, image and property names used by the road quality map layers. All prefixed to avoid clashes. */
object MapLayerIds {
    const val TRACK_SOURCE = "mmr-tracks"
    const val HAZARD_SOURCE = "mmr-hazards"

    const val TRACK_LAYER_NO_DATA = "mmr-track-no-data"
    const val TRACK_LAYER_GOOD = "mmr-track-good"
    const val TRACK_LAYER_FAIR = "mmr-track-fair"
    const val TRACK_LAYER_POOR = "mmr-track-poor"

    /** Clustered hazards: icon of the highest severity in the cluster plus the count. */
    const val HAZARD_CLUSTER_LAYER = "mmr-hazard-clusters"

    /** Single hazards: severity shape icons. */
    const val HAZARD_LAYER = "mmr-hazard-markers"

    /** Feature properties. */
    const val PROP_BAND = "band"
    const val PROP_TRIP_ID = "tripId"
    const val PROP_HAZARD_ID = "hazardId"
    const val PROP_SEVERITY = "severity"
    const val PROP_ICON = "icon"

    /** Cluster property holding the highest [PROP_SEVERITY] level in the cluster. */
    const val PROP_CLUSTER_MAX_SEVERITY = "maxSeverity"

    /** Properties MapLibre adds to cluster features. */
    const val PROP_POINT_COUNT = "point_count"
    const val PROP_POINT_COUNT_ABBREVIATED = "point_count_abbreviated"

    val trackLayerIds: List<String> =
        listOf(TRACK_LAYER_NO_DATA, TRACK_LAYER_GOOD, TRACK_LAYER_FAIR, TRACK_LAYER_POOR)

    fun trackLayerId(band: RoadBand): String =
        when (band) {
            RoadBand.GOOD -> TRACK_LAYER_GOOD
            RoadBand.FAIR -> TRACK_LAYER_FAIR
            RoadBand.POOR -> TRACK_LAYER_POOR
            RoadBand.NO_DATA -> TRACK_LAYER_NO_DATA
        }

    /** Style image name of a single hazard marker. */
    fun markerImage(severity: Severity): String = "mmr-marker-${severity.name.lowercase()}"

    /** Style image name of a cluster marker whose highest severity is [severity]. */
    fun clusterImage(severity: Severity): String = "mmr-cluster-${severity.name.lowercase()}"
}
