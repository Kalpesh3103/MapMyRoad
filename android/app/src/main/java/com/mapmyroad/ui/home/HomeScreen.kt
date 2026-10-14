package com.mapmyroad.ui.home

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapmyroad.R
import com.mapmyroad.ui.theme.MapMyRoadTheme

/** Home (S2). Placeholder until the tracking service and live trip view exist. */
@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.home_title), style = MaterialTheme.typography.titleLarge)
        Text(text = stringResource(R.string.home_body), style = MaterialTheme.typography.bodyLarge)
        // weight() has no effect inside verticalScroll, so a fixed gap is used. Layout is revisited in M2-10.
        Spacer(modifier = Modifier.height(24.dp))
        // Primary action, 64 dp or taller and in the lower third (UI_UX_GUIDELINES.md section 4.5).
        // TODO(M2-10): enable once TripTrackingService (M2-2) exists.
        Button(
            onClick = {},
            enabled = false,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp),
        ) {
            Text(text = stringResource(R.string.home_start_trip), style = MaterialTheme.typography.titleLarge)
        }
        Text(
            text = stringResource(R.string.home_start_trip_unavailable),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Font 200%", showBackground = true, fontScale = 2f)
@Composable
private fun HomeScreenPreview() {
    MapMyRoadTheme {
        Surface {
            HomeScreen()
        }
    }
}
