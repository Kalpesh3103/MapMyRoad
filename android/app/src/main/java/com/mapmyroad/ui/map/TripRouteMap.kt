package com.mapmyroad.ui.map

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R

/**
 * Route map of one trip for the trip summary (S4): the route colored by road band, the trip's hazard markers and
 * the always-visible OpenStreetMap attribution (PV-7). The camera fits the trip once its data has loaded.
 * Gestures stay enabled; taps on clusters zoom in, other taps are ignored.
 */
@Composable
fun TripRouteMap(
    tripId: String,
    modifier: Modifier = Modifier,
) {
    val viewModel: TripRouteMapViewModel = hiltViewModel(key = "trip-route-map-$tripId")
    LaunchedEffect(viewModel, tripId) { viewModel.setTripId(tripId) }
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val base = stringResource(R.string.map_route_content_description)
    val mapDescription =
        if (uiState.isLoading) {
            base
        } else {
            stringResource(
                R.string.map_route_summary_format,
                base,
                pluralStringResource(R.plurals.map_summary_severe, uiState.severeCount, uiState.severeCount),
                pluralStringResource(R.plurals.map_summary_moderate, uiState.moderateCount, uiState.moderateCount),
                pluralStringResource(R.plurals.map_summary_minor, uiState.minorCount, uiState.minorCount),
            )
        }

    RoadQualityMapView(
        trackFeatures = uiState.trackFeatures,
        hazardFeatures = uiState.hazardFeatures,
        fitBounds = uiState.bounds,
        mapDescription = mapDescription,
        onHit = {},
        modifier = modifier,
    )
}
