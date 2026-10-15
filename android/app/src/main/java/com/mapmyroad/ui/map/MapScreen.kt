package com.mapmyroad.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.map.CommunityCoverage
import com.mapmyroad.map.CommunityViewport
import com.mapmyroad.map.GeoBounds
import com.mapmyroad.map.MapMode
import com.mapmyroad.map.TileHazard

/**
 * Map (S6). "My trips" is the personal map (PRD PM-1, PM-2, M2-11): the user's own trips colored by smoothness and
 * their hazard markers; tapping a trip line opens its summary, tapping a hazard opens the hazard detail sheet (S7).
 * "Community" is the shared map (M5-5, PRD CM-2, CM-3) from the backend tiles; tapping a hazard opens the community
 * sheet. Both modes share the collapsible legend and the always-visible OpenStreetMap attribution (PV-7).
 * The search field at the top ("Where to?", S8) calls [onOpenSearch].
 *
 * TODO(M2-11): center-on-me button. This screen does not hold the location permission, so it is left out for now.
 */
@Composable
fun MapScreen(
    modifier: Modifier = Modifier,
    onOpenTripSummary: (tripId: String) -> Unit = {},
    onOpenSearch: () -> Unit = {},
) {
    val viewModel: PersonalMapViewModel = hiltViewModel()
    val communityViewModel: CommunityMapViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val communityState by communityViewModel.uiState.collectAsStateWithLifecycle()
    MapScreenContent(
        uiState = uiState,
        communityState = communityState,
        communityTileUrlTemplate = communityViewModel.tileUrlTemplate,
        onOpenTripSummary = onOpenTripSummary,
        onOpenSearch = onOpenSearch,
        onModeSelected = communityViewModel::onModeSelected,
        onHazardSelected = viewModel::onHazardSelected,
        onHazardSheetDismissed = viewModel::onHazardSheetDismissed,
        onCommunityHazardSelected = communityViewModel::onCommunityHazardSelected,
        onCommunityHazardSheetDismissed = communityViewModel::onCommunityHazardSheetDismissed,
        onViewportChanged = viewModel::onViewportChanged,
        onCommunityViewport = communityViewModel::onCommunityViewport,
        modifier = modifier,
    )
}

@Composable
private fun MapScreenContent(
    uiState: PersonalMapUiState,
    communityState: CommunityMapUiState,
    communityTileUrlTemplate: String,
    onOpenTripSummary: (tripId: String) -> Unit,
    onOpenSearch: () -> Unit,
    onModeSelected: (MapMode) -> Unit,
    onHazardSelected: (hazardId: String) -> Unit,
    onHazardSheetDismissed: () -> Unit,
    onCommunityHazardSelected: (TileHazard) -> Unit,
    onCommunityHazardSheetDismissed: () -> Unit,
    onViewportChanged: (GeoBounds) -> Unit,
    onCommunityViewport: (CommunityViewport) -> Unit,
    modifier: Modifier = Modifier,
) {
    var legendExpanded by rememberSaveable { mutableStateOf(true) }
    val mode = communityState.mode
    val mapDescription =
        when (mode) {
            MapMode.PERSONAL -> personalMapDescription(uiState)
            MapMode.COMMUNITY -> communityMapDescription(communityState.viewport)
        }

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
                    is MapHit.CommunityHazard -> onCommunityHazardSelected(hit.hazard)
                    is MapHit.Cluster, MapHit.None -> Unit
                }
            },
            onViewportChanged = onViewportChanged,
            mode = mode,
            communityTileUrlTemplate = communityTileUrlTemplate,
            onCommunityViewport = onCommunityViewport,
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
            SearchField(onClick = onOpenSearch, modifier = Modifier.fillMaxWidth())
            MapModeToggle(mode = mode, onModeSelected = onModeSelected, modifier = Modifier.fillMaxWidth())
            when (mode) {
                MapMode.PERSONAL ->
                    if (uiState.isEmpty) {
                        InfoBanner(text = stringResource(R.string.map_empty_banner), modifier = Modifier.fillMaxWidth())
                    }
                MapMode.COMMUNITY ->
                    when (communityState.coverage) {
                        CommunityCoverage.ZOOM_IN ->
                            InfoBanner(text = stringResource(R.string.community_zoom_in_banner), modifier = Modifier.fillMaxWidth())
                        CommunityCoverage.LIMITED ->
                            InfoBanner(text = stringResource(R.string.community_limited_banner), modifier = Modifier.fillMaxWidth())
                        CommunityCoverage.UNKNOWN, CommunityCoverage.AVAILABLE -> Unit
                    }
            }
            MapLegend(expanded = legendExpanded, onToggle = { legendExpanded = !legendExpanded })
        }
    }

    if (mode == MapMode.PERSONAL) {
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
    communityState.sheet?.let { sheet ->
        CommunityHazardSheet(state = sheet, onDismiss = onCommunityHazardSheetDismissed)
    }
}

/** "Where to?" (S6, S8). Opens the search screen; the field itself takes no text input here. */
@Composable
private fun SearchField(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val label = stringResource(R.string.community_search_hint)
    Surface(
        modifier =
            modifier
                .heightIn(min = 56.dp)
                .clip(MaterialTheme.shapes.extraLarge)
                .clickable(
                    role = Role.Button,
                    onClickLabel = stringResource(R.string.community_search_open),
                    onClick = onClick,
                ),
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        shape = MaterialTheme.shapes.extraLarge,
        shadowElevation = 3.dp,
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(imageVector = Icons.Filled.Search, contentDescription = null)
            Text(
                text = label,
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** "My trips | Community" (S6). Viewing the community map needs no opt-in: the tiles are public data. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MapModeToggle(
    mode: MapMode,
    onModeSelected: (MapMode) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        shape = MaterialTheme.shapes.medium,
        shadowElevation = 2.dp,
    ) {
        SingleChoiceSegmentedButtonRow(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
        ) {
            SegmentedButton(
                selected = mode == MapMode.PERSONAL,
                onClick = { onModeSelected(MapMode.PERSONAL) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
            ) {
                Text(text = stringResource(R.string.map_mode_my_trips))
            }
            SegmentedButton(
                selected = mode == MapMode.COMMUNITY,
                onClick = { onModeSelected(MapMode.COMMUNITY) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
            ) {
                Text(text = stringResource(R.string.map_mode_community))
            }
        }
    }
}

/** Unobtrusive status line over the map (S6), announced politely by TalkBack. */
@Composable
private fun InfoBanner(
    text: String,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        color = MaterialTheme.colorScheme.secondaryContainer,
        contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        shape = MaterialTheme.shapes.medium,
    ) {
        Text(
            text = text,
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

/** Community map description for TalkBack, with the hazards in view once the map has been measured. */
@Composable
private fun communityMapDescription(viewport: CommunityViewport?): String {
    val base = stringResource(R.string.community_map_content_description)
    return when (CommunityCoverage.of(viewport)) {
        CommunityCoverage.UNKNOWN -> base
        CommunityCoverage.ZOOM_IN ->
            stringResource(R.string.community_map_description_format, base, stringResource(R.string.community_zoom_in_banner))
        CommunityCoverage.LIMITED ->
            stringResource(R.string.community_map_description_format, base, stringResource(R.string.community_limited_banner))
        CommunityCoverage.AVAILABLE -> {
            val counts = viewport ?: return base
            stringResource(
                R.string.community_map_summary_format,
                base,
                pluralStringResource(R.plurals.map_summary_severe, counts.severeCount, counts.severeCount),
                pluralStringResource(R.plurals.map_summary_moderate, counts.moderateCount, counts.moderateCount),
                pluralStringResource(R.plurals.map_summary_minor, counts.minorCount, counts.minorCount),
            )
        }
    }
}
