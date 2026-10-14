package com.mapmyroad.ui.map

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.map.GeoBounds

/**
 * Map (S6), personal map (PRD PM-1, PM-2, M2-11): the user's own trips colored by smoothness and their hazard
 * markers, with a collapsible legend and the always-visible OpenStreetMap attribution (PV-7). Tapping a trip line
 * opens its summary; tapping a hazard opens the hazard detail sheet (S7).
 *
 * TODO(M2-11): center-on-me button. This screen does not hold the location permission, so it is left out for now.
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onOpenTripSummary: (tripId: String) -> Unit = {},
) {
    val viewModel: PersonalMapViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    MapScreenContent(
        uiState = uiState,
        onOpenTripSummary = onOpenTripSummary,
        onHazardSelected = viewModel::onHazardSelected,
        onHazardSheetDismissed = viewModel::onHazardSheetDismissed,
        onViewportChanged = viewModel::onViewportChanged,
        modifier = modifier,
    )
}

@Composable
private fun MapScreenContent(
    uiState: PersonalMapUiState,
    onOpenTripSummary: (tripId: String) -> Unit,
    onHazardSelected: (hazardId: String) -> Unit,
    onHazardSheetDismissed: () -> Unit,
    onViewportChanged: (GeoBounds) -> Unit,
    modifier: Modifier = Modifier,
) {
    var legendExpanded by rememberSaveable { mutableStateOf(true) }
    val mapDescription = personalMapDescription(uiState)

    Box(modifier = modifier.fillMaxSize()) {
        RoadQualityMapView(
            trackFeatures = uiState.trackFeatures,
            hazardFeatures = uiState.hazardFeatures,
            fitBounds = uiState.dataBounds,
            mapDescription = mapDescription,
            onHit = { hit ->
                when (hit) {
                    is MapHit.Hazard -> onHazardSelected(hit.hazardId)
                    is MapHit.Trip -> onOpenTripSummary(hit.tripId)
                    is MapHit.Cluster, MapHit.None -> Unit
                }
            },
            onViewportChanged = onViewportChanged,
            modifier = Modifier.fillMaxSize(),
        )
        Column(
            modifier =
                Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MapModeToggle(modifier = Modifier.fillMaxWidth())
            if (uiState.isEmpty) {
                EmptyBanner(modifier = Modifier.fillMaxWidth())
            }
            MapLegend(expanded = legendExpanded, onToggle = { legendExpanded = !legendExpanded })
        }
    }

    uiState.selectedHazard?.let { hazard ->
        HazardDetailSheet(
            hazard = hazard,
            onDismiss = onHazardSheetDismissed,
            onViewTrip = { tripId ->
                onHazardSheetDismissed()
                onOpenTripSummary(tripId)
            },
        )
    }
}

/** "My trips | Community" (S6). Community is v2, so it is disabled with an explanation. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapModeToggle(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 2.dp,
    ) {
        Column(modifier = Modifier.padding(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                SegmentedButton(
                    selected = true,
                    onClick = {},
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) {
                    Text(text = stringResource(R.string.map_mode_my_trips))
                }
                SegmentedButton(
                    selected = false,
                    onClick = {},
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                    enabled = false,
                ) {
                    Text(text = stringResource(R.string.map_mode_community))
                }
            }
            Text(
                text = stringResource(R.string.map_mode_community_later),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End),
            )
        }
    }
}

@Composable
private fun EmptyBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = stringResource(R.string.map_empty_banner),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
    }
}

/** Map description for TalkBack with a text summary of the visible area (UI_UX_GUIDELINES.md section 11). */
@Composable
private fun personalMapDescription(uiState: PersonalMapUiState): String {
    val base = stringResource(R.string.map_content_description)
    if (uiState.isLoading) return base
    val summary = uiState.summary
    return stringResource(
        R.string.map_summary_format,
        base,
        pluralStringResource(R.plurals.map_summary_trips, summary.tripCount, summary.tripCount),
        pluralStringResource(R.plurals.map_summary_severe, summary.severeCount, summary.severeCount),
        pluralStringResource(R.plurals.map_summary_moderate, summary.moderateCount, summary.moderateCount),
        pluralStringResource(R.plurals.map_summary_minor, summary.minorCount, summary.minorCount),
    )
}
