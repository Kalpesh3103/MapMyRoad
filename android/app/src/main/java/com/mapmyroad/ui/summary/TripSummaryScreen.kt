package com.mapmyroad.ui.summary

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.data.upload.TripUploadState
import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.ui.components.BandIndicator
import com.mapmyroad.ui.components.ObserveAsEvents
import com.mapmyroad.ui.components.PreviewData
import com.mapmyroad.ui.components.SeverityMarker
import com.mapmyroad.ui.components.labelRes
import com.mapmyroad.ui.format.ScoreBand
import com.mapmyroad.ui.format.TripFormat
import com.mapmyroad.ui.format.rememberTripDateTime
import com.mapmyroad.ui.map.TripRouteMap
import com.mapmyroad.ui.summary.review.HazardReviewSection
import com.mapmyroad.ui.theme.MapMyRoadTheme

/** Trip summary (S4). */
@Composable
fun TripSummaryRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TripSummaryViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            TripSummaryEvent.TripDeleted -> onBack()
        }
    }
    TripSummaryScreen(
        state = state,
        onBack = onBack,
        onDeleteTrip = viewModel::onDeleteTrip,
        modifier = modifier,
        routeMap = { tripId, mapModifier -> TripRouteMap(tripId = tripId, modifier = mapModifier) },
        hazardReview = { tripId, reviewModifier -> HazardReviewSection(tripId = tripId, modifier = reviewModifier) },
    )
}

/** [routeMap] and [hazardReview] (S12) are slots so previews can render without MapLibre or view models. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripSummaryScreen(
    state: TripSummaryUiState,
    onBack: () -> Unit,
    onDeleteTrip: () -> Unit,
    modifier: Modifier = Modifier,
    routeMap: @Composable (tripId: String, modifier: Modifier) -> Unit = { _, _ -> },
    hazardReview: @Composable (tripId: String, modifier: Modifier) -> Unit = { _, _ -> },
) {
    // A confirmation dialog is fine here: the summary is never shown during a trip.
    var isDeleteDialogVisible by rememberSaveable { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(text = stringResource(R.string.summary_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (state is TripSummaryUiState.Loaded) {
                        IconButton(onClick = { isDeleteDialogVisible = true }) {
                            Icon(
                                imageVector = Icons.Filled.Delete,
                                contentDescription = stringResource(R.string.summary_delete_trip),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when (state) {
                TripSummaryUiState.Loading ->
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                TripSummaryUiState.NotFound ->
                    Text(
                        text = stringResource(R.string.summary_not_found),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp),
                    )
                is TripSummaryUiState.Loaded ->
                    TripSummaryContent(
                        trip = state.trip,
                        hasDeleteFailed = state.hasDeleteFailed,
                        uploadState = state.uploadState,
                        uploadsNeedWifi = state.uploadsNeedWifi,
                        onDeleteClick = { isDeleteDialogVisible = true },
                        routeMap = routeMap,
                        hazardReview = hazardReview,
                    )
            }
        }
    }

    if (isDeleteDialogVisible) {
        AlertDialog(
            onDismissRequest = { isDeleteDialogVisible = false },
            title = { Text(text = stringResource(R.string.summary_delete_dialog_title)) },
            text = { Text(text = stringResource(R.string.summary_delete_dialog_body)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isDeleteDialogVisible = false
                        onDeleteTrip()
                    },
                ) {
                    Text(text = stringResource(R.string.common_delete), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { isDeleteDialogVisible = false }) {
                    Text(text = stringResource(R.string.common_cancel))
                }
            },
        )
    }
}

@Composable
private fun TripSummaryContent(
    trip: Trip,
    hasDeleteFailed: Boolean,
    uploadState: TripUploadState,
    uploadsNeedWifi: Boolean,
    onDeleteClick: () -> Unit,
    routeMap: @Composable (tripId: String, modifier: Modifier) -> Unit,
    hazardReview: @Composable (tripId: String, modifier: Modifier) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = rememberTripDateTime(trip.startedAtMs),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.semantics { heading() },
        )
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Fact(
                label = stringResource(R.string.summary_duration),
                value = TripFormat.duration(trip.durationS.toLong(), locale),
            )
            Fact(
                label = stringResource(R.string.summary_distance),
                value = stringResource(R.string.common_distance_km, TripFormat.distanceKm(trip.distanceM, locale)),
            )
            Fact(label = stringResource(R.string.summary_vehicle), value = stringResource(trip.vehicleType.labelRes()))
        }
        uploadStateRes(uploadState, uploadsNeedWifi)?.let { res ->
            Text(
                text = stringResource(res),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (trip.stopReason == StopReason.SERVICE_KILLED) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Icon(imageVector = Icons.Filled.Warning, contentDescription = null)
                    Text(
                        text = stringResource(R.string.summary_service_killed),
                        style = MaterialTheme.typography.bodyLarge,
                    )
                }
            }
        }
        SmoothnessCard(score = trip.smoothnessScore)
        routeMap(
            trip.id,
            Modifier
                .fillMaxWidth()
                .height(240.dp)
                .clip(RoundedCornerShape(12.dp)),
        )
        Text(
            text = stringResource(R.string.summary_bumps_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        SeverityCountRow(Severity.MINOR, R.plurals.summary_minor_count, trip.minorCount)
        SeverityCountRow(Severity.MODERATE, R.plurals.summary_moderate_count, trip.moderateCount)
        SeverityCountRow(Severity.SEVERE, R.plurals.summary_severe_count, trip.severeCount)
        // S12: shown only to contributors for finished, recent trips that passed known hazards (ADR-0013).
        if (trip.isFinished) {
            hazardReview(trip.id, Modifier.fillMaxWidth())
        }
        if (hasDeleteFailed) {
            Text(
                text = stringResource(R.string.common_delete_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        OutlinedButton(
            onClick = onDeleteClick,
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.summary_delete_trip))
        }
    }
}

/** S4 v2 sharing line; nothing when the trip was not shared because contribution is off. */
private fun uploadStateRes(
    state: TripUploadState,
    needsWifi: Boolean,
): Int? =
    when (state) {
        TripUploadState.NOT_SHARED -> null
        TripUploadState.WAITING -> if (needsWifi) R.string.upload_state_waiting_wifi else R.string.upload_state_waiting
        TripUploadState.SHARED -> R.string.upload_state_shared
        TripUploadState.SKIPPED -> R.string.upload_state_skipped
        TripUploadState.FAILED -> R.string.upload_state_failed
    }

@Composable
private fun Fact(
    label: String,
    value: String,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun SmoothnessCard(score: Int?) {
    val band = ScoreBand.of(score)
    val bandLabel = stringResource(band.labelRes())
    val description =
        if (score != null) {
            stringResource(R.string.summary_smoothness_description, score, bandLabel)
        } else {
            stringResource(R.string.summary_smoothness_no_data_description)
        }
    OutlinedCard(modifier = Modifier.fillMaxWidth().semantics(mergeDescendants = true) { contentDescription = description }) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.summary_smoothness_title), style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = score?.toString() ?: stringResource(R.string.summary_score_missing),
                    style = MaterialTheme.typography.displaySmall,
                )
                if (score != null) {
                    Text(
                        text = stringResource(R.string.summary_score_out_of),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(bottom = 6.dp),
                    )
                }
            }
            BandIndicator(band = band)
        }
    }
}

@Composable
private fun SeverityCountRow(
    severity: Severity,
    pluralRes: Int,
    count: Int,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.heightIn(min = 32.dp),
    ) {
        SeverityMarker(severity = severity)
        Text(text = pluralStringResource(pluralRes, count, count), style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Font 200%", showBackground = true, fontScale = 2f)
@Composable
private fun TripSummaryPreview() {
    MapMyRoadTheme {
        TripSummaryScreen(
            state = TripSummaryUiState.Loaded(trip = PreviewData.trip, hasDeleteFailed = false),
            onBack = {},
            onDeleteTrip = {},
        )
    }
}

@Preview(name = "No score, service killed", showBackground = true)
@Composable
private fun TripSummaryNoScorePreview() {
    MapMyRoadTheme {
        TripSummaryScreen(
            state =
                TripSummaryUiState.Loaded(
                    trip = PreviewData.trip.copy(smoothnessScore = null, stopReason = StopReason.SERVICE_KILLED),
                    hasDeleteFailed = false,
                ),
            onBack = {},
            onDeleteTrip = {},
        )
    }
}
