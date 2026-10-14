package com.mapmyroad.ui.settings

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapmyroad.R
import com.mapmyroad.ui.components.BackTopAppBar
import com.mapmyroad.ui.components.openBatteryOptimizationSettings
import com.mapmyroad.ui.theme.MapMyRoadTheme

private val BatterySteps =
    listOf(
        R.string.battery_step_1,
        R.string.battery_step_2,
        R.string.battery_step_3,
        R.string.battery_step_4,
    )

/** Battery guidance (PRD PV-5): plain steps and a shortcut to the system battery optimization list. */
@Composable
fun BatteryGuidanceScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    BatteryGuidanceContent(
        onBack = onBack,
        onOpenBatterySettings = { context.openBatteryOptimizationSettings() },
        modifier = modifier,
    )
}

@Composable
private fun BatteryGuidanceContent(
    onBack: () -> Unit,
    onOpenBatterySettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = { BackTopAppBar(title = stringResource(R.string.battery_title), onBack = onBack) },
    ) { innerPadding ->
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(text = stringResource(R.string.battery_intro), style = MaterialTheme.typography.bodyLarge)
            BatterySteps.forEachIndexed { index, stepRes ->
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        text = stringResource(R.string.battery_step_number, index + 1),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(text = stringResource(stepRes), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(text = stringResource(R.string.battery_manufacturer_note), style = MaterialTheme.typography.bodyLarge)
            Button(
                onClick = onOpenBatterySettings,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Text(text = stringResource(R.string.battery_open_settings))
            }
        }
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun BatteryGuidancePreview() {
    MapMyRoadTheme {
        BatteryGuidanceContent(onBack = {}, onOpenBatterySettings = {})
    }
}
