package com.mapmyroad.ui.onboarding

import android.Manifest
import android.content.res.Configuration
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.ui.components.LocationPermissions
import com.mapmyroad.ui.components.VehicleDisplayOrder
import com.mapmyroad.ui.components.areNotificationPermissionsRuntime
import com.mapmyroad.ui.components.hasFineLocationPermission
import com.mapmyroad.ui.components.hasNotificationPermission
import com.mapmyroad.ui.components.labelRes
import com.mapmyroad.ui.components.openAppDetailsSettings
import com.mapmyroad.ui.theme.MapMyRoadTheme

private const val TOTAL_STEPS = 3

/** Onboarding (S1): welcome, vehicle, then one rationale screen per permission just before its system prompt. */
@Composable
fun OnboardingRoute(
    modifier: Modifier = Modifier,
    viewModel: OnboardingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val areNotificationsRuntime = areNotificationPermissionsRuntime

    LaunchedEffect(Unit) { viewModel.onShown() }

    val locationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
            // Approximate location alone cannot place bumps on a road, so only precise location counts as granted.
            val isFineGranted = result[Manifest.permission.ACCESS_FINE_LOCATION] == true
            viewModel.onPermissionResult(isFineGranted, areNotificationsRuntime)
        }
    val notificationLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
            viewModel.onPermissionResult(isGranted, areNotificationsRuntime)
        }

    // A permission that is already granted needs no rationale screen.
    LaunchedEffect(state.step) {
        val isAlreadyGranted =
            when (state.step) {
                OnboardingStep.LOCATION -> context.hasFineLocationPermission()
                OnboardingStep.NOTIFICATIONS -> context.hasNotificationPermission()
                OnboardingStep.WELCOME, OnboardingStep.VEHICLE -> false
            }
        if (isAlreadyGranted) {
            viewModel.onPermissionResult(isGranted = true, areNotificationsRuntime = areNotificationsRuntime)
        }
    }

    BackHandler(enabled = state.step != OnboardingStep.WELCOME && !state.isSaving) { viewModel.onBack() }

    OnboardingScreen(
        state = state,
        onGetStarted = viewModel::onGetStarted,
        onVehicleSelected = viewModel::onVehicleSelected,
        onVehicleConfirmed = viewModel::onVehicleConfirmed,
        onRequestPermission = {
            when (state.step) {
                OnboardingStep.LOCATION -> locationLauncher.launch(LocationPermissions)
                OnboardingStep.NOTIFICATIONS ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        notificationLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                    } else {
                        viewModel.onPermissionResult(isGranted = true, areNotificationsRuntime = false)
                    }
                OnboardingStep.WELCOME, OnboardingStep.VEHICLE -> Unit
            }
        },
        onDeclinePermission = viewModel::onPermissionDeclined,
        onOpenAppSettings = { context.openAppDetailsSettings() },
        onContinueAfterDenial = { viewModel.onContinueAfterDenial(areNotificationsRuntime) },
        modifier = modifier,
    )
}

@Composable
fun OnboardingScreen(
    state: OnboardingUiState,
    onGetStarted: () -> Unit,
    onVehicleSelected: (VehicleType) -> Unit,
    onVehicleConfirmed: () -> Unit,
    onRequestPermission: () -> Unit,
    onDeclinePermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onContinueAfterDenial: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier.fillMaxSize()) {
        // While settings are saved, the root gate is about to switch to the main shell, so nothing is drawn.
        if (!state.isSaving && !state.isCompleted) {
            OnboardingContent(
                state = state,
                onGetStarted = onGetStarted,
                onVehicleSelected = onVehicleSelected,
                onVehicleConfirmed = onVehicleConfirmed,
                onRequestPermission = onRequestPermission,
                onDeclinePermission = onDeclinePermission,
                onOpenAppSettings = onOpenAppSettings,
                onContinueAfterDenial = onContinueAfterDenial,
            )
        }
    }
}

@Composable
private fun OnboardingContent(
    state: OnboardingUiState,
    onGetStarted: () -> Unit,
    onVehicleSelected: (VehicleType) -> Unit,
    onVehicleConfirmed: () -> Unit,
    onRequestPermission: () -> Unit,
    onDeclinePermission: () -> Unit,
    onOpenAppSettings: () -> Unit,
    onContinueAfterDenial: () -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize().systemBarsPadding()) {
        Column(
            modifier =
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.onboarding_step_progress, state.step.number(), TOTAL_STEPS),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            when (state.step) {
                OnboardingStep.WELCOME -> WelcomeContent()
                OnboardingStep.VEHICLE -> VehicleContent(state.selectedVehicle, onVehicleSelected)
                OnboardingStep.LOCATION, OnboardingStep.NOTIFICATIONS ->
                    PermissionContent(step = state.step, isDenied = state.isPermissionDenied)
            }
        }
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when (state.step) {
                OnboardingStep.WELCOME ->
                    PrimaryButton(text = stringResource(R.string.onboarding_get_started), onClick = onGetStarted)
                OnboardingStep.VEHICLE ->
                    PrimaryButton(
                        text = stringResource(R.string.onboarding_continue),
                        onClick = onVehicleConfirmed,
                        enabled = state.selectedVehicle != null,
                    )
                OnboardingStep.LOCATION, OnboardingStep.NOTIFICATIONS ->
                    if (state.isPermissionDenied) {
                        OutlinedButton(
                            onClick = onOpenAppSettings,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(text = stringResource(R.string.permission_open_settings))
                        }
                        PrimaryButton(text = stringResource(R.string.onboarding_continue), onClick = onContinueAfterDenial)
                    } else {
                        PrimaryButton(text = stringResource(R.string.onboarding_continue), onClick = onRequestPermission)
                        TextButton(
                            onClick = onDeclinePermission,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        ) {
                            Text(text = stringResource(R.string.onboarding_not_now))
                        }
                    }
            }
        }
    }
}

private fun OnboardingStep.number(): Int =
    when (this) {
        OnboardingStep.WELCOME -> 1
        OnboardingStep.VEHICLE -> 2
        OnboardingStep.LOCATION, OnboardingStep.NOTIFICATIONS -> 3
    }

@Composable
private fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
    ) {
        Text(text = text, style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun StepTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleLarge,
        modifier = Modifier.semantics { heading() },
    )
}

@Composable
private fun WelcomeContent() {
    // Decorative illustration placeholder until the visual identity exists (UI_UX_GUIDELINES.md section 14).
    Icon(
        imageVector = Icons.Filled.Place,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(96.dp),
    )
    StepTitle(text = stringResource(R.string.onboarding_welcome_title))
    Text(text = stringResource(R.string.onboarding_welcome_body), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun VehicleContent(
    selected: VehicleType?,
    onSelect: (VehicleType) -> Unit,
) {
    StepTitle(text = stringResource(R.string.onboarding_vehicle_title))
    Text(text = stringResource(R.string.onboarding_vehicle_body), style = MaterialTheme.typography.bodyLarge)
    Column(
        modifier = Modifier.selectableGroup(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        VehicleDisplayOrder.forEach { vehicle ->
            VehicleCard(vehicle = vehicle, isSelected = vehicle == selected, onClick = { onSelect(vehicle) })
        }
    }
}

@Composable
private fun VehicleCard(
    vehicle: VehicleType,
    isSelected: Boolean,
    onClick: () -> Unit,
) {
    val borderColor = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline
    OutlinedCard(
        border = BorderStroke(width = if (isSelected) 3.dp else 1.dp, color = borderColor),
        colors =
            CardDefaults.outlinedCardColors(
                containerColor =
                    if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
            ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 72.dp)
                    .selectable(selected = isSelected, role = Role.RadioButton, onClick = onClick)
                    .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(vehicle.labelRes()),
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.weight(1f),
            )
            // Second cue besides the border color: a check mark on the selected card.
            if (isSelected) {
                Icon(
                    imageVector = Icons.Filled.CheckCircle,
                    contentDescription = stringResource(R.string.common_selected),
                    tint = MaterialTheme.colorScheme.primary,
                )
            } else {
                Spacer(modifier = Modifier.size(24.dp))
            }
        }
    }
}

@Composable
private fun PermissionContent(
    step: OnboardingStep,
    isDenied: Boolean,
) {
    val isLocation = step == OnboardingStep.LOCATION
    val title =
        when {
            isDenied && isLocation -> R.string.permission_location_denied_title
            isDenied -> R.string.permission_notifications_denied_title
            isLocation -> R.string.permission_location_title
            else -> R.string.permission_notifications_title
        }
    val body =
        when {
            isDenied && isLocation -> R.string.permission_location_denied_body
            isDenied -> R.string.permission_notifications_denied_body
            isLocation -> R.string.permission_location_body
            else -> R.string.permission_notifications_body
        }
    StepTitle(text = stringResource(title))
    Text(text = stringResource(body), style = MaterialTheme.typography.bodyLarge)
}

@Composable
private fun OnboardingPreview(state: OnboardingUiState) {
    MapMyRoadTheme {
        OnboardingScreen(
            state = state,
            onGetStarted = {},
            onVehicleSelected = {},
            onVehicleConfirmed = {},
            onRequestPermission = {},
            onDeclinePermission = {},
            onOpenAppSettings = {},
            onContinueAfterDenial = {},
        )
    }
}

@Preview(name = "Welcome", showBackground = true)
@Composable
private fun OnboardingWelcomePreview() {
    OnboardingPreview(OnboardingUiState())
}

@Preview(name = "Vehicle", showBackground = true)
@Preview(name = "Vehicle dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Vehicle 200%", showBackground = true, fontScale = 2f)
@Composable
private fun OnboardingVehiclePreview() {
    OnboardingPreview(OnboardingUiState(step = OnboardingStep.VEHICLE, selectedVehicle = VehicleType.SCOOTER))
}

@Preview(name = "Location rationale", showBackground = true)
@Composable
private fun OnboardingLocationPreview() {
    OnboardingPreview(OnboardingUiState(step = OnboardingStep.LOCATION, selectedVehicle = VehicleType.CAR))
}

@Preview(name = "Location denied", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun OnboardingLocationDeniedPreview() {
    OnboardingPreview(
        OnboardingUiState(step = OnboardingStep.LOCATION, selectedVehicle = VehicleType.CAR, isPermissionDenied = true),
    )
}
