package com.mapmyroad.ui.map

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mapmyroad.R
import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.CommunityHazard
import com.mapmyroad.domain.model.HazardStatus
import com.mapmyroad.map.ConfidenceLevel
import com.mapmyroad.map.HazardAge
import com.mapmyroad.map.TileHazard
import com.mapmyroad.ui.theme.MapMyRoadTheme
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * Hazard detail sheet (S7) for a community hazard (PRD CM-3): severity, confidence level with a one-line
 * explanation, last seen and confirmations. There are no feedback buttons: feedback is collected after a trip that
 * passed the hazard (S12, ADR-0013), never from the map.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun CommunityHazardSheet(
    state: CommunityHazardSheetState,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        CommunityHazardContent(state = state, nowMs = remember(state.tile.id) { System.currentTimeMillis() })
    }
}

@Composable
internal fun CommunityHazardContent(
    state: CommunityHazardSheetState,
    nowMs: Long,
    modifier: Modifier = Modifier,
) {
    val details = state.details
    val severity = details?.severity ?: state.tile.severity
    val confidence = details?.confidence ?: state.tile.confidence
    val status = details?.status ?: state.tile.status
    val seenDays = details?.let { HazardAge.daysSince(it.lastSeenMs, nowMs) } ?: state.tile.seenDays
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SeverityIcon(severity = severity, size = 32.dp)
            Text(
                text = stringResource(severity.labelRes()),
                style = MaterialTheme.typography.titleLarge,
                modifier =
                    Modifier
                        .padding(start = 12.dp)
                        .semantics { heading() },
            )
        }
        Text(
            text = stringResource(R.string.community_hazard_source),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (status != null && status != HazardStatus.ACTIVE) {
            Text(
                text = stringResource(status.labelRes()),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        if (confidence != null) {
            val level = ConfidenceLevel.of(confidence)
            SheetFact(
                label = stringResource(R.string.community_confidence_label),
                value = stringResource(level.labelRes()),
                detail = stringResource(level.explanationRes()),
            )
        }
        if (seenDays != null) {
            SheetFact(label = stringResource(R.string.community_last_seen_label), value = seenText(seenDays))
        }
        if (details != null) {
            SheetFact(label = stringResource(R.string.community_first_seen_label), value = rememberDayText(details.firstSeenMs))
            SheetFact(
                label = stringResource(R.string.community_confirmations_label),
                value = pluralStringResource(R.plurals.community_confirmations, details.confirmCount, details.confirmCount),
            )
        }
        if (state.isLoadingDetails) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                Text(text = stringResource(R.string.community_details_loading), style = MaterialTheme.typography.bodyMedium)
            }
        } else if (state.detailsUnavailable) {
            Text(
                text = stringResource(R.string.community_details_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = stringResource(R.string.community_feedback_after_trip),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun seenText(days: Int): String =
    if (days == 0) {
        stringResource(R.string.community_seen_today)
    } else {
        pluralStringResource(R.plurals.community_seen_days_ago, days, days)
    }

/** Server dates are whole UTC days (SECURITY.md 6.4), so they are formatted in UTC to show the same day. */
@Composable
private fun rememberDayText(epochMs: Long): String {
    val locale = LocalConfiguration.current.locales[0]
    return remember(epochMs, locale) {
        DateTimeFormatter
            .ofLocalizedDate(FormatStyle.MEDIUM)
            .withLocale(locale)
            .format(Instant.ofEpochMilli(epochMs).atZone(ZoneOffset.UTC))
    }
}

@Composable
private fun SheetFact(
    label: String,
    value: String,
    detail: String? = null,
) {
    // One TalkBack node per fact, read as "label, value, detail".
    Column(modifier = Modifier.semantics(mergeDescendants = true) {}) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
        if (detail != null) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

internal fun ConfidenceLevel.labelRes(): Int =
    when (this) {
        ConfidenceLevel.LOW -> R.string.community_confidence_low
        ConfidenceLevel.MEDIUM -> R.string.community_confidence_medium
        ConfidenceLevel.HIGH -> R.string.community_confidence_high
    }

private fun ConfidenceLevel.explanationRes(): Int =
    when (this) {
        ConfidenceLevel.LOW -> R.string.community_confidence_low_explanation
        ConfidenceLevel.MEDIUM -> R.string.community_confidence_medium_explanation
        ConfidenceLevel.HIGH -> R.string.community_confidence_high_explanation
    }

private fun HazardStatus.labelRes(): Int =
    when (this) {
        HazardStatus.ACTIVE -> R.string.community_status_active
        HazardStatus.STALE -> R.string.community_status_stale
        HazardStatus.FIXED -> R.string.community_status_fixed
    }

private const val PREVIEW_NOW_MS = 1_760_000_000_000L

@Preview(name = "Community hazard")
@Preview(name = "Community hazard dark", uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Community hazard 200%", fontScale = 2f)
@Composable
private fun CommunityHazardContentPreview() {
    val tile = TileHazard(id = "preview", severity = Severity.SEVERE, confidence = 0.78, status = HazardStatus.ACTIVE, seenDays = 3)
    MapMyRoadTheme {
        Surface {
            CommunityHazardContent(
                state =
                    CommunityHazardSheetState(
                        tile = tile,
                        details =
                            CommunityHazard(
                                id = "preview",
                                lat = 0.0,
                                lon = 0.0,
                                severity = Severity.SEVERE,
                                confidence = 0.78,
                                status = HazardStatus.ACTIVE,
                                firstSeenMs = PREVIEW_NOW_MS - 40 * HazardAge.DAY_MS,
                                lastSeenMs = PREVIEW_NOW_MS - 3 * HazardAge.DAY_MS,
                                confirmCount = 4,
                                dismissCount = 0,
                            ),
                        isLoadingDetails = false,
                    ),
                nowMs = PREVIEW_NOW_MS,
            )
        }
    }
}

@Preview(name = "Community hazard, tile data only")
@Composable
private fun CommunityHazardTileOnlyPreview() {
    MapMyRoadTheme {
        Surface {
            CommunityHazardContent(
                state =
                    CommunityHazardSheetState(
                        tile = TileHazard(id = "preview", severity = Severity.MODERATE, confidence = 0.35, status = null, seenDays = 0),
                        isLoadingDetails = false,
                        detailsUnavailable = true,
                    ),
                nowMs = PREVIEW_NOW_MS,
            )
        }
    }
}
