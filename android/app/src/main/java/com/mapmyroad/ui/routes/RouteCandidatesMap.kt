package com.mapmyroad.ui.routes

import android.graphics.RectF
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.mapmyroad.map.GeoBounds
import com.mapmyroad.map.GeoPoint
import com.mapmyroad.map.MapConfig
import com.mapmyroad.ui.map.MapAttribution
import com.mapmyroad.ui.map.moveCameraTo
import com.mapmyroad.ui.map.rememberMapViewWithLifecycle
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import kotlin.math.roundToInt

private class LoadedRouteMap(
    val map: MapLibreMap,
    val style: Style,
)

/** Colors for the candidate lines, read from the theme in composition. */
private class RouteLineColors(
    val selected: Int,
    val selectedCasing: Int,
    val other: Int,
    val endpoint: Int,
)

/**
 * Map of up to three route candidates (S8, UI_UX_GUIDELINES.md section 12). Candidates differ by outline style as
 * well as color: the selected route is a thick solid line with a light casing, the others are thinner dashed
 * lines. The selected route always draws on top because it has its own source and layers above the others.
 * Tapping a line selects that candidate. The camera fits all candidates whenever the set of lines changes.
 * The OpenStreetMap attribution is always visible (PRD PV-7).
 */
@Composable
internal fun RouteCandidatesMap(
    lines: List<RouteLine>,
    selectedId: String?,
    origin: GeoPoint?,
    destination: GeoPoint,
    mapDescription: String,
    onCandidateTapped: (candidateId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val fitPaddingPx = with(density) { FIT_PADDING.toPx() }.roundToInt()
    val touchHalfPx = with(density) { (TOUCH_TARGET / 2).toPx() }
    val colors =
        RouteLineColors(
            selected = MaterialTheme.colorScheme.primary.toArgb(),
            selectedCasing = Color.White.toArgb(),
            other = MaterialTheme.colorScheme.outline.toArgb(),
            endpoint = MaterialTheme.colorScheme.primary.toArgb(),
        )
    val mapView = rememberMapViewWithLifecycle()
    var loadedMap by remember { mutableStateOf<LoadedRouteMap?>(null) }
    val currentOnTapped by rememberUpdatedState(onCandidateTapped)
    val currentColors by rememberUpdatedState(colors)

    DisposableEffect(mapView) {
        var active = true
        var readyMap: MapLibreMap? = null
        val clickListener =
            MapLibreMap.OnMapClickListener { tap ->
                val map = readyMap ?: return@OnMapClickListener false
                if (!active) return@OnMapClickListener false
                val screenPoint = map.projection.toScreenLocation(tap)
                val area =
                    RectF(
                        screenPoint.x - touchHalfPx,
                        screenPoint.y - touchHalfPx,
                        screenPoint.x + touchHalfPx,
                        screenPoint.y + touchHalfPx,
                    )
                val id =
                    map
                        .queryRenderedFeatures(area, OTHER_LAYER)
                        .firstNotNullOfOrNull { feature ->
                            if (feature.hasProperty(PROP_CANDIDATE_ID)) feature.getStringProperty(PROP_CANDIDATE_ID) else null
                        }
                if (id != null) {
                    currentOnTapped(id)
                    true
                } else {
                    false
                }
            }
        mapView.getMapAsync { map ->
            if (!active) return@getMapAsync
            map.cameraPosition =
                CameraPosition
                    .Builder()
                    .target(LatLng(destination.lat, destination.lon))
                    .zoom(MapConfig.INITIAL_ZOOM)
                    .build()
            map.setStyle(Style.Builder().fromUri(MapConfig.BASE_STYLE_URL)) { style ->
                if (!active) return@setStyle
                installRouteLayers(style, currentColors)
                readyMap = map
                map.addOnMapClickListener(clickListener)
                loadedMap = LoadedRouteMap(map = map, style = style)
            }
        }
        onDispose {
            active = false
            readyMap?.removeOnMapClickListener(clickListener)
            readyMap = null
            loadedMap = null
        }
    }

    val loaded = loadedMap
    LaunchedEffect(loaded, lines, selectedId) {
        loaded ?: return@LaunchedEffect
        if (!loaded.style.isFullyLoaded) return@LaunchedEffect
        val selectedLine = lines.firstOrNull { it.candidateId == selectedId }
        loaded.style
            .getSourceAs<GeoJsonSource>(OTHER_SOURCE)
            ?.setGeoJson(lineFeatures(lines.filter { it.candidateId != selectedId }))
        loaded.style
            .getSourceAs<GeoJsonSource>(SELECTED_SOURCE)
            ?.setGeoJson(lineFeatures(listOfNotNull(selectedLine)))
    }
    LaunchedEffect(loaded, origin, destination) {
        loaded ?: return@LaunchedEffect
        if (!loaded.style.isFullyLoaded) return@LaunchedEffect
        loaded.style.getSourceAs<GeoJsonSource>(ENDPOINT_SOURCE)?.setGeoJson(endpointFeatures(origin, destination))
    }
    // Fit only when the set of lines changes, not when the selection changes, so the map does not jump.
    LaunchedEffect(loaded, lines) {
        loaded ?: return@LaunchedEffect
        val bounds = boundsOf(lines, destination) ?: return@LaunchedEffect
        mapView.doOnLayout {
            if (loadedMap === loaded) loaded.map.moveCameraTo(bounds, fitPaddingPx)
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { mapView },
            modifier =
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = mapDescription },
        )
        MapAttribution(modifier = Modifier.align(Alignment.BottomEnd))
    }
}

private fun installRouteLayers(
    style: Style,
    colors: RouteLineColors,
) {
    val empty = FeatureCollection.fromFeatures(emptyList<Feature>())
    style.addSource(GeoJsonSource(OTHER_SOURCE, empty))
    style.addSource(GeoJsonSource(SELECTED_SOURCE, empty))
    style.addSource(GeoJsonSource(ENDPOINT_SOURCE, empty))

    val other =
        LineLayer(OTHER_LAYER, OTHER_SOURCE).apply {
            setProperties(
                PropertyFactory.lineColor(colors.other),
                PropertyFactory.lineWidth(OTHER_WIDTH),
                // Dashes are the second cue besides color (UI_UX_GUIDELINES.md section 12).
                PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            )
        }
    val casing =
        LineLayer(SELECTED_CASING_LAYER, SELECTED_SOURCE).apply {
            setProperties(
                PropertyFactory.lineColor(colors.selectedCasing),
                PropertyFactory.lineWidth(SELECTED_WIDTH + 2 * CASING_WIDTH),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            )
        }
    val selected =
        LineLayer(SELECTED_LAYER, SELECTED_SOURCE).apply {
            setProperties(
                PropertyFactory.lineColor(colors.selected),
                PropertyFactory.lineWidth(SELECTED_WIDTH),
                PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
                PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            )
        }
    val endpoints =
        CircleLayer(ENDPOINT_LAYER, ENDPOINT_SOURCE).apply {
            setProperties(
                PropertyFactory.circleRadius(ENDPOINT_RADIUS),
                PropertyFactory.circleColor(colors.endpoint),
                PropertyFactory.circleStrokeColor(colors.selectedCasing),
                PropertyFactory.circleStrokeWidth(CASING_WIDTH),
            )
        }
    // Routes go below the base map labels; later layers end up on top, so the selected route draws last.
    val firstLabelLayerId = style.layers.firstOrNull { it is SymbolLayer }?.id
    for (layer in listOf(other, casing, selected)) {
        if (firstLabelLayerId != null) style.addLayerBelow(layer, firstLabelLayerId) else style.addLayer(layer)
    }
    style.addLayer(endpoints)
}

private fun lineFeatures(lines: List<RouteLine>): FeatureCollection {
    val features =
        lines
            .filter { it.points.size >= 2 }
            .map { line ->
                Feature
                    .fromGeometry(LineString.fromLngLats(line.points.map { Point.fromLngLat(it.lon, it.lat) }))
                    .also { it.addStringProperty(PROP_CANDIDATE_ID, line.candidateId) }
            }
    return FeatureCollection.fromFeatures(features)
}

private fun endpointFeatures(
    origin: GeoPoint?,
    destination: GeoPoint,
): FeatureCollection {
    val points = listOfNotNull(origin, destination).map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }
    return FeatureCollection.fromFeatures(points)
}

/** Bounds of every candidate point plus the destination; null when there are no lines yet. */
private fun boundsOf(
    lines: List<RouteLine>,
    destination: GeoPoint,
): GeoBounds? {
    val points = lines.flatMap { it.points }
    if (points.isEmpty()) return null
    return GeoBounds.of(points + destination, lat = { it.lat }, lon = { it.lon })
}

private val FIT_PADDING = 48.dp
private val TOUCH_TARGET = 48.dp

private const val OTHER_SOURCE = "mmr-routes-other-source"
private const val SELECTED_SOURCE = "mmr-routes-selected-source"
private const val ENDPOINT_SOURCE = "mmr-routes-endpoint-source"
private const val OTHER_LAYER = "mmr-routes-other"
private const val SELECTED_CASING_LAYER = "mmr-routes-selected-casing"
private const val SELECTED_LAYER = "mmr-routes-selected"
private const val ENDPOINT_LAYER = "mmr-routes-endpoints"
private const val PROP_CANDIDATE_ID = "candidateId"

private const val OTHER_WIDTH = 4f
private const val SELECTED_WIDTH = 7f
private const val CASING_WIDTH = 2f
private const val ENDPOINT_RADIUS = 6f
