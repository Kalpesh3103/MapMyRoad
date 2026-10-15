package com.mapmyroad.ui.map

import android.graphics.RectF
import com.mapmyroad.detector.Severity
import com.mapmyroad.map.CommunityTiles
import com.mapmyroad.map.CommunityViewport
import com.mapmyroad.map.ConfidenceLevel
import com.mapmyroad.map.MapLayerIds
import com.mapmyroad.map.RoadBand
import com.mapmyroad.map.ScoreRange
import com.mapmyroad.map.TileHazard
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.TileSet
import org.maplibre.android.style.sources.VectorSource
import org.maplibre.geojson.Feature

/**
 * The shared community map (M5-5, PRD CM-2, CM-3): road quality lines and hazard markers from the backend vector
 * tiles (API_SPEC.md 5.4). Uses the same line styles and marker images as the personal map, so the legend applies
 * to both modes and color is never the only cue (UI_UX_GUIDELINES.md sections 4.2, 4.3).
 *
 * [install] adds the source and layers once, hidden. Mode switches only change layer visibility, so tiles are
 * never reloaded. MapLibre requests tiles only for sources with visible layers, so nothing is fetched until the
 * user opens the Community mode. Must be installed after [RoadQualityLayers], which adds the marker images.
 */
internal class CommunityLayers(
    private val density: Float,
) {
    fun install(
        style: Style,
        tileUrlTemplate: String,
    ) {
        if (style.getSource(MapLayerIds.COMMUNITY_SOURCE) != null) return
        // Built from the URL template instead of the TileJSON URL so the source does not depend on the base URL
        // the server advertises.
        val tileSet = TileSet(CommunityTiles.TILEJSON_VERSION, tileUrlTemplate)
        tileSet.setMinZoom(CommunityTiles.SOURCE_MIN_ZOOM)
        tileSet.setMaxZoom(CommunityTiles.SOURCE_MAX_ZOOM)
        style.addSource(VectorSource(MapLayerIds.COMMUNITY_SOURCE, tileSet))

        // Same placement as the personal tracks: above roads, below base map labels, poor roads drawn last.
        val firstLabelLayerId = style.layers.firstOrNull { it is SymbolLayer }?.id
        for (band in CommunityTiles.tileBands) {
            val layer = roadLayer(band) ?: continue
            if (firstLabelLayerId != null) {
                style.addLayerBelow(layer, firstLabelLayerId)
            } else {
                style.addLayer(layer)
            }
        }
        // Markers on top of everything.
        style.addLayer(hazardLayer())
    }

    fun setVisible(
        style: Style,
        visible: Boolean,
    ) {
        if (!style.isFullyLoaded) return
        style.setLayersVisible(MapLayerIds.communityLayerIds, visible)
    }

    /**
     * The community hazard under a tap, with a 48 dp target (UI_UX_GUIDELINES.md section 4.3). The most severe
     * one wins when several are under the finger. Null when there is none.
     */
    fun hitTest(
        map: MapLibreMap,
        tap: LatLng,
    ): TileHazard? {
        val screenPoint = map.projection.toScreenLocation(tap)
        val half = TOUCH_TARGET_DP / 2f * density
        val area = RectF(screenPoint.x - half, screenPoint.y - half, screenPoint.x + half, screenPoint.y + half)
        return map
            .queryRenderedFeatures(area, MapLayerIds.COMMUNITY_HAZARD_LAYER)
            .mapNotNull { it.toTileHazard() }
            .maxByOrNull { it.severity.level }
    }

    /** What the community layers draw in the whole visible map ([widthPx] by [heightPx]), for the banner. */
    fun viewport(
        map: MapLibreMap,
        widthPx: Int,
        heightPx: Int,
    ): CommunityViewport {
        val area = RectF(0f, 0f, widthPx.toFloat(), heightPx.toFloat())
        val roadCount = map.queryRenderedFeatures(area, *MapLayerIds.communityRoadLayerIds.toTypedArray()).size
        // A hazard near a tile edge can be returned once per tile, so count distinct IDs.
        val hazards =
            map
                .queryRenderedFeatures(area, MapLayerIds.COMMUNITY_HAZARD_LAYER)
                .mapNotNull { it.toTileHazard() }
                .distinctBy { it.id }
        return CommunityViewport(
            zoom = map.cameraPosition.zoom,
            roadFeatureCount = roadCount,
            minorCount = hazards.count { it.severity == Severity.MINOR },
            moderateCount = hazards.count { it.severity == Severity.MODERATE },
            severeCount = hazards.count { it.severity == Severity.SEVERE },
        )
    }

    private fun roadLayer(band: RoadBand): LineLayer? {
        val id = MapLayerIds.communityRoadLayerId(band) ?: return null
        val range = CommunityTiles.scoreRange(band) ?: return null
        val layer = LineLayer(id, MapLayerIds.COMMUNITY_SOURCE)
        layer.setSourceLayer(CommunityTiles.ROADS_SOURCE_LAYER)
        styleBandLine(layer, band)
        layer.setFilter(scoreFilter(range))
        layer.setMinZoom(CommunityTiles.ROADS_MIN_ZOOM)
        layer.setProperties(PropertyFactory.visibility(Property.NONE))
        return layer
    }

    private fun scoreFilter(range: ScoreRange): Expression {
        val score = Expression.toNumber(Expression.get(CommunityTiles.PROP_SCORE))
        val conditions = mutableListOf(Expression.has(CommunityTiles.PROP_SCORE))
        range.minInclusive?.let { conditions.add(Expression.gte(score, Expression.literal(it))) }
        range.maxExclusive?.let { conditions.add(Expression.lt(score, Expression.literal(it))) }
        return Expression.all(*conditions.toTypedArray())
    }

    /** Severity shape icons chosen by `sev`; confidence as opacity, low confidence lighter (section 4.3). */
    private fun hazardLayer(): SymbolLayer {
        val layer = SymbolLayer(MapLayerIds.COMMUNITY_HAZARD_LAYER, MapLayerIds.COMMUNITY_SOURCE)
        layer.setSourceLayer(CommunityTiles.HAZARDS_SOURCE_LAYER)
        layer.setProperties(
            PropertyFactory.iconImage(
                Expression.match(
                    Expression.toNumber(Expression.get(CommunityTiles.PROP_SEVERITY)),
                    Expression.literal(MapLayerIds.markerImage(Severity.MINOR)),
                    Expression.stop(Severity.MODERATE.level, MapLayerIds.markerImage(Severity.MODERATE)),
                    Expression.stop(Severity.SEVERE.level, MapLayerIds.markerImage(Severity.SEVERE)),
                ),
            ),
            PropertyFactory.iconOpacity(
                Expression.step(
                    Expression.toNumber(Expression.get(CommunityTiles.PROP_CONFIDENCE)),
                    Expression.literal(ConfidenceLevel.LOW_OPACITY),
                    Expression.stop(ConfidenceLevel.MEDIUM_MIN, ConfidenceLevel.MEDIUM_OPACITY),
                    Expression.stop(ConfidenceLevel.HIGH_MIN, ConfidenceLevel.HIGH_OPACITY),
                ),
            ),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.visibility(Property.NONE),
        )
        layer.setFilter(Expression.has(CommunityTiles.PROP_ID))
        layer.setMinZoom(CommunityTiles.HAZARDS_MIN_ZOOM)
        return layer
    }

    private fun Feature.toTileHazard(): TileHazard? =
        TileHazard.fromProperties(
            id = stringOrNull(CommunityTiles.PROP_ID),
            severity = numberOrNull(CommunityTiles.PROP_SEVERITY),
            confidence = numberOrNull(CommunityTiles.PROP_CONFIDENCE),
            status = stringOrNull(CommunityTiles.PROP_STATUS),
            seenDays = numberOrNull(CommunityTiles.PROP_SEEN_DAYS),
        )

    private companion object {
        const val TOUCH_TARGET_DP = 48f
    }
}
