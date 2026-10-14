package com.mapmyroad.ui.settings

import android.content.res.Configuration
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.ui.components.VehicleSelector
import com.mapmyroad.ui.theme.MapMyRoadTheme

/** Alpha for content of rows that are not available yet (Material disabled content alpha). */
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
    SettingsScreen(
        state = state,
        onVehicleSelected = viewModel::onVehicleSelected,
        onAutoPauseChanged = viewModel::onAutoPauseChanged,
        onOpenPrivacy = onOpenPrivacy,
        onOpenBatteryGuidance = onOpenBatteryGuidance,
        onOpenAbout = onOpenAbout,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    onVehicleSelected: (VehicleType) -> Unit,
    onAutoPauseChanged: (Boolean) -> Unit,
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

            val disabledColor = MaterialTheme.colorScheme.onSurface.copy(alpha = DISABLED_ALPHA)
            ListItem(
                headlineContent = { Text(text = stringResource(R.string.settings_sharing)) },
                supportingContent = { Text(text = stringResource(R.string.settings_sharing_later)) },
                colors = ListItemDefaults.colors(headlineColor = disabledColor, supportingColor = disabledColor),
                modifier = Modifier.semantics { disabled() },
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
            state = SettingsUiState(isLoading = false, vehicleType = VehicleType.SCOOTER, isAutoPauseEnabled = true),
            onVehicleSelected = {},
            onAutoPauseChanged = {},
            onOpenPrivacy = {},
            onOpenBatteryGuidance = {},
            onOpenAbout = {},
        )
    }
}
