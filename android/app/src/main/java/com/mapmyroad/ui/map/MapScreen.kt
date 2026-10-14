package com.mapmyroad.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.mapmyroad.R
import com.mapmyroad.map.MapConfig
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style

/**
 * Map (S6), M0-8: the base map with OpenStreetMap attribution. Personal trip and hazard layers arrive in M2-11.
 */
@Composable
fun MapScreen(modifier: Modifier = Modifier) {
    val mapView = rememberMapViewWithLifecycle()
    val mapDescription = stringResource(R.string.map_content_description)

    Box(modifier = modifier.fillMaxSize()) {
        AndroidView(
            factory = {
                mapView.apply {
                    getMapAsync { map ->
                        map.setStyle(Style.Builder().fromUri(MapConfig.BASE_STYLE_URL))
                        map.cameraPosition =
                            CameraPosition
                                .Builder()
                                .target(LatLng(MapConfig.INITIAL_LAT, MapConfig.INITIAL_LON))
                                .zoom(MapConfig.INITIAL_ZOOM)
                                .build()
                    }
                }
            },
            modifier =
                Modifier
                    .fillMaxSize()
                    .semantics { contentDescription = mapDescription },
        )
        MapAttribution(modifier = Modifier.align(Alignment.BottomEnd))
    }
}

/**
 * Always-visible OpenStreetMap attribution (PRD PV-7, UI_UX_GUIDELINES.md section 12). MapLibre's own
 * attribution button stays enabled as well. Tapping opens the OSM copyright page.
 */
@Composable
private fun MapAttribution(modifier: Modifier = Modifier) {
    val uriHandler = LocalUriHandler.current
    val actionLabel = stringResource(R.string.map_attribution_action)
    Surface(
        modifier =
            modifier
                .padding(8.dp)
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = actionLabel, role = Role.Button) {
                    uriHandler.openUri(MapConfig.OSM_COPYRIGHT_URL)
                },
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.85f),
        shape = MaterialTheme.shapes.small,
    ) {
        Box(modifier = Modifier.heightIn(min = 48.dp), contentAlignment = Alignment.Center) {
            Text(
                text = stringResource(R.string.map_attribution),
                style = MaterialTheme.typography.labelLarge,
                modifier =
                    Modifier
                        .padding(horizontal = 12.dp)
                        .wrapContentHeight(),
            )
        }
    }
}

/**
 * Creates a [MapView] and forwards the lifecycle to it, as MapLibre requires. The map leaves composition when
 * the user switches tabs while the activity stays resumed, so disposal replays pause and stop before destroy.
 */
@Composable
private fun rememberMapViewWithLifecycle(): MapView {
    val context = LocalContext.current
    val mapView = remember { MapView(context).apply { onCreate(null) } }
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    DisposableEffect(lifecycle, mapView) {
        // Adding the observer replays ON_START and ON_RESUME up to the current state.
        val observer =
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> mapView.onStart()
                    Lifecycle.Event.ON_RESUME -> mapView.onResume()
                    Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                    Lifecycle.Event.ON_STOP -> mapView.onStop()
                    // ON_DESTROY is handled in onDispose so that onDestroy() runs exactly once.
                    else -> Unit
                }
            }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            val state = lifecycle.currentState
            if (state.isAtLeast(Lifecycle.State.RESUMED)) {
                mapView.onPause()
            }
            if (state.isAtLeast(Lifecycle.State.STARTED)) {
                mapView.onStop()
            }
            mapView.onDestroy()
        }
    }
    return mapView
}
