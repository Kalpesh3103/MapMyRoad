package com.mapmyroad.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.mapmyroad.R
import com.mapmyroad.detector.VehicleType

/** Order used everywhere vehicles are listed (UI_UX_GUIDELINES.md S1). */
val VehicleDisplayOrder: List<VehicleType> =
    listOf(VehicleType.SCOOTER, VehicleType.MOTORCYCLE, VehicleType.CAR, VehicleType.AUTO_RICKSHAW)

/** Vehicle picker: a button showing the current vehicle that opens a menu (no experimental dropdown box). */
@Composable
fun VehicleSelector(
    selected: VehicleType?,
    onSelect: (VehicleType) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    var isExpanded by remember { mutableStateOf(false) }
    val selectedLabel =
        if (selected != null) stringResource(selected.labelRes()) else stringResource(R.string.vehicle_choose)
    val buttonDescription = stringResource(R.string.vehicle_selector_description, selectedLabel)
    val selectedDescription = stringResource(R.string.common_selected)

    Box(modifier = modifier) {
        OutlinedButton(
            onClick = { isExpanded = true },
            enabled = enabled,
            modifier =
                Modifier
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = buttonDescription },
        ) {
            Text(text = selectedLabel)
            Icon(imageVector = Icons.Filled.ArrowDropDown, contentDescription = null)
        }
        DropdownMenu(expanded = isExpanded, onDismissRequest = { isExpanded = false }) {
            VehicleDisplayOrder.forEach { vehicle ->
                DropdownMenuItem(
                    text = { Text(text = stringResource(vehicle.labelRes())) },
                    onClick = {
                        isExpanded = false
                        onSelect(vehicle)
                    },
                    leadingIcon =
                        if (vehicle == selected) {
                            { Icon(imageVector = Icons.Filled.Check, contentDescription = selectedDescription) }
                        } else {
                            null
                        },
                )
            }
        }
    }
}
