package com.mapmyroad.ui.map

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapmyroad.R
import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.ui.theme.MapMyRoadTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import kotlin.math.roundToInt

/**
 * Hazard detail sheet (S7), v1 part: a hazard from the user's own trips. Shows the severity, when it was observed
 * and the speed at detection, with a button to open the trip. Feedback actions arrive in v2.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun HazardDetailSheet(
    hazard: RecordedHazard,
    onDismiss: () -> Unit,
    onViewTrip: (tripId: String) -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        HazardDetailContent(hazard = hazard, onViewTrip = onViewTrip)
    }
}

@Composable
internal fun HazardDetailContent(
    hazard: RecordedHazard,
    onViewTrip: (tripId: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val observedText =
        remember(hazard.observedAtMs, locale) {
            DateTimeFormatter
                .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
                .withLocale(locale)
                .format(Instant.ofEpochMilli(hazard.observedAtMs).atZone(ZoneId.systemDefault()))
        }
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeverityIcon(severity = hazard.severity, size = 32.dp)
            Text(
                text = stringResource(hazard.severity.labelRes()),
                style = MaterialTheme.typography.titleLarge,
                modifier =
                    Modifier
                        .padding(start = 12.dp)
                        .semantics { heading() },
            )
        }
        Text(
            text = stringResource(R.string.map_hazard_source_own),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Fact(label = stringResource(R.string.map_hazard_observed_label), value = observedText)
        Fact(
            label = stringResource(R.string.map_hazard_speed_label),
            value = stringResource(R.string.map_hazard_speed_value, hazard.speedKmh.roundToInt()),
        )
        Button(
            onClick = { onViewTrip(hazard.tripId) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 48.dp),
        ) {
            Text(text = stringResource(R.string.map_hazard_view_trip))
        }
    }
}

@Composable
private fun Fact(
    label: String,
    value: String,
) {
    // One TalkBack node per fact, read as "label, value".
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(name = "Hazard sheet")
@Preview(name = "Hazard sheet dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Hazard sheet 200%", fontScale = 2f)
@Composable
private fun HazardDetailContentPreview() {
    MapMyRoadTheme {
        Surface {
            HazardDetailContent(
                hazard =
                    RecordedHazard(
                        id = "preview-hazard",
                        tripId = "preview-trip",
                        lat = 0.0,
                        lon = 0.0,
                        observedAtMs = 1_760_000_000_000L,
                        severity = Severity.SEVERE,
                        speedKmh = 27.4,
                        peakScore = 3.1,
                        headingDeg = 90,
                    ),
                onViewTrip = {},
            )
        }
    }
}
