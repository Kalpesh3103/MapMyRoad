package com.mapmyroad.ui.home

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.ui.components.BandIndicator
import com.mapmyroad.ui.components.LocationPermissions
import com.mapmyroad.ui.components.ObserveAsEvents
import com.mapmyroad.ui.components.PreviewData
import com.mapmyroad.ui.components.VehicleSelector
import com.mapmyroad.ui.components.hasFineLocationPermission
import com.mapmyroad.ui.components.hasNotificationPermission
import com.mapmyroad.ui.components.isIgnoringBatteryOptimizations
import com.mapmyroad.ui.components.openAppDetailsSettings
import com.mapmyroad.ui.format.DayPart
import com.mapmyroad.ui.format.ScoreBand
import com.mapmyroad.ui.format.TripFormat
import com.mapmyroad.ui.format.rememberTripDateTime
import com.mapmyroad.ui.theme.MapMyRoadTheme
import java.util.Calendar

/**
 * Home (S2). Handles the permission flow before a trip starts: location is required (rationale first, PRD PV-4);
 * notifications are requested once on Android 13+, and the trip starts whatever the answer.
 */
@Composable
fun HomeRoute(
    onOpenLiveTrip: () -> Unit,
    onOpenTripSummary: (tripId: String) -> Unit,
    onOpenBatteryGuidance: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = LocalHapticFeedback.current
    var isLocationRationaleVisible by rememberSaveable { mutableStateOf(false) }
    var isLocationDenied by rememberSaveable { mutableStateOf(false) }
    var isNotificationDenied by rememberSaveable { mutableStateOf(false) }
    var hasAskedNotifications by rememberSaveable { mutableStateOf(false) }
    val isBatteryRestricted = remember { !context.isIgnoringBatteryOptimizations() }
    val dayPart = remember { DayPart.of(Calendar.getInstance().get(Calendar.HOUR_OF_DAY)) }

    ObserveAsEvents(viewModel.events) { event ->
        when (event) {
            HomeEvent.OpenLiveTrip -> onOpenLiveTrip()
        }
    }

    val startTrip: () -> Unit = {
        // Haptic tick on trip start (UI_UX_GUIDELINES.md S3).
        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        viewModel.onStartTrip()
    }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            isNotificationDenied = !isGranted
            startTrip()
        }
    val continueAfterLocation: () -> Unit = {
        val shouldAskNotifications = !hasAskedNotifications && !context.hasNotificationPermission()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && shouldAskNotifications) {
            hasAskedNotifications = true
            notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            isNotificationDenied = !context.hasNotificationPermission()
            startTrip()
        }
    }
    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            val isFineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            isLocationDenied = !isFineGranted
            if (isFineGranted) continueAfterLocation()
        }

    HomeScreen(
        state = state,
        dayPart = dayPart,
        isBatteryRestricted = isBatteryRestricted,
        isLocationDenied = isLocationDenied,
        isNotificationDenied = isNotificationDenied,
        onVehicleSelected = viewModel::onVehicleSelected,
        onPrimaryAction = {
            when {
                state.sessionStatus == HomeSessionStatus.RECORDING -> onOpenLiveTrip()
                !context.hasFineLocationPermission() -> isLocationRationaleVisible = true
                else -> {
                    isLocationDenied = false
                    continueAfterLocation()
                }
            }
        },
        onOpenLiveTrip = onOpenLiveTrip,
        onOpenTripSummary = onOpenTripSummary,
        onOpenBatteryGuidance = onOpenBatteryGuidance,
        onDismissBatteryCard = viewModel::onDismissBatteryCard,
        onDismissServiceKilledCard = viewModel::onDismissServiceKilledCard,
        onOpenAppSettings = { context.openAppDetailsSettings() },
        modifier = modifier,
    )

    // A dialog is allowed here: Home is never shown during a trip, and the user is stationary when tapping Start.
    if (isLocationRationaleVisible) {
        LocationRationaleDialog(
            onContinue = {
                isLocationRationaleVisible = false
                locationLauncher.launch(LocationPermissions)
            },
            onDismiss = { isLocationRationaleVisible = false },
        )
    }
}

@Composable
fun HomeScreen(
    state: HomeUiState,
    dayPart: DayPart,
    isBatteryRestricted: Boolean,
    isLocationDenied: Boolean,
    isNotificationDenied: Boolean,
    onVehicleSelected: (VehicleType) -> Unit,
    onPrimaryAction: () -> Unit,
    onOpenLiveTrip: () -> Unit,
    onOpenTripSummary: (tripId: String) -> Unit,
    onOpenBatteryGuidance: () -> Unit,
    onDismissBatteryCard: () -> Unit,
    onDismissServiceKilledCard: () -> Unit,
    onOpenAppSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                Text(
                    text = stringResource(dayPart.greetingRes()),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.semantics { heading() },
                )
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(text = stringResource(R.string.home_vehicle_label), style = MaterialTheme.typography.bodyLarge)
                    VehicleSelector(
                        selected = state.vehicleType,
                        onSelect = onVehicleSelected,
                        enabled = state.sessionStatus == HomeSessionStatus.IDLE,
                    )
                }
                if (state.sessionStatus == HomeSessionStatus.RECORDING) {
                    StatusCard(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.home_trip_in_progress_title),
                        body = stringResource(R.string.home_trip_in_progress_body),
                        actionLabel = stringResource(R.string.home_trip_in_progress_open),
                        onAction = onOpenLiveTrip,
                    )
                }
                if (state.isServiceKilledCardVisible) {
                    StatusCard(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.home_service_killed_title),
                        body = stringResource(R.string.home_service_killed_body),
                        actionLabel = stringResource(R.string.home_battery_guidance_action),
                        onAction = onOpenBatteryGuidance,
                        onDismiss = onDismissServiceKilledCard,
                    )
                }
                if (isLocationDenied) {
                    StatusCard(
                        icon = Icons.Filled.Warning,
                        title = stringResource(R.string.permission_location_denied_title),
                        body = stringResource(R.string.home_location_denied_body),
                        actionLabel = stringResource(R.string.permission_open_settings),
                        onAction = onOpenAppSettings,
                    )
                }
                if (isBatteryRestricted && !state.isBatteryCardDismissed && !state.isServiceKilledCardVisible) {
                    StatusCard(
                        icon = Icons.Filled.Info,
                        title = stringResource(R.string.home_battery_card_title),
                        body = stringResource(R.string.home_battery_card_body),
                        actionLabel = stringResource(R.string.home_battery_guidance_action),
                        onAction = onOpenBatteryGuidance,
                        onDismiss = onDismissBatteryCard,
                    )
                }
                when {
                    state.isLoading -> LoadingPlaceholder()
                    state.latestTrip != null -> LastTripCard(trip = state.latestTrip, onOpenTripSummary = onOpenTripSummary)
                    else -> EmptyTripsCard()
                }
            }
            StartArea(
                state = state,
                isNotificationDenied = isNotificationDenied,
                onPrimaryAction = onPrimaryAction,
            )
        }
    }
}

/** Primary action pinned to the bottom, in thumb reach (UI_UX_GUIDELINES.md 4.5). */
@Composable
private fun StartArea(
    state: HomeUiState,
    isNotificationDenied: Boolean,
    onPrimaryAction: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (state.hasStartFailed) {
            Text(
                text = stringResource(R.string.home_start_failed),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (state.vehicleType == null && !state.isLoading) {
            Text(text = stringResource(R.string.home_choose_vehicle_first), style = MaterialTheme.typography.bodyLarge)
        }
        if (isNotificationDenied && state.sessionStatus == HomeSessionStatus.IDLE) {
            Text(text = stringResource(R.string.home_notifications_off), style = MaterialTheme.typography.bodyLarge)
        }
        val label =
            when {
                state.sessionStatus == HomeSessionStatus.RECORDING -> R.string.home_open_live_trip
                state.sessionStatus == HomeSessionStatus.FINISHING -> R.string.home_saving_trip
                state.isStarting -> R.string.home_starting_trip
                else -> R.string.home_start_trip
            }
        val isEnabled =
            when (state.sessionStatus) {
                HomeSessionStatus.RECORDING -> true
                HomeSessionStatus.FINISHING -> false
                HomeSessionStatus.IDLE -> !state.isStarting && !state.isLoading && state.vehicleType != null
            }
        Button(
            onClick = onPrimaryAction,
            enabled = isEnabled,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
        ) {
            Text(text = stringResource(label), style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun StatusCard(
    icon: ImageVector,
    title: String,
    body: String,
    actionLabel: String,
    onAction: () -> Unit,
    onDismiss: (() -> Unit)? = null,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                // The title carries the meaning, so the icon is decorative.
                Icon(imageVector = icon, contentDescription = null)
                Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            Text(text = body, style = MaterialTheme.typography.bodyLarge)
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                if (onDismiss != null) {
                    TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(text = stringResource(R.string.common_dismiss))
                    }
                }
                TextButton(onClick = onAction, modifier = Modifier.heightIn(min = 48.dp)) {
                    Text(text = actionLabel)
                }
            }
        }
    }
}

@Composable
private fun LastTripCard(
    trip: Trip,
    onOpenTripSummary: (tripId: String) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val bumpCount = trip.minorCount + trip.moderateCount + trip.severeCount
    val band = ScoreBand.of(trip.smoothnessScore)
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.home_last_trip_title),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(text = rememberTripDateTime(trip.startedAtMs), style = MaterialTheme.typography.bodyLarge)
            Text(
                text = stringResource(R.string.common_distance_km, TripFormat.distanceKm(trip.distanceM, locale)),
                style = MaterialTheme.typography.bodyLarge,
            )
            Text(
                text =
                    if (trip.smoothnessScore != null) {
                        stringResource(R.string.home_smoothness_value, trip.smoothnessScore)
                    } else {
                        stringResource(R.string.home_smoothness_label)
                    },
                style = MaterialTheme.typography.bodyLarge,
            )
            BandIndicator(band = band)
            Text(
                text = pluralStringResource(R.plurals.home_bumps_count, bumpCount, bumpCount),
                style = MaterialTheme.typography.bodyLarge,
            )
            TextButton(
                onClick = { onOpenTripSummary(trip.id) },
                modifier = Modifier.heightIn(min = 48.dp),
            ) {
                Text(text = stringResource(R.string.home_view_summary))
            }
        }
    }
}

@Composable
private fun EmptyTripsCard() {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(text = stringResource(R.string.home_empty_body), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

/** Skeleton placeholder while data loads (UI_UX_GUIDELINES.md section 8). */
@Composable
private fun LoadingPlaceholder() {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        modifier =
            Modifier
                .fillMaxWidth()
                .height(160.dp),
    ) {}
}

@Composable
private fun LocationRationaleDialog(
    onContinue: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = stringResource(R.string.permission_location_title)) },
        text = { Text(text = stringResource(R.string.permission_location_body)) },
        confirmButton = {
            TextButton(onClick = onContinue) { Text(text = stringResource(R.string.onboarding_continue)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = stringResource(R.string.onboarding_not_now)) }
        },
    )
}

private fun DayPart.greetingRes(): Int =
    when (this) {
        DayPart.MORNING -> R.string.home_greeting_morning
        DayPart.AFTERNOON -> R.string.home_greeting_afternoon
        DayPart.EVENING -> R.string.home_greeting_evening
    }

@Composable
private fun HomePreview(state: HomeUiState) {
    MapMyRoadTheme {
        HomeScreen(
            state = state,
            dayPart = DayPart.MORNING,
            isBatteryRestricted = true,
            isLocationDenied = false,
            isNotificationDenied = false,
            onVehicleSelected = {},
            onPrimaryAction = {},
            onOpenLiveTrip = {},
            onOpenTripSummary = {},
            onOpenBatteryGuidance = {},
            onDismissBatteryCard = {},
            onDismissServiceKilledCard = {},
            onOpenAppSettings = {},
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Font 200%", showBackground = true, fontScale = 2f)
@Composable
private fun HomeScreenPreview() {
    HomePreview(
        HomeUiState(isLoading = false, vehicleType = VehicleType.SCOOTER, latestTrip = PreviewData.trip),
    )
}

@Preview(name = "Empty", showBackground = true)
@Composable
private fun HomeScreenEmptyPreview() {
    HomePreview(HomeUiState(isLoading = false, vehicleType = VehicleType.CAR, isBatteryCardDismissed = true))
}

@Preview(name = "Recording", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun HomeScreenRecordingPreview() {
    HomePreview(
        HomeUiState(
            isLoading = false,
            vehicleType = VehicleType.MOTORCYCLE,
            latestTrip = PreviewData.trip,
            sessionStatus = HomeSessionStatus.RECORDING,
            isBatteryCardDismissed = true,
        ),
    )
}
