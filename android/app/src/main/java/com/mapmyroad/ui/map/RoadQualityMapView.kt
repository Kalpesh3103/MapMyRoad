package com.mapmyroad.ui.map

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.doOnLayout
import com.mapmyroad.map.CommunityViewport
import com.mapmyroad.map.GeoBounds
import com.mapmyroad.map.MapConfig
import com.mapmyroad.map.MapMode
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.geojson.FeatureCollection
import kotlin.math.roundToInt

/** Map plus its loaded style; both are only valid while the map view is in composition. */
private class LoadedMap(
    val map: MapLibreMap,
    val style: Style,
)

/**
 * Base map with the road quality layers, shared by the personal map (S6) and the trip summary route map (S4).
 *
 * Sources and layers are added once when the style has loaded. Changes to [trackFeatures] or [hazardFeatures] only
 * replace the GeoJSON data. The camera starts on the launch city and is fitted to [fitBounds] once, the first time
 * they are known. Taps on clusters zoom in; taps on hazards and trip lines are passed to [onHit].
 * The OpenStreetMap attribution is always visible (PRD PV-7).
 *
 * With a [communityTileUrlTemplate] the community layers (M5-5) are installed too, hidden until [mode] is
 * [MapMode.COMMUNITY]. Mode switches only toggle layer visibility. In community mode taps hit community hazards only,
 * and [onCommunityViewport] reports what is drawn each time the map becomes idle (all tiles loaded and rendered).
 */
@Composable
internal fun RoadQualityMapView(
    trackFeatures: FeatureCollection,
    hazardFeatures: FeatureCollection,
    fitBounds: GeoBounds?,
    mapDescription: String,
    onHit: (MapHit) -> Unit,
    modifier: Modifier = Modifier,
    onViewportChanged: (GeoBounds) -> Unit = {},
    mode: MapMode = MapMode.PERSONAL,
    communityTileUrlTemplate: String? = null,
    onCommunityViewport: (CommunityViewport) -> Unit = {},
) {
    val density = LocalDensity.current
    val fitPaddingPx = with(density) { FIT_PADDING.toPx() }.roundToInt()
    val mapView = rememberMapViewWithLifecycle()
    val layers = remember(density.density) { RoadQualityLayers(density.density) }
    val communityLayers = remember(density.density) { CommunityLayers(density.density) }
    var loadedMap by remember { mutableStateOf<LoadedMap?>(null) }
    var cameraFitted by remember { mutableStateOf(false) }
    val currentOnHit by rememberUpdatedState(onHit)
    val currentOnViewportChanged by rememberUpdatedState(onViewportChanged)
    val currentMode by rememberUpdatedState(mode)
    val currentOnCommunityViewport by rememberUpdatedState(onCommunityViewport)

    DisposableEffect(mapView, layers, communityLayers, communityTileUrlTemplate) {
        var active = true
        var readyMap: MapLibreMap? = null
        val clickListener =
            MapLibreMap.OnMapClickListener { tap ->
                val map = readyMap ?: return@OnMapClickListener false
                if (!active) return@OnMapClickListener false
                val hit =
                    if (currentMode == MapMode.COMMUNITY) {
                        communityLayers.hitTest(map, tap)?.let { MapHit.CommunityHazard(it) } ?: MapHit.None
                    } else {
                        layers.hitTest(map, tap)
                    }
                when (hit) {
                    MapHit.None -> false
                    is MapHit.Cluster -> {
                        map.animateCamera(
                            CameraUpdateFactory.newLatLngZoom(hit.target, map.cameraPosition.zoom + CLUSTER_ZOOM_STEP),
                        )
                        true
                    }
                    else -> {
                        currentOnHit(hit)
                        true
                    }
                }
            }
        val cameraIdleListener =
            MapLibreMap.OnCameraIdleListener {
                val map = readyMap
                if (active && map != null) currentOnViewportChanged(map.visibleBounds())
            }
        val idleListener =
            MapView.OnDidBecomeIdleListener {
                val map = readyMap
                if (active && map != null && communityTileUrlTemplate != null && currentMode == MapMode.COMMUNITY) {
                    currentOnCommunityViewport(communityLayers.viewport(map, mapView.width, mapView.height))
                }
            }
        mapView.getMapAsync { map ->
            if (!active) return@getMapAsync
            map.cameraPosition =
                CameraPosition
                    .Builder()
                    .target(LatLng(MapConfig.INITIAL_LAT, MapConfig.INITIAL_LON))
                    .zoom(MapConfig.INITIAL_ZOOM)
                    .build()
            map.setStyle(Style.Builder().fromUri(MapConfig.BASE_STYLE_URL)) { style ->
                if (!active) return@setStyle
                layers.install(style)
                communityTileUrlTemplate?.let { communityLayers.install(style, it) }
                readyMap = map
                map.addOnMapClickListener(clickListener)
                map.addOnCameraIdleListener(cameraIdleListener)
                mapView.addOnDidBecomeIdleListener(idleListener)
                loadedMap = LoadedMap(map = map, style = style)
            }
        }
        onDispose {
            active = false
            // Listeners are removed so a re-run of this effect on the same map view never handles a tap twice.
            readyMap?.let { map ->
                map.removeOnMapClickListener(clickListener)
                map.removeOnCameraIdleListener(cameraIdleListener)
            }
            mapView.removeOnDidBecomeIdleListener(idleListener)
            readyMap = null
            loadedMap = null
        }
    }

    val loaded = loadedMap
    LaunchedEffect(loaded, trackFeatures) {
        loaded?.let { layers.setTracks(it.style, trackFeatures) }
    }
    LaunchedEffect(loaded, hazardFeatures) {
        loaded?.let { layers.setHazards(it.style, hazardFeatures) }
    }
    LaunchedEffect(loaded, mode) {
        loaded?.let {
            layers.setVisible(it.style, visible = mode == MapMode.PERSONAL)
            communityLayers.setVisible(it.style, visible = mode == MapMode.COMMUNITY)
        }
    }
    LaunchedEffect(loaded, fitBounds) {
        if (loaded == null || fitBounds == null || cameraFitted) return@LaunchedEffect
        cameraFitted = true
        // Fitting needs the view size, so wait for layout if it has not happened yet.
        mapView.doOnLayout {
            if (loadedMap === loaded) {
                loaded.map.moveCameraTo(fitBounds, fitPaddingPx)
                currentOnViewportChanged(loaded.map.visibleBounds())
            }
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

private val FIT_PADDING = 48.dp

/** Zoom levels added when a cluster is tapped. */
private const val CLUSTER_ZOOM_STEP = 2.0
