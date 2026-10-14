package com.mapmyroad.map

import com.mapmyroad.detector.TripScoring
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.TripPoint
import com.mapmyroad.domain.model.TripTrack
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Builds the GeoJSON for the road quality map layers. Runs off the main thread (the view models use the default
 * dispatcher). Feature properties are named in [MapLayerIds].
 */
object RoadQualityGeoJson {
    fun emptyCollection(): FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())

    /** Band of one track point from its window roughness. Points without roughness have no data. */
    fun bandOf(point: TripPoint): RoadBand = RoadBand.fromScore(point.roughness?.let { TripScoring.roughnessToScore(it) })

    /**
     * One LineString per contiguous band run per trip, simplified, with properties [MapLayerIds.PROP_BAND] and
     * [MapLayerIds.PROP_TRIP_ID]. Runs with fewer than two points are skipped.
     */
    fun trackFeatures(
        tracks: List<TripTrack>,
        bandOfPoint: (TripPoint) -> RoadBand = RoadQualityGeoJson::bandOf,
        minSpacingM: Double = TrackSimplifier.DEFAULT_MIN_SPACING_M,
    ): FeatureCollection {
        val features = ArrayList<Feature>()
        for (track in tracks) {
            for (run in TrackSegmenter.segment(track.points, bandOfPoint)) {
                val simplified = TrackSimplifier.simplify(run.points, minSpacingM)
                if (simplified.size < 2) continue
                val line = LineString.fromLngLats(simplified.map { Point.fromLngLat(it.lon, it.lat) })
                val feature = Feature.fromGeometry(line)
                feature.addStringProperty(MapLayerIds.PROP_BAND, run.band.key)
                feature.addStringProperty(MapLayerIds.PROP_TRIP_ID, track.tripId)
                features.add(feature)
            }
        }
        return FeatureCollection.fromFeatures(features)
    }

    /**
     * One Point per hazard with properties [MapLayerIds.PROP_HAZARD_ID], [MapLayerIds.PROP_TRIP_ID],
     * [MapLayerIds.PROP_SEVERITY] (level 1 to 3) and [MapLayerIds.PROP_ICON] (style image name).
     */
    fun hazardFeatures(hazards: List<RecordedHazard>): FeatureCollection {
        val features = ArrayList<Feature>(hazards.size)
        for (hazard in hazards) {
            val feature = Feature.fromGeometry(Point.fromLngLat(hazard.lon, hazard.lat))
            feature.addStringProperty(MapLayerIds.PROP_HAZARD_ID, hazard.id)
            feature.addStringProperty(MapLayerIds.PROP_TRIP_ID, hazard.tripId)
            feature.addNumberProperty(MapLayerIds.PROP_SEVERITY, hazard.severity.level)
            feature.addStringProperty(MapLayerIds.PROP_ICON, MapLayerIds.markerImage(hazard.severity))
            features.add(feature)
        }
        return FeatureCollection.fromFeatures(features)
    }
}
