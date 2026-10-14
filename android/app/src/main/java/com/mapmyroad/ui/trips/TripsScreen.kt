package com.mapmyroad.ui.trips

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.ui.components.BandIndicator
import com.mapmyroad.ui.components.ObserveAsEvents
import com.mapmyroad.ui.components.PreviewData
import com.mapmyroad.ui.format.ScoreBand
import com.mapmyroad.ui.format.TripFormat
import com.mapmyroad.ui.format.rememberTripDateTime
import com.mapmyroad.ui.theme.MapMyRoadTheme
import kotlinx.coroutines.launch

/** Trips list (S5). */
@Composable
fun TripsRoute(
    onOpenTrip: (tripId: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TripsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val deletedMessage = stringResource(R.string.trips_deleted)
    val failedMessage = stringResource(R.string.common_delete_failed)

    ObserveAsEvents(viewModel.events) { event ->
        val message =
            when (event) {
                TripsEvent.TripDeleted -> deletedMessage
                TripsEvent.DeleteFailed -> failedMessage
            }
        scope.launch { snackbarHostState.showSnackbar(message) }
    }

    TripsScreen(
        state = state,
        snackbarHostState = snackbarHostState,
        onOpenTrip = onOpenTrip,
        onDeleteTrip = viewModel::onDeleteTrip,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripsScreen(
    state: TripsUiState,
    snackbarHostState: SnackbarHostState,
    onOpenTrip: (tripId: String) -> Unit,
    onDeleteTrip: (tripId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var pendingDeleteTripId by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.trips_title)) }) },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (state) {
                TripsUiState.Loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                is TripsUiState.Loaded ->
                    if (state.trips.isEmpty()) {
                        Text(
                            text = stringResource(R.string.trips_empty),
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier.padding(24.dp),
                        )
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(items = state.trips, key = { it.id }) { trip ->
                                TripCard(
                                    trip = trip,
                                    onOpen = { onOpenTrip(trip.id) },
                                    onDelete = { pendingDeleteTripId = trip.id },
                                )
                            }
                        }
                    }
            }
        }
    }

    val tripIdToDelete = pendingDeleteTripId
    if (tripIdToDelete != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteTripId = null },
            title = { Text(text = stringResource(R.string.summary_delete_dialog_title)) },
            text = { Text(text = stringResource(R.string.summary_delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteTripId = null
                        onDeleteTrip(tripIdToDelete)
                    },
                ) {
                    Text(text = stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteTripId = null }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun TripCard(
    trip: Trip,
    onOpen: () -> Unit,
    onDelete: () -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    var isMenuExpanded by remember { mutableStateOf(false) }
    val openLabel = stringResource(R.string.trips_open_summary)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable(onClickLabel = openLabel, onClick = onOpen)
                    .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(text = rememberTripDateTime(trip.startedAtMs), style = MaterialTheme.typography.titleMedium)
                Text(
                    text =
                        stringResource(
                            R.string.trips_distance_duration,
                            stringResource(R.string.common_distance_km, TripFormat.distanceKm(trip.distanceM, locale)),
                            TripFormat.duration(trip.durationS.toLong(), locale),
                        ),
                    style = MaterialTheme.typography.bodyLarge,
                )
                BandIndicator(band = ScoreBand.of(trip.smoothnessScore))
            }
            Box {
                IconButton(onClick = { isMenuExpanded = true }) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.trips_more_actions),
                    )
                }
                DropdownMenu(expanded = isMenuExpanded, onDismissRequest = { isMenuExpanded = false }) {
                    DropdownMenuItem(
                        text = { Text(text = stringResource(R.string.summary_delete_trip)) },
                        onClick = {
                            isMenuExpanded = false
                            onDelete()
                        },
                    )
                }
            }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TripsScreenPreview() {
    MapMyRoadTheme {
        TripsScreen(
            state = TripsUiState.Loaded(PreviewData.trips),
            snackbarHostState = remember { SnackbarHostState() },
            onOpenTrip = {},
            onDeleteTrip = {},
        )
    }
}

@Preview(name = "Empty", showBackground = true)
@Composable
private fun TripsScreenEmptyPreview() {
    MapMyRoadTheme {
        TripsScreen(
            state = TripsUiState.Loaded(emptyList()),
            snackbarHostState = remember { SnackbarHostState() },
            onOpenTrip = {},
            onDeleteTrip = {},
        )
    }
}
