package com.mapmyroad.ui.map

import android.graphics.RectF
import androidx.compose.ui.graphics.toArgb
import com.mapmyroad.detector.Severity
import com.mapmyroad.map.GeoBounds
import com.mapmyroad.map.MapLayerIds
import com.mapmyroad.map.RoadBand
import com.mapmyroad.map.RoadQualityGeoJson
import com.mapmyroad.map.TileHazard
import com.mapmyroad.ui.theme.RoadQualityColors
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/** Result of a tap on the road quality map. */
internal sealed interface MapHit {
    data object None : MapHit

    data class Hazard(
        val hazardId: String,
    ) : MapHit

    /** A hazard cluster; [target] is its position, used to zoom in. */
    class Cluster(
        val target: LatLng,
    ) : MapHit

    data class Trip(
        val tripId: String,
    ) : MapHit

    /** A hazard of the community map (M5-5), with what its tile feature says about it. */
    data class CommunityHazard(
        val hazard: TileHazard,
    ) : MapHit
}

/**
 * Sources and layers for trips colored by smoothness and hazard markers (UI_UX_GUIDELINES.md sections 4.2, 4.3,
 * 12). [install] adds everything once after the style loads; afterwards only the GeoJSON data is replaced with
 * [setTracks] and [setHazards], so layers are never recreated on state changes (CODING_STANDARDS.md section 9).
 */
internal class RoadQualityLayers(
    private val density: Float,
) {
    fun install(style: Style) {
        addImages(style)
        style.addSource(GeoJsonSource(MapLayerIds.TRACK_SOURCE, RoadQualityGeoJson.emptyCollection()))
        style.addSource(
            GeoJsonSource(
                MapLayerIds.HAZARD_SOURCE,
                RoadQualityGeoJson.emptyCollection(),
                GeoJsonOptions()
                    .withCluster(true)
                    .withClusterMaxZoom(CLUSTER_MAX_ZOOM)
                    .withClusterRadius(CLUSTER_RADIUS)
                    .withClusterProperty(
                        MapLayerIds.PROP_CLUSTER_MAX_SEVERITY,
                        Expression.literal("max"),
                        Expression.toNumber(Expression.get(MapLayerIds.PROP_SEVERITY)),
                    ),
            ),
        )

        // Quality lines go above roads but below the base map labels (UI_UX_GUIDELINES.md section 12). Each layer
        // is inserted right below the first label layer, so later layers end up on top: poor roads draw last.
        val firstLabelLayerId = style.layers.firstOrNull { it is SymbolLayer }?.id
        val trackLayers =
            listOf(
                trackLayer(RoadBand.NO_DATA),
                trackLayer(RoadBand.GOOD),
                trackLayer(RoadBand.FAIR),
                trackLayer(RoadBand.POOR),
            )
        for (layer in trackLayers) {
            if (firstLabelLayerId != null) {
                style.addLayerBelow(layer, firstLabelLayerId)
            } else {
                style.addLayer(layer)
            }
        }
        // Markers on top of everything.
        style.addLayer(clusterLayer())
        style.addLayer(hazardLayer())
    }

    fun setTracks(
        style: Style,
        features: FeatureCollection,
    ) {
        if (!style.isFullyLoaded) return
        style.getSourceAs<GeoJsonSource>(MapLayerIds.TRACK_SOURCE)?.setGeoJson(features)
    }

    /** Shows or hides the personal layers (own trips and hazards) when the map mode changes. */
    fun setVisible(
        style: Style,
        visible: Boolean,
    ) {
        if (!style.isFullyLoaded) return
        style.setLayersVisible(MapLayerIds.personalLayerIds, visible)
    }

    fun setHazards(
        style: Style,
        features: FeatureCollection,
    ) {
        if (!style.isFullyLoaded) return
        style.getSourceAs<GeoJsonSource>(MapLayerIds.HAZARD_SOURCE)?.setGeoJson(features)
    }

    /**
     * Finds what is under a tap, with a 48 dp target (UI_UX_GUIDELINES.md section 4.3). Single hazards win over
     * clusters, clusters over trip lines. The most severe hazard wins when several are under the finger.
     */
    fun hitTest(
        map: MapLibreMap,
        tap: LatLng,
    ): MapHit {
        val screenPoint = map.projection.toScreenLocation(tap)
        val half = TOUCH_TARGET_DP / 2f * density
        val area = RectF(screenPoint.x - half, screenPoint.y - half, screenPoint.x + half, screenPoint.y + half)

        val hazard =
            map
                .queryRenderedFeatures(area, MapLayerIds.HAZARD_LAYER)
                .filter { it.stringOrNull(MapLayerIds.PROP_HAZARD_ID) != null }
                .maxByOrNull { it.numberOrNull(MapLayerIds.PROP_SEVERITY)?.toInt() ?: 0 }
        hazard?.stringOrNull(MapLayerIds.PROP_HAZARD_ID)?.let { return MapHit.Hazard(it) }

        val cluster = map.queryRenderedFeatures(area, MapLayerIds.HAZARD_CLUSTER_LAYER).firstOrNull()
        (cluster?.geometry() as? Point)?.let { return MapHit.Cluster(LatLng(it.latitude(), it.longitude())) }

        val trip =
            map
                .queryRenderedFeatures(area, *MapLayerIds.trackLayerIds.toTypedArray())
                .firstNotNullOfOrNull { it.stringOrNull(MapLayerIds.PROP_TRIP_ID) }
        return if (trip != null) MapHit.Trip(trip) else MapHit.None
    }

    private fun addImages(style: Style) {
        for (severity in Severity.entries) {
            style.addImage(MapLayerIds.markerImage(severity), MarkerBitmaps.create(severity, density))
            style.addImage(
                MapLayerIds.clusterImage(severity),
                MarkerBitmaps.create(severity, density, sizeDp = MarkerBitmaps.CLUSTER_SIZE_DP, withGlyph = false),
            )
        }
    }

    private fun trackLayer(band: RoadBand): LineLayer {
        val layer = LineLayer(MapLayerIds.trackLayerId(band), MapLayerIds.TRACK_SOURCE)
        styleBandLine(layer, band)
        layer.setFilter(Expression.eq(Expression.get(MapLayerIds.PROP_BAND), Expression.literal(band.key)))
        return layer
    }

    /** Clusters show the shape of the highest severity inside them plus the count (section 4.3). */
    private fun clusterLayer(): SymbolLayer {
        val layer = SymbolLayer(MapLayerIds.HAZARD_CLUSTER_LAYER, MapLayerIds.HAZARD_SOURCE)
        layer.setProperties(
            PropertyFactory.iconImage(
                Expression.match(
                    Expression.toNumber(Expression.get(MapLayerIds.PROP_CLUSTER_MAX_SEVERITY)),
                    Expression.literal(MapLayerIds.clusterImage(Severity.MINOR)),
                    Expression.stop(Severity.MODERATE.level, MapLayerIds.clusterImage(Severity.MODERATE)),
                    Expression.stop(Severity.SEVERE.level, MapLayerIds.clusterImage(Severity.SEVERE)),
                ),
            ),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.textField(Expression.get(MapLayerIds.PROP_POINT_COUNT_ABBREVIATED)),
            PropertyFactory.textFont(arrayOf(CLUSTER_FONT)),
            PropertyFactory.textSize(CLUSTER_TEXT_SIZE_SP),
            PropertyFactory.textColor(android.graphics.Color.WHITE),
            PropertyFactory.textHaloColor(android.graphics.Color.BLACK),
            PropertyFactory.textHaloWidth(1f),
            PropertyFactory.textAllowOverlap(true),
            PropertyFactory.textIgnorePlacement(true),
        )
        layer.setFilter(Expression.has(MapLayerIds.PROP_POINT_COUNT))
        return layer
    }

    private fun hazardLayer(): SymbolLayer {
        val layer = SymbolLayer(MapLayerIds.HAZARD_LAYER, MapLayerIds.HAZARD_SOURCE)
        layer.setProperties(
            PropertyFactory.iconImage(Expression.get(MapLayerIds.PROP_ICON)),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
        )
        layer.setFilter(Expression.not(Expression.has(MapLayerIds.PROP_POINT_COUNT)))
        return layer
    }

    private companion object {
        /** Clusters up to this zoom; single markers above it (S6: markers from zoom 13). */
        const val CLUSTER_MAX_ZOOM = 13
        const val CLUSTER_RADIUS = 50

        const val TOUCH_TARGET_DP = 48f

        /** A font served by the OpenFreeMap styles. Text is not drawn if the style has no such glyphs. */
        const val CLUSTER_FONT = "Noto Sans Bold"
        const val CLUSTER_TEXT_SIZE_SP = 12f
    }
}

/**
 * Line style of a road band, shared by the personal tracks and the community roads so both modes look the same
 * (UI_UX_GUIDELINES.md sections 4.2 and 12): color plus a second cue, solid, dashed, or dotted and thicker.
 */
internal fun styleBandLine(
    layer: LineLayer,
    band: RoadBand,
) {
    // Line width scales with zoom; poor roads are thicker, no-data roads thinner (section 12).
    val widthFactor =
        when (band) {
            RoadBand.POOR -> POOR_WIDTH_FACTOR
            RoadBand.NO_DATA -> NO_DATA_WIDTH_FACTOR
            RoadBand.GOOD, RoadBand.FAIR -> 1f
        }
    val color =
        when (band) {
            RoadBand.GOOD -> RoadQualityColors.Good
            RoadBand.FAIR -> RoadQualityColors.Fair
            RoadBand.POOR -> RoadQualityColors.Poor
            RoadBand.NO_DATA -> RoadQualityColors.NoData
        }
    layer.setProperties(
        PropertyFactory.lineColor(color.toArgb()),
        PropertyFactory.lineWidth(
            Expression.interpolate(
                Expression.linear(),
                Expression.zoom(),
                Expression.stop(LOW_ZOOM, LOW_ZOOM_WIDTH * widthFactor),
                Expression.stop(MID_ZOOM, MID_ZOOM_WIDTH * widthFactor),
                Expression.stop(HIGH_ZOOM, HIGH_ZOOM_WIDTH * widthFactor),
            ),
        ),
        PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
        PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
    )
    // Second cue besides color (section 4.2). The dash array is in line widths and cannot be data driven,
    // which is why there is one layer per band.
    when (band) {
        RoadBand.FAIR -> layer.setProperties(PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)))
        // A zero-length dash with a round cap draws a dot.
        RoadBand.POOR -> layer.setProperties(PropertyFactory.lineDasharray(arrayOf(0f, 1.8f)))
        RoadBand.GOOD, RoadBand.NO_DATA -> Unit
    }
}

/** Shows or hides layers without removing them, so sources and tiles stay loaded when switching map modes. */
internal fun Style.setLayersVisible(
    layerIds: List<String>,
    visible: Boolean,
) {
    val visibility = PropertyFactory.visibility(if (visible) Property.VISIBLE else Property.NONE)
    for (id in layerIds) {
        getLayer(id)?.setProperties(visibility)
    }
}

private const val LOW_ZOOM = 10f
private const val MID_ZOOM = 15f
private const val HIGH_ZOOM = 19f
private const val LOW_ZOOM_WIDTH = 2f
private const val MID_ZOOM_WIDTH = 5f
private const val HIGH_ZOOM_WIDTH = 10f
private const val POOR_WIDTH_FACTOR = 1.4f
private const val NO_DATA_WIDTH_FACTOR = 0.6f

/** Single point zoom when a camera cannot be fitted to bounds (UI_UX: street level). */
internal const val SINGLE_POINT_ZOOM = 15.0

/** Fitting tiny bounds would zoom in too far. */
internal const val MAX_FIT_ZOOM = 17.0

/** Moves the camera to show [bounds]. Must be called once the map view has been laid out. */
internal fun MapLibreMap.moveCameraTo(
    bounds: GeoBounds,
    paddingPx: Int,
) {
    if (bounds.isPoint) {
        moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(bounds.centerLat, bounds.centerLon), SINGLE_POINT_ZOOM))
        return
    }
    val latLngBounds =
        LatLngBounds
            .Builder()
            .include(LatLng(bounds.south, bounds.west))
            .include(LatLng(bounds.north, bounds.east))
            .build()
    moveCamera(CameraUpdateFactory.newLatLngBounds(latLngBounds, paddingPx))
    if (cameraPosition.zoom > MAX_FIT_ZOOM) {
        moveCamera(CameraUpdateFactory.zoomTo(MAX_FIT_ZOOM))
    }
}

/** The visible area of the map. */
internal fun MapLibreMap.visibleBounds(): GeoBounds {
    val visible = projection.visibleRegion.latLngBounds
    return GeoBounds(
        south = visible.latitudeSouth,
        west = visible.longitudeWest,
        north = visible.latitudeNorth,
        east = visible.longitudeEast,
    )
}

// hasProperty + getProperty are the long-standing GeoJSON Feature API. A JSON null value is treated as absent,
// because getStringProperty would throw on it.
internal fun Feature.hasValue(key: String): Boolean = hasProperty(key) && getProperty(key)?.isJsonNull == false

internal fun Feature.stringOrNull(key: String): String? = if (hasValue(key)) getStringProperty(key) else null

internal fun Feature.numberOrNull(key: String): Number? = if (hasValue(key)) getNumberProperty(key) else null
