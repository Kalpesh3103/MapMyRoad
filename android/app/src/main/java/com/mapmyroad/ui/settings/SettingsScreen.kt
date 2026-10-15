package com.mapmyroad.ui.settings

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
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
import com.mapmyroad.ui.components.VehicleSelector
import com.mapmyroad.ui.sharing.SharingConsentScreen
import com.mapmyroad.ui.theme.MapMyRoadTheme

/** Alpha for content of rows that are not available (Material disabled content alpha). */
private const val DISABLED_ALPHA = 0.38f

/** Settings (S10), grouped list. */
@Composable
fun SettingsRoute(
    onOpenPrivacy: () -> Unit,
    onOpenBatteryGuidance: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    // The consent is a full-screen state of this route rather than a destination of its own (M5-3).
    if (state.isConsentVisible) {
        BackHandler(onBack = viewModel::onConsentDismissed)
        SharingConsentScreen(
            isContributing = state.isContributionEnabled,
            onAccept = viewModel::onConsentAccepted,
            onDismiss = viewModel::onConsentDismissed,
            modifier = modifier,
        )
    } else {
        SettingsScreen(
            state = state,
            onVehicleSelected = viewModel::onVehicleSelected,
            onAutoPauseChanged = viewModel::onAutoPauseChanged,
            onContributionChanged = viewModel::onContributionChanged,
            onUploadOnMobileDataChanged = viewModel::onUploadOnMobileDataChanged,
            onOpenSharingInfo = viewModel::onOpenSharingInfo,
            onOpenPrivacy = onOpenPrivacy,
            onOpenBatteryGuidance = onOpenBatteryGuidance,
            onOpenAbout = onOpenAbout,
            modifier = modifier,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onVehicleSelected: (VehicleType) -> Unit,
    onAutoPauseChanged: (Boolean) -> Unit,
    onContributionChanged: (Boolean) -> Unit,
    onUploadOnMobileDataChanged: (Boolean) -> Unit,
    onOpenSharingInfo: () -> Unit,
    onOpenPrivacy: () -> Unit,
    onOpenBatteryGuidance: () -> Unit,
    onOpenAbout: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { TopAppBar(title = { Text(text = stringResource(R.string.settings_title)) }) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState()),
        ) {
            SectionHeader(text = stringResource(R.string.settings_section_trips))
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.settings_vehicle)) },
                supportingContent = {
                    Column {
                        Text(text = stringResource(R.string.settings_vehicle_body))
                        VehicleSelector(
                            selected = state.vehicleType,
                            onSelect = onVehicleSelected,
                            enabled = !state.isLoading,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                },
            )
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.settings_auto_pause)) },
                supportingContent = { Text(text = stringResource(R.string.settings_auto_pause_body)) },
                trailingContent = { Switch(checked = state.isAutoPauseEnabled, onCheckedChange = null) },
                modifier =
                    Modifier.toggleable(
                        value = state.isAutoPauseEnabled,
                        enabled = !state.isLoading,
                        role = Role.Switch,
                        onValueChange = onAutoPauseChanged,
                    ),
            )
            HorizontalDivider()

            SectionHeader(text = stringResource(R.string.settings_section_sharing))

            SharingGroup(
                state = state,
                onContributionChanged = onContributionChanged,
                onUploadOnMobileDataChanged = onUploadOnMobileDataChanged,
                onOpenSharingInfo = onOpenSharingInfo,
            )
            HorizontalDivider()

            SectionHeader(text = stringResource(R.string.settings_section_language))
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.settings_language_value)) },
                supportingContent = { Text(text = stringResource(R.string.settings_language_later)) },
            )
            HorizontalDivider()

            SectionHeader(text = stringResource(R.string.settings_section_privacy))
            NavigationRow(
                title = stringResource(R.string.privacy_title),
                body = stringResource(R.string.settings_privacy_body),
                onClick = onOpenPrivacy,
            )
            HorizontalDivider()

            SectionHeader(text = stringResource(R.string.settings_section_help))
            NavigationRow(
                title = stringResource(R.string.battery_title),
                body = stringResource(R.string.settings_battery_body),
                onClick = onOpenBatteryGuidance,
            )
            HorizontalDivider()

            SectionHeader(text = stringResource(R.string.settings_section_about))
            NavigationRow(
                title = stringResource(R.string.about_title),
                body = stringResource(R.string.settings_about_body),
                onClick = onOpenAbout,
            )
        }
    }
}

/** Sharing group (S10, M5-3): contribute, mobile data, and the consent text. */
@Composable
private fun SharingGroup(
    state: SettingsUiState,
    onContributionChanged: (Boolean) -> Unit,
    onUploadOnMobileDataChanged: (Boolean) -> Unit,
    onOpenSharingInfo: () -> Unit,
) {
    val contributeBody =
        if (state.isContributionEnabled) {
            stringResource(R.string.sharing_contribute_body_on)
        } else {
            stringResource(R.string.sharing_contribute_body_off)
        }
    val waitingText =
        when {
            !state.isContributionEnabled || state.waitingTripCount == 0 -> null
            state.isUploadOnMobileData ->
                pluralStringResource(R.plurals.upload_waiting_trips, state.waitingTripCount, state.waitingTripCount)
            else ->
                pluralStringResource(R.plurals.upload_waiting_trips_wifi, state.waitingTripCount, state.waitingTripCount)
        }
    ListItem(
        headlineContent = { Text(text = stringResource(R.string.sharing_contribute)) },
        supportingContent = {
            Column {
                Text(text = contributeBody)
                if (waitingText != null) {
                    Text(text = waitingText, modifier = Modifier.padding(top = 4.dp))
                }
            }
        },
        trailingContent = { Switch(checked = state.isContributionEnabled, onCheckedChange = null) },
        modifier =
            Modifier.toggleable(
                value = state.isContributionEnabled,
                enabled = !state.isLoading,
                role = Role.Switch,
                onValueChange = onContributionChanged,
            ),
    )

    val isMobileDataEnabled = state.isContributionEnabled && !state.isLoading
    val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
    ListItem(
        headlineContent = { Text(text = stringResource(R.string.sharing_mobile_data)) },
        supportingContent = {
            Text(
                text =
                    if (isMobileDataEnabled) {
                        stringResource(R.string.sharing_mobile_data_body)
                    } else {
                        stringResource(R.string.sharing_mobile_data_disabled_body)
                    },
            )
        },
        trailingContent = {
            Switch(checked = state.isUploadOnMobileData, onCheckedChange = null, enabled = isMobileDataEnabled)
        },
        colors =
            if (isMobileDataEnabled) {
                ListItemDefaults.colors()
            } else {
                ListItemDefaults.colors(headlineColor = disabledColor, supportingColor = disabledColor)
            },
        modifier =
            Modifier.toggleable(
                value = state.isUploadOnMobileData,
                enabled = isMobileDataEnabled,
                role = Role.Switch,
                onValueChange = onUploadOnMobileDataChanged,
            ),
    )

    NavigationRow(
        title = stringResource(R.string.sharing_what_is_shared),
        body = stringResource(R.string.sharing_what_is_shared_body),
        onClick = onOpenSharingInfo,
    )
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier =
            Modifier
                .padding(start = 16.dp, end = 16.dp, top = 24.dp, bottom = 4.dp)
                .semantics { heading() },
    )
}

@Composable
private fun NavigationRow(
    title: String,
    body: String,
    onClick: () -> Unit,
) {
    ListItem(
        headlineContent = { Text(text = title) },
        supportingContent = { Text(text = body) },
        trailingContent = { Icon(imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null) },
        modifier = Modifier.clickable(role = Role.Button, onClick = onClick),
    )
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Font 200%", showBackground = true, fontScale = 2f)
@Composable
private fun SettingsScreenPreview() {
    MapMyRoadTheme {
        SettingsScreen(
            state =
                SettingsUiState(
                    isLoading = false,
                    vehicleType = VehicleType.SCOOTER,
                    isAutoPauseEnabled = true,
                    isContributionEnabled = true,
                    waitingTripCount = 2,
                ),
            onVehicleSelected = {},
            onAutoPauseChanged = {},
            onContributionChanged = {},
            onUploadOnMobileDataChanged = {},
            onOpenSharingInfo = {},
            onOpenPrivacy = {},
            onOpenBatteryGuidance = {},
            onOpenAbout = {},
        )
    }
}
