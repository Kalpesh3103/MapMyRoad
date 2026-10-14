package com.mapmyroad.sensorlogger.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.mapmyroad.sensorlogger.R
import com.mapmyroad.sensorlogger.core.PhonePlacement
import com.mapmyroad.sensorlogger.core.VehicleType
import com.mapmyroad.sensorlogger.service.LoggerError
import com.mapmyroad.sensorlogger.service.RecordingConfig

private const val MAX_NOTE_CHARS = 200

/** Session setup: vehicle, placement, note, wake lock option, permissions, start. */
@Composable
fun SetupScreen(
    resumeCount: Int,
    lastError: LoggerError?,
    lastSessionName: String?,
    onStart: (RecordingConfig) -> Unit,
    onOpenSessions: () -> Unit,
) {
    val context = LocalContext.current
    var vehicleCode by rememberSaveable { mutableStateOf(VehicleType.SCOOTER.code) }
    var placementCode by rememberSaveable { mutableStateOf(PhonePlacement.MOUNT.code) }
    var note by rememberSaveable { mutableStateOf("") }
    var keepCpuAwake by rememberSaveable { mutableStateOf(true) }
    var hasAskedPermissions by rememberSaveable { mutableStateOf(false) }
    var permissionResultCount by remember { mutableIntStateOf(0) }

    val hasFineLocation =
        remember(resumeCount, permissionResultCount) { isGranted(context, Manifest.permission.ACCESS_FINE_LOCATION) }
    val hasNotifications =
        remember(resumeCount, permissionResultCount) {
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU || isGranted(context, Manifest.permission.POST_NOTIFICATIONS)
        }
    val permissionLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            hasAskedPermissions = true
            permissionResultCount++
        }

    val vehicle = VehicleType.fromCode(vehicleCode) ?: VehicleType.SCOOTER
    val placement = PhonePlacement.fromCode(placementCode) ?: PhonePlacement.MOUNT

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = stringResource(R.string.setup_title), style = MaterialTheme.typography.headlineSmall)
        Text(text = stringResource(R.string.debug_tool_warning), color = MaterialTheme.colorScheme.error)
        Text(
            text =
                stringResource(
                    R.string.device_info,
                    Build.MANUFACTURER,
                    Build.MODEL,
                    Build.VERSION.RELEASE,
                    Build.VERSION.SDK_INT,
                ),
            style = MaterialTheme.typography.bodyMedium,
        )

        ChoiceGroup(
            title = stringResource(R.string.vehicle_type),
            options = VehicleType.entries,
            selected = vehicle,
            label = { vehicleLabel(it) },
            onSelect = { vehicleCode = it.code },
        )
        ChoiceGroup(
            title = stringResource(R.string.phone_placement),
            options = PhonePlacement.entries,
            selected = placement,
            label = { placementLabel(it) },
            onSelect = { placementCode = it.code },
        )

        OutlinedTextField(
            value = note,
            onValueChange = { note = it.take(MAX_NOTE_CHARS) },
            label = { Text(text = stringResource(R.string.note_label)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(text = stringResource(R.string.keep_cpu_awake), modifier = Modifier.weight(1f))
            Switch(checked = keepCpuAwake, onCheckedChange = { keepCpuAwake = it })
        }
        Text(text = stringResource(R.string.keep_cpu_awake_explanation), style = MaterialTheme.typography.bodySmall)

        PermissionSection(
            hasFineLocation = hasFineLocation,
            hasNotifications = hasNotifications,
            hasAskedPermissions = hasAskedPermissions,
            onRequest = { permissionLauncher.launch(requiredPermissions()) },
            onOpenSettings = { openAppSettings(context) },
        )

        if (lastError != null) {
            Text(text = errorText(lastError), color = MaterialTheme.colorScheme.error)
        }
        if (lastSessionName != null) {
            Text(text = stringResource(R.string.last_session, lastSessionName), style = MaterialTheme.typography.bodySmall)
        }

        Button(
            onClick = { onStart(RecordingConfig(vehicle, placement, note.trim(), keepCpuAwake)) },
            enabled = hasFineLocation,
            modifier = Modifier.fillMaxWidth().height(64.dp),
        ) {
            Text(text = stringResource(R.string.start_recording), style = MaterialTheme.typography.titleLarge)
        }
        OutlinedButton(onClick = onOpenSessions, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.open_sessions))
        }
    }
}

@Composable
private fun PermissionSection(
    hasFineLocation: Boolean,
    hasNotifications: Boolean,
    hasAskedPermissions: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    if (hasFineLocation && hasNotifications) {
        Text(text = stringResource(R.string.permissions_ok), style = MaterialTheme.typography.bodyMedium)
        return
    }
    if (!hasFineLocation) {
        Text(text = stringResource(R.string.permission_location_missing), color = MaterialTheme.colorScheme.error)
    }
    if (!hasNotifications) {
        Text(text = stringResource(R.string.permission_notifications_missing), style = MaterialTheme.typography.bodySmall)
    }
    Button(onClick = onRequest, modifier = Modifier.fillMaxWidth()) {
        Text(text = stringResource(R.string.grant_permissions))
    }
    if (hasAskedPermissions) {
        OutlinedButton(onClick = onOpenSettings, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.open_app_settings))
        }
    }
}

@Composable
private fun <T> ChoiceGroup(
    title: String,
    options: List<T>,
    selected: T,
    label: @Composable (T) -> String,
    onSelect: (T) -> Unit,
) {
    Column(modifier = Modifier.selectableGroup()) {
        Text(text = title, style = MaterialTheme.typography.titleMedium)
        for (option in options) {
            val isSelected = option == selected
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .selectable(selected = isSelected, onClick = { onSelect(option) }, role = Role.RadioButton),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = isSelected, onClick = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = label(option))
            }
        }
    }
}

@Composable
private fun vehicleLabel(vehicle: VehicleType): String =
    when (vehicle) {
        VehicleType.MOTORCYCLE -> stringResource(R.string.vehicle_motorcycle)
        VehicleType.SCOOTER -> stringResource(R.string.vehicle_scooter)
        VehicleType.CAR -> stringResource(R.string.vehicle_car)
        VehicleType.AUTO_RICKSHAW -> stringResource(R.string.vehicle_auto_rickshaw)
    }

@Composable
private fun placementLabel(placement: PhonePlacement): String =
    when (placement) {
        PhonePlacement.MOUNT -> stringResource(R.string.placement_mount)
        PhonePlacement.POCKET -> stringResource(R.string.placement_pocket)
        PhonePlacement.BAG -> stringResource(R.string.placement_bag)
        PhonePlacement.OTHER -> stringResource(R.string.placement_other)
    }

@Composable
internal fun errorText(error: LoggerError): String =
    when (error) {
        LoggerError.STORAGE_UNAVAILABLE -> stringResource(R.string.error_storage)
        LoggerError.FOREGROUND_START_FAILED -> stringResource(R.string.error_foreground)
        LoggerError.LOCATION_PERMISSION_MISSING -> stringResource(R.string.error_location_permission)
        LoggerError.LOCATION_REQUEST_FAILED -> stringResource(R.string.error_location_request)
    }

private fun requiredPermissions(): Array<String> {
    val permissions =
        mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
        )
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        permissions.add(Manifest.permission.POST_NOTIFICATIONS)
    }
    return permissions.toTypedArray()
}

private fun isGranted(
    context: Context,
    permission: String,
): Boolean = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun openAppSettings(context: Context) {
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
    context.startActivity(intent)
}
