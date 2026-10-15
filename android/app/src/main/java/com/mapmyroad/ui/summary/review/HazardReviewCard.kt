package com.mapmyroad.ui.summary.review

import android.content.res.Configuration
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.R
import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.model.HazardFeedbackAction
import com.mapmyroad.ui.format.TripFormat
import com.mapmyroad.ui.map.SeverityIcon
import com.mapmyroad.ui.map.labelRes
import com.mapmyroad.ui.theme.MapMyRoadTheme

/**
 * Post-trip hazard review (S12) for the trip summary. Shows nothing unless there is something to review (see
 * [HazardReviewViewModel]). This screen is never shown during a live trip, and the card hides itself while one is
 * recording (UI_UX_GUIDELINES.md section 6).
 */
@Composable
fun HazardReviewSection(
    tripId: String,
    modifier: Modifier = Modifier,
) {
    val viewModel: HazardReviewViewModel = hiltViewModel(key = "hazard-review-$tripId")
    LaunchedEffect(viewModel, tripId) { viewModel.setTripId(tripId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val ready = state as? HazardReviewUiState.Ready ?: return
    HazardReviewCard(
        state = ready,
        onAnswer = viewModel::onAnswer,
        onSkipAll = viewModel::onSkipAll,
        modifier = modifier,
    )
}

@Composable
fun HazardReviewCard(
    state: HazardReviewUiState.Ready,
    onAnswer: (hazardId: String, action: HazardFeedbackAction) -> Unit,
    onSkipAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(modifier = modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(
                text = pluralStringResource(R.plurals.community_review_title, state.rows.size, state.rows.size),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.semantics { heading() },
            )
            Text(
                text = stringResource(R.string.community_review_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.rows.forEachIndexed { index, row ->
                if (index > 0) HorizontalDivider()
                ReviewRow(row = row, onAnswer = { action -> onAnswer(row.hazardId, action) })
            }
            if (state.isComplete) {
                Text(
                    text = stringResource(R.string.community_review_thanks),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            } else {
                TextButton(
                    onClick = onSkipAll,
                    modifier =
                        Modifier
                            .align(Alignment.End)
                            .heightIn(min = 48.dp),
                ) {
                    Text(text = stringResource(R.string.community_review_skip_all))
                }
            }
        }
    }
}

@Composable
private fun ReviewRow(
    row: HazardReviewRow,
    onAnswer: (HazardFeedbackAction) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val severityText = stringResource(row.severity.labelRes())
    val distanceText = stringResource(R.string.community_review_at_distance, TripFormat.distanceKm(row.alongTrackM, locale))
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.semantics(mergeDescendants = true) {},
        ) {
            SeverityIcon(severity = row.severity, size = 24.dp)
            Column {
                Text(text = severityText, style = MaterialTheme.typography.bodyLarge)
                Text(
                    text = distanceText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        when (val status = row.status) {
            is ReviewRowStatus.Sent -> SentLine(action = status.action)
            ReviewRowStatus.Rejected ->
                Text(
                    text = stringResource(R.string.community_review_rejected),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                )
            ReviewRowStatus.Pending, is ReviewRowStatus.Sending, is ReviewRowStatus.Failed -> {
                AnswerButtons(
                    enabled = status !is ReviewRowStatus.Sending,
                    hazardText = stringResource(R.string.community_review_hazard_description, severityText, distanceText),
                    onAnswer = onAnswer,
                )
                if (status is ReviewRowStatus.Failed) {
                    Text(
                        text = stringResource(R.string.community_review_failed),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                    )
                }
            }
        }
    }
}

@Composable
private fun AnswerButtons(
    enabled: Boolean,
    hazardText: String,
    onAnswer: (HazardFeedbackAction) -> Unit,
) {
    // Every row has the same two buttons, so TalkBack also hears which hazard each one answers.
    val stillThereText = stringResource(R.string.community_review_still_there)
    val fixedText = stringResource(R.string.community_review_fixed)
    val stillThereDescription = stringResource(R.string.community_review_answer_description, stillThereText, hazardText)
    val fixedDescription = stringResource(R.string.community_review_answer_description, fixedText, hazardText)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        OutlinedButton(
            onClick = { onAnswer(HazardFeedbackAction.CONFIRM) },
            enabled = enabled,
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = stillThereDescription },
        ) {
            Text(text = stillThereText)
        }
        OutlinedButton(
            onClick = { onAnswer(HazardFeedbackAction.DISMISS) },
            enabled = enabled,
            modifier =
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .semantics { contentDescription = fixedDescription },
        ) {
            Text(text = fixedText)
        }
    }
}

@Composable
private fun SentLine(action: HazardFeedbackAction) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.semantics(mergeDescendants = true) { liveRegion = LiveRegionMode.Polite },
    ) {
        Icon(imageVector = Icons.Filled.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
        Text(
            text =
                stringResource(
                    when (action) {
                        HazardFeedbackAction.CONFIRM -> R.string.community_review_sent_still_there
                        HazardFeedbackAction.DISMISS -> R.string.community_review_sent_fixed
                    },
                ),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Preview(name = "Review", showBackground = true)
@Preview(name = "Review dark", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Review 200%", showBackground = true, fontScale = 2f)
@Composable
private fun HazardReviewCardPreview() {
    MapMyRoadTheme {
        Surface {
            HazardReviewCard(
                state =
                    HazardReviewUiState.Ready(
                        rows =
                            listOf(
                                HazardReviewRow("a", Severity.SEVERE, 2_300.0, ReviewRowStatus.Pending),
                                HazardReviewRow("b", Severity.MODERATE, 4_100.0, ReviewRowStatus.Sent(HazardFeedbackAction.CONFIRM)),
                                HazardReviewRow("c", Severity.MINOR, 6_800.0, ReviewRowStatus.Failed(HazardFeedbackAction.DISMISS)),
                            ),
                    ),
                onAnswer = { _, _ -> },
                onSkipAll = {},
            )
        }
    }
}
