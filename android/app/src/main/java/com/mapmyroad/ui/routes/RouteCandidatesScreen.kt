package com.mapmyroad.ui.routes

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.CoverageLevel
import com.mapmyroad.domain.model.RouteCandidate
import com.mapmyroad.domain.model.RouteCoverage
import com.mapmyroad.domain.model.RouteLabel
import com.mapmyroad.ui.components.BackTopAppBar
import com.mapmyroad.ui.components.BandIndicator
import com.mapmyroad.ui.components.LocationPermissions
import com.mapmyroad.ui.components.VehicleSelector
import com.mapmyroad.ui.components.hasFineLocationPermission
import com.mapmyroad.ui.components.openAppDetailsSettings
import com.mapmyroad.ui.format.ScoreBand
import com.mapmyroad.ui.format.TripFormat
import java.util.Locale

/**
 * Route candidates (S8, M5-8, M5-9; PRD RT-2, RT-3, RT-4, flow 7.3). For planning before a trip: there are no
 * dialogs or prompts, so showing it while a trip records is harmless, and nothing here starts navigation
 * ("Start" arrives with in-app navigation in v3). "Open in Google Maps" is always available.
 */
@Composable
fun RouteCandidatesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RouteCandidatesViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var isLocationDenied by rememberSaveable { mutableStateOf(false) }

    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val isFineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            isLocationDenied = !isFineGranted
            if (isFineGranted) viewModel.onLocationPermissionGranted() else viewModel.onLocationPermissionMissing()
        }

    // Re-checked on every resume, so turning location on in system settings is picked up on return.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val currentViewModel by rememberUpdatedState(viewModel)
    DisposableEffect(lifecycle, context) {
        val observer =
            LifecycleEventObserver { _, event ->
                if (event == Lifecycle.Event.ON_RESUME) {
                    if (context.hasFineLocationPermission()) {
                        isLocationDenied = false
                        currentViewModel.onLocationPermissionGranted()
                    } else {
                        currentViewModel.onLocationPermissionMissing()
                    }
                }
            }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    RouteCandidatesScreen(
        uiState = uiState,
        isLocationDenied = isLocationDenied,
        onBack = onBack,
        onAllowLocation = { locationLauncher.launch(LocationPermissions) },
        onOpenAppSettings = { context.openAppDetailsSettings() },
        onRetryLocation = viewModel::onRetryLocation,
        onVehicleSelected = viewModel::onVehicleSelected,
        onCandidateSelected = viewModel::onCandidateSelected,
        onSliderChange = viewModel::onSliderChange,
        onRetry = viewModel::onRetry,
        onDismissEtaExplainer = viewModel::onTypicalEtaExplainerDismissed,
        onOpenGoogleMaps = {
            val url =
                GoogleMapsHandoff.directionsUrl(
                    originLat = uiState.origin?.lat,
                    originLon = uiState.origin?.lon,
                    destinationLat = uiState.destination.lat,
                    destinationLon = uiState.destination.lon,
                    vehicleType = uiState.vehicleType,
                )
            context.openGoogleMapsDirections(url)
        },
        modifier = modifier,
    )
}

@Composable
fun RouteCandidatesScreen(
    uiState: RouteCandidatesUiState,
    isLocationDenied: Boolean,
    onBack: () -> Unit,
    onAllowLocation: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRetryLocation: () -> Unit,
    onVehicleSelected: (VehicleType) -> Unit,
    onCandidateSelected: (String) -> Unit,
    onSliderChange: (Float) -> Unit,
    onRetry: () -> Unit,
    onDismissEtaExplainer: () -> Unit,
    onOpenGoogleMaps: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val title =
        uiState.destinationName?.let { stringResource(R.string.routes_title, it) }
            ?: stringResource(R.string.routes_title_fallback)
    val candidates = uiState.options?.candidates.orEmpty()
    val mapDescription =
        stringResource(
            R.string.routes_map_summary,
            stringResource(R.string.routes_map_description),
            pluralStringResource(R.plurals.routes_map_count, candidates.size, candidates.size),
        )

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopAppBar(title = title, onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
        ) {
            RouteCandidatesMap(
                lines = uiState.lines,
                selectedId = uiState.selectedId,
                origin = uiState.origin,
                destination = uiState.destination,
                mapDescription = mapDescription,
                onCandidateTapped = onCandidateSelected,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(MAP_WEIGHT),
            )
            if (uiState.isLoading) {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }
            LazyColumn(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .weight(LIST_WEIGHT),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (uiState.needsVehicle) {
                    item(key = "vehicle") { VehicleCard(onVehicleSelected = onVehicleSelected) }
                }
                if (uiState.originStatus != OriginStatus.READY) {
                    item(key = "origin") {
                        OriginCard(
                            status = uiState.originStatus,
                            isLocationDenied = isLocationDenied,
                            onAllowLocation = onAllowLocation,
                            onOpenAppSettings = onOpenAppSettings,
                            onRetryLocation = onRetryLocation,
                        )
                    }
                }
                uiState.error?.let { error ->
                    item(key = "error") { ErrorCard(error = error, onRetry = onRetry) }
                }
                if (uiState.isLoading && candidates.isEmpty()) {
                    item(key = "loading") { LoadingRow() }
                }
                if (uiState.isTypicalEtaExplainerVisible) {
                    item(key = "eta-explainer") { EtaExplainerCard(onDismiss = onDismissEtaExplainer) }
                }
                val fastest = RouteTradeOffs.fastestOf(candidates)
                items(items = candidates, key = { "candidate-${it.id}" }) { candidate ->
                    CandidateCard(
                        candidate = candidate,
                        fastest = fastest,
                        fallbackCoverage = uiState.options?.coverage,
                        isSelected = candidate.id == uiState.selectedId,
                        onSelect = { onCandidateSelected(candidate.id) },
                    )
                }
                if (candidates.isNotEmpty()) {
                    item(key = "preference") {
                        PreferenceSlider(position = uiState.sliderPosition, onChange = onSliderChange)
                    }
                }
                item(key = "handoff") { GoogleMapsHandoffRow(onOpenGoogleMaps = onOpenGoogleMaps) }
            }
        }
    }
}

@Composable
private fun VehicleCard(onVehicleSelected: (VehicleType) -> Unit) {
    InfoSurface {
        Text(
            text = stringResource(R.string.routes_vehicle_title),
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { heading() },
        )
        Text(text = stringResource(R.string.routes_vehicle_body), style = MaterialTheme.typography.bodyMedium)
        VehicleSelector(selected = null, onSelect = onVehicleSelected)
    }
}

@Composable
private fun OriginCard(
    status: OriginStatus,
    isLocationDenied: Boolean,
    onAllowLocation: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onRetryLocation: () -> Unit,
) {
    InfoSurface {
        when (status) {
            OriginStatus.NEEDS_PERMISSION ->
                if (isLocationDenied) {
                    Text(text = stringResource(R.string.routes_location_denied), style = MaterialTheme.typography.bodyLarge)
                    OutlinedButton(onClick = onOpenAppSettings, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text = stringResource(R.string.routes_location_open_settings))
                    }
                } else {
                    Text(
                        text = stringResource(R.string.routes_location_rationale_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        text = stringResource(R.string.routes_location_rationale_body),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Button(onClick = onAllowLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text = stringResource(R.string.routes_location_allow))
                    }
                }
            OriginStatus.LOCATING -> LoadingRow(textRes = R.string.routes_locating)
            OriginStatus.UNAVAILABLE -> {
                Text(
                    text = stringResource(R.string.routes_location_unavailable),
                    style = MaterialTheme.typography.bodyLarge,
                )
                OutlinedButton(onClick = onRetryLocation, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text = stringResource(R.string.routes_retry))
                }
            }
            OriginStatus.READY -> Unit
        }
    }
}

@Composable
private fun ErrorCard(
    error: RouteError,
    onRetry: () -> Unit,
) {
    val message =
        when (error) {
            RouteError.OFFLINE -> R.string.routes_error_offline
            RouteError.UNAVAILABLE -> R.string.routes_error_unavailable
            RouteError.NO_ROUTE -> R.string.routes_error_no_route
            RouteError.OUTSIDE_AREA -> R.string.routes_error_outside_area
            RouteError.TOO_FAR -> R.string.routes_error_too_far
            RouteError.GENERIC -> R.string.routes_error_generic
        }
    // Retrying the same request cannot help when the server said there is no route or the area is unsupported.
    val canRetry = error == RouteError.OFFLINE || error == RouteError.UNAVAILABLE || error == RouteError.GENERIC
    InfoSurface(color = MaterialTheme.colorScheme.errorContainer) {
        Text(
            text = stringResource(message),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
        )
        if (canRetry) {
            OutlinedButton(onClick = onRetry, modifier = Modifier.heightIn(min = 48.dp)) {
                Text(text = stringResource(R.string.routes_retry))
            }
        }
    }
}

@Composable
private fun EtaExplainerCard(onDismiss: () -> Unit) {
    InfoSurface(color = MaterialTheme.colorScheme.secondaryContainer) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(imageVector = Icons.Filled.Info, contentDescription = null)
            Text(
                text = stringResource(R.string.routes_eta_explainer_title),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.semantics { heading() },
            )
        }
        Text(text = stringResource(R.string.routes_eta_explainer_body), style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
            Text(text = stringResource(R.string.routes_eta_explainer_dismiss))
        }
    }
}

@Composable
private fun CandidateCard(
    candidate: RouteCandidate,
    fastest: RouteCandidate?,
    fallbackCoverage: RouteCoverage?,
    isSelected: Boolean,
    onSelect: () -> Unit,
) {
    val shape = MaterialTheme.shapes.medium
    val distanceText =
        stringResource(R.string.common_distance_km, TripFormat.distanceKm(candidate.distanceM.toDouble(), Locale.getDefault()))
    // Selection is shown by the radio button and a thicker border, not by color alone.
    val border =
        if (isSelected) {
            BorderStroke(SELECTED_BORDER, MaterialTheme.colorScheme.primary)
        } else {
            BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        }
    Surface(
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .selectable(selected = isSelected, onClick = onSelect, role = Role.RadioButton),
        shape = shape,
        border = border,
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                // Decorative: the whole card is the radio button for TalkBack.
                RadioButton(selected = isSelected, onClick = null)
                LabelsText(labels = candidate.labels, modifier = Modifier.weight(1f))
                if (candidate.isRecommended) RecommendedTag()
            }
            Text(
                text = stringResource(R.string.routes_eta_distance, etaText(candidate.durationS), distanceText),
                style = MaterialTheme.typography.titleMedium,
            )
            ComfortRow(candidate = candidate, fallbackCoverage = fallbackCoverage)
            Text(
                text =
                    stringResource(
                        R.string.routes_hazards_format,
                        pluralStringResource(R.plurals.routes_hazards_severe, candidate.severeCount, candidate.severeCount),
                        pluralStringResource(R.plurals.routes_hazards_rough, candidate.moderateCount, candidate.moderateCount),
                    ),
                style = MaterialTheme.typography.bodyMedium,
            )
            if (RouteTradeOffs.isLimitedData(candidate, fallbackCoverage)) {
                Text(
                    text = stringResource(R.string.routes_limited_data),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            fastest
                ?.let { RouteTradeOffs.tradeOff(candidate, it, fallbackCoverage) }
                ?.let { tradeOff -> Text(text = tradeOffText(tradeOff), style = MaterialTheme.typography.bodyMedium) }
        }
    }
}

@Composable
private fun LabelsText(
    labels: Set<RouteLabel>,
    modifier: Modifier = Modifier,
) {
    val names = RouteTradeOffs.orderedLabels(labels).map { stringResource(it.labelRes()) }
    val text = if (names.isEmpty()) stringResource(R.string.routes_label_alternative) else names.joinToString(separator = " · ")
    Text(text = text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, modifier = modifier)
}

@Composable
private fun RecommendedTag() {
    Surface(
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        shape = MaterialTheme.shapes.small,
    ) {
        Text(
            text = stringResource(R.string.routes_recommended),
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
        )
    }
}

/** "Comfort 81" with its band; softened when coverage is limited (UI_UX section 4.2 and S8). */
@Composable
private fun ComfortRow(
    candidate: RouteCandidate,
    fallbackCoverage: RouteCoverage?,
) {
    val coverage = RouteTradeOffs.coverageOf(candidate, fallbackCoverage)
    val text =
        when (coverage) {
            CoverageLevel.NONE -> stringResource(R.string.routes_comfort_no_data)
            CoverageLevel.PARTIAL -> stringResource(R.string.routes_comfort_limited, candidate.comfortScore)
            CoverageLevel.GOOD, null -> stringResource(R.string.routes_comfort, candidate.comfortScore)
        }
    val band = if (coverage == CoverageLevel.NONE) ScoreBand.NO_DATA else ScoreBand.of(candidate.comfortScore)
    val isSoftened = coverage == CoverageLevel.NONE || coverage == CoverageLevel.PARTIAL
    val color = if (isSoftened) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(text = text, style = MaterialTheme.typography.bodyLarge, color = color)
        BandIndicator(band = band, textStyle = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun PreferenceSlider(
    position: Float,
    onChange: (Float) -> Unit,
) {
    val stateText =
        when {
            position < BALANCED_LOW -> stringResource(R.string.routes_preference_state_comfort)
            position > BALANCED_HIGH -> stringResource(R.string.routes_preference_state_speed)
            else -> stringResource(R.string.routes_preference_state_balanced)
        }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            text = stringResource(R.string.routes_preference_title),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.semantics { heading() },
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = stringResource(R.string.routes_preference_comfort), style = MaterialTheme.typography.labelLarge)
            Slider(
                value = position,
                onValueChange = onChange,
                valueRange = 0f..1f,
                modifier =
                    Modifier
                        .weight(1f)
                        .semantics { stateDescription = stateText },
            )
            Text(text = stringResource(R.string.routes_preference_speed), style = MaterialTheme.typography.labelLarge)
        }
    }
}

@Composable
private fun GoogleMapsHandoffRow(onOpenGoogleMaps: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Button(
            onClick = onOpenGoogleMaps,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.routes_open_google_maps))
        }
        Text(
            text = stringResource(R.string.routes_google_maps_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun LoadingRow(textRes: Int = R.string.routes_loading) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(24.dp))
        Text(text = stringResource(textRes), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun InfoSurface(
    color: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable ColumnScope.() -> Unit,
) {
    Surface(color = color, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)
    }
}

@Composable
private fun etaText(durationS: Int): String {
    val eta = EtaParts.of(durationS)
    return if (eta.hours == 0) {
        stringResource(R.string.routes_eta_minutes, eta.minutes)
    } else {
        stringResource(R.string.routes_eta_hours, eta.hours, eta.minutes)
    }
}

@Composable
private fun tradeOffText(tradeOff: TradeOff): String {
    val minutes = tradeOff.minutesDifference
    val timePart =
        when {
            minutes > 0 -> pluralStringResource(R.plurals.routes_tradeoff_slower, minutes, minutes)
            minutes < 0 -> pluralStringResource(R.plurals.routes_tradeoff_faster, -minutes, -minutes)
            else -> stringResource(R.string.routes_tradeoff_same_time)
        }
    val hazardPart =
        when (tradeOff.hazards) {
            HazardComparison.FAR_FEWER_SEVERE -> R.string.routes_tradeoff_far_fewer_severe
            HazardComparison.FEWER_SEVERE -> R.string.routes_tradeoff_fewer_severe
            HazardComparison.MORE_SEVERE -> R.string.routes_tradeoff_more_severe
            HazardComparison.FEWER_ROUGH -> R.string.routes_tradeoff_fewer_rough
            HazardComparison.MORE_ROUGH -> R.string.routes_tradeoff_more_rough
            HazardComparison.SIMILAR -> R.string.routes_tradeoff_similar
            HazardComparison.UNKNOWN -> R.string.routes_tradeoff_unknown
        }
    return stringResource(R.string.routes_tradeoff_format, timePart, stringResource(hazardPart))
}

private fun RouteLabel.labelRes(): Int =
    when (this) {
        RouteLabel.FASTEST -> R.string.routes_label_fastest
        RouteLabel.SMOOTHEST -> R.string.routes_label_smoothest
        RouteLabel.BALANCED -> R.string.routes_label_balanced
    }

private const val MAP_WEIGHT = 0.42f
private const val LIST_WEIGHT = 0.58f
private const val BALANCED_LOW = 0.4f
private const val BALANCED_HIGH = 0.6f
private val SELECTED_BORDER = 3.dp
