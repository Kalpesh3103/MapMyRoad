package com.mapmyroad.ui.trips

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

/** Trips list (S5). Empty state until trips are stored (M2-7, M2-12). */
@Composable
fun TripsScreen(modifier: Modifier = Modifier) {
    Column(
        modifier =
            modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp, vertical = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(text = stringResource(R.string.trips_title), style = MaterialTheme.typography.titleLarge)
        Text(text = stringResource(R.string.trips_empty), style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(name = "Light", showBackground = true)
@Preview(name = "Dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun TripsScreenPreview() {
    MapMyRoadTheme {
        Surface {
            TripsScreen()
        }
    }
}
