package com.mapmyroad.ui.live

import android.content.res.Configuration
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.mapmyroad.R
import com.mapmyroad.detector.Severity
import com.mapmyroad.domain.session.GpsStatus
import com.mapmyroad.domain.session.PauseReason
import com.mapmyroad.ui.components.SeverityMarker
import com.mapmyroad.ui.format.TripFormat
import com.mapmyroad.ui.theme.MapMyRoadTheme
import kotlinx.coroutines.delay

private const val CLOCK_TICK_MS = 1_000L

/**
 * Live trip (S3), designed for a one-second glance. Always dark. No map, no scrolling, no text under 16 sp and
 * never a dialog, toast or prompt (AGENTS.md section 6). Stop needs a long press so a bump or a pocket cannot
 * end the trip by accident; the notification also carries Stop.
 */
@Composable
fun LiveTripRoute(
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LiveTripViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var nowMs by remember { mutableLongStateOf(System.currentTimeMillis()) }

    // Ticks the elapsed clock once per second, only while the screen is visible.
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                nowMs = System.currentTimeMillis()
                delay(CLOCK_TICK_MS)
            }
        }
    }

    LaunchedEffect(state) {
        if (state is LiveTripUiState.NotRecording) onClose()
    }

    MapMyRoadTheme(darkTheme = true) {
        LiveTripScreen(
            state = state,
            nowMs = nowMs,
            onPause = viewModel::onPause,
            onResume = viewModel::onResume,
            onStop = {
                // Haptic tick on trip stop (UI_UX_GUIDELINES.md S3).
                haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                viewModel.onStop()
            },
            modifier = modifier,
        )
    }
}

@Composable
fun LiveTripScreen(
    state: LiveTripUiState,
    nowMs: Long,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val locale = LocalConfiguration.current.locales[0]
    val recording = state as? LiveTripUiState.Recording
    val elapsedText =
        TripFormat.duration(
            durationS = if (recording != null) TripFormat.elapsedS(recording.startedAtMs, nowMs) else 0L,
            locale = locale,
        )
    val distanceText = TripFormat.distanceKm(recording?.distanceM ?: 0.0, locale)

    Surface(modifier = modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .systemBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (recording != null && recording.pauseReason == null) {
                    // Recording indicator: static, the only motion-free visual on this screen besides text.
                    Box(
                        modifier =
                            Modifier
                                .size(16.dp)
                                .background(color = MaterialTheme.colorScheme.error, shape = CircleShape),
                    )
                }
                Text(
                    text = stringResource(state.stateLineRes()),
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.weight(1f).semantics { heading() },
                )
                Text(text = elapsedText, style = MaterialTheme.typography.titleLarge)
            }
            Spacer(modifier = Modifier.size(24.dp))
            Text(
                text = stringResource(R.string.common_distance_km, distanceText),
                style = MaterialTheme.typography.displaySmall,
            )
            Spacer(modifier = Modifier.size(24.dp))
            Text(text = stringResource(R.string.live_bumps_label), style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.size(8.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                BumpCount(Severity.MINOR, R.string.live_bumps_minor, recording?.minorCount ?: 0, Modifier.weight(1f))
                BumpCount(Severity.MODERATE, R.string.live_bumps_rough, recording?.moderateCount ?: 0, Modifier.weight(1f))
                BumpCount(Severity.SEVERE, R.string.live_bumps_severe, recording?.severeCount ?: 0, Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.size(16.dp))
            if (recording != null) {
                Text(text = stringResource(recording.gpsStatus.labelRes()), style = MaterialTheme.typography.bodyLarge)
            }
            Spacer(modifier = Modifier.weight(1f))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val isPaused = recording?.pauseReason != null
                OutlinedButton(
                    onClick = if (isPaused) onResume else onPause,
                    enabled = recording != null,
                    modifier = Modifier.weight(1f).heightIn(min = 72.dp),
                ) {
                    Text(
                        text = stringResource(if (isPaused) R.string.live_resume else R.string.live_pause),
                        style = MaterialTheme.typography.titleLarge,
                    )
                }
                StopButton(enabled = recording != null, onStop = onStop, modifier = Modifier.weight(1f))
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun StopButton(
    enabled: Boolean,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val shape = RoundedCornerShape(28.dp)
    val stopLabel = stringResource(R.string.live_stop_action)
    val containerColor =
        if (enabled) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f)
    val contentColor =
        if (enabled) MaterialTheme.colorScheme.onError else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = shape,
        modifier =
            modifier
                .heightIn(min = 72.dp)
                .clip(shape)
                .combinedClickable(
                    enabled = enabled,
                    role = Role.Button,
                    onClickLabel = stopLabel,
                    onLongClickLabel = stopLabel,
                    onLongClick = onStop,
                    // A short tap does nothing; the visible "Hold to stop" label explains why.
                    onClick = {},
                ),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                text = stringResource(R.string.live_stop),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Text(
                text = stringResource(R.string.live_hold_to_stop),
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun BumpCount(
    severity: Severity,
    labelRes: Int,
    count: Int,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SeverityMarker(severity = severity, size = 20.dp)
            Text(text = count.toString(), style = MaterialTheme.typography.titleLarge)
        }
        // Text label next to the shape, never color alone.
        Text(text = stringResource(labelRes), style = MaterialTheme.typography.bodyLarge)
    }
}

private fun LiveTripUiState.stateLineRes(): Int =
    when (this) {
        LiveTripUiState.NotRecording -> R.string.live_state_not_recording
        LiveTripUiState.Finishing -> R.string.live_state_saving
        is LiveTripUiState.Recording ->
            when {
                pauseReason == PauseReason.STATIONARY -> R.string.live_state_paused_stationary
                pauseReason == PauseReason.MANUAL -> R.string.live_state_paused
                // Weak GPS also shows "Waiting for GPS": no events are recorded without a good fix (UI_UX section 8).
                gpsStatus != GpsStatus.GOOD -> R.string.live_state_waiting_gps
                else -> R.string.live_state_recording
            }
    }

private fun GpsStatus.labelRes(): Int =
    when (this) {
        GpsStatus.WAITING -> R.string.live_gps_waiting
        GpsStatus.GOOD -> R.string.live_gps_good
        GpsStatus.WEAK -> R.string.live_gps_weak
    }

private val previewRecording =
    LiveTripUiState.Recording(
        startedAtMs = 0L,
        distanceM = 12_400.0,
        minorCount = 5,
        moderateCount = 2,
        severeCount = 0,
        gpsStatus = GpsStatus.GOOD,
        pauseReason = null,
    )

@Preview(name = "Recording", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Preview(name = "Recording light system", showBackground = true)
@Preview(name = "Font 200%", showBackground = true, fontScale = 2f)
@Composable
private fun LiveTripRecordingPreview() {
    MapMyRoadTheme(darkTheme = true) {
        LiveTripScreen(state = previewRecording, nowMs = 1_421_000L, onPause = {}, onResume = {}, onStop = {})
    }
}

@Preview(name = "Paused (stopped)", showBackground = true, uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun LiveTripPausedPreview() {
    MapMyRoadTheme(darkTheme = true) {
        LiveTripScreen(
            state = previewRecording.copy(pauseReason = PauseReason.STATIONARY, gpsStatus = GpsStatus.WEAK),
            nowMs = 3_725_000L,
            onPause = {},
            onResume = {},
            onStop = {},
        )
    }
}
