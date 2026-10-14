package com.mapmyroad.sensorlogger.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.mapmyroad.sensorlogger.R
import com.mapmyroad.sensorlogger.core.ElapsedFormat
import com.mapmyroad.sensorlogger.core.TagSeverity
import com.mapmyroad.sensorlogger.core.TagType
import com.mapmyroad.sensorlogger.service.LoggerStatus
import com.mapmyroad.sensorlogger.service.TagRecord

private val tagButtonHeight = 96.dp
private val wideButtonHeight = 80.dp

/**
 * Live screen while recording: stats and very large tag buttons.
 * Debug tool only: passenger tagging preferred (the product's no-touch-while-moving rule still applies).
 */
@Composable
fun RecordingScreen(
    status: LoggerStatus,
    onTag: (TagType, Int) -> Unit,
    onStop: () -> Unit,
) {
    val haptic = LocalHapticFeedback.current
    var isConfirmingStop by rememberSaveable { mutableStateOf(false) }
    val tag: (TagType, Int) -> Unit = { type, severity ->
        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
        onTag(type, severity)
    }
    KeepScreenOn()

    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.recording_elapsed, ElapsedFormat.hms(status.elapsedMs)),
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(text = stringResource(R.string.safety_note), color = MaterialTheme.colorScheme.error)
        StatsBlock(status)
        Text(
            text = status.lastTag?.let { lastTagText(it) } ?: stringResource(R.string.no_tags_yet),
            style = MaterialTheme.typography.titleMedium,
        )

        Text(text = stringResource(R.string.tag_pothole), style = MaterialTheme.typography.titleMedium)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TagButton(
                label = stringResource(R.string.severity_minor),
                height = tagButtonHeight,
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.weight(1f),
                onClick = { tag(TagType.POTHOLE, TagSeverity.MINOR) },
            )
            TagButton(
                label = stringResource(R.string.severity_moderate),
                height = tagButtonHeight,
                containerColor = MaterialTheme.colorScheme.tertiary,
                contentColor = MaterialTheme.colorScheme.onTertiary,
                modifier = Modifier.weight(1f),
                onClick = { tag(TagType.POTHOLE, TagSeverity.MODERATE) },
            )
            TagButton(
                label = stringResource(R.string.severity_severe),
                height = tagButtonHeight,
                containerColor = MaterialTheme.colorScheme.error,
                contentColor = MaterialTheme.colorScheme.onError,
                modifier = Modifier.weight(1f),
                onClick = { tag(TagType.POTHOLE, TagSeverity.SEVERE) },
            )
        }
        TagButton(
            label = stringResource(R.string.tag_speed_breaker),
            height = tagButtonHeight,
            modifier = Modifier.fillMaxWidth(),
            onClick = { tag(TagType.SPEED_BREAKER, TagSeverity.NONE) },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            TagButton(
                label = stringResource(R.string.tag_rough_start),
                height = wideButtonHeight,
                modifier = Modifier.weight(1f),
                onClick = { tag(TagType.ROUGH_PATCH_START, TagSeverity.NONE) },
            )
            TagButton(
                label = stringResource(R.string.tag_rough_end),
                height = wideButtonHeight,
                modifier = Modifier.weight(1f),
                onClick = { tag(TagType.ROUGH_PATCH_END, TagSeverity.NONE) },
            )
        }
        TagButton(
            label = stringResource(R.string.tag_other),
            height = wideButtonHeight,
            modifier = Modifier.fillMaxWidth(),
            onClick = { tag(TagType.OTHER, TagSeverity.NONE) },
        )

        OutlinedButton(
            onClick = { isConfirmingStop = true },
            modifier = Modifier.fillMaxWidth().height(56.dp),
        ) {
            Text(text = stringResource(R.string.stop_recording))
        }
    }

    if (isConfirmingStop) {
        AlertDialog(
            onDismissRequest = { isConfirmingStop = false },
            title = { Text(text = stringResource(R.string.stop_confirm_title)) },
            text = { Text(text = stringResource(R.string.stop_confirm_text)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        isConfirmingStop = false
                        onStop()
                    },
                ) {
                    Text(text = stringResource(R.string.stop_recording))
                }
            },
            dismissButton = {
                TextButton(onClick = { isConfirmingStop = false }) {
                    Text(text = stringResource(R.string.cancel))
                }
            },
        )
    }
}

@Composable
private fun StatsBlock(status: LoggerStatus) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (stream in status.streams) {
            Text(
                text = stringResource(R.string.stream_stat, stream.code, stream.count, stream.rateHz),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (status.missingSensors.isNotEmpty()) {
            Text(
                text = stringResource(R.string.missing_sensors, status.missingSensors.joinToString(", ")),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        val accuracyM = status.lastAccuracyM
        val gpsText =
            if (accuracyM == null) {
                stringResource(R.string.gps_waiting, status.locationCount)
            } else {
                stringResource(
                    R.string.gps_fix,
                    accuracyM,
                    status.lastSpeedKmh ?: 0f,
                    (status.lastFixAgeMs ?: 0L) / 1_000L,
                    status.locationCount,
                )
            }
        Text(text = gpsText, style = MaterialTheme.typography.bodyMedium)
        Text(text = stringResource(R.string.tag_count, status.tagCount), style = MaterialTheme.typography.bodyMedium)
        status.error?.let { Text(text = errorText(it), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable
private fun lastTagText(tag: TagRecord): String {
    val label =
        when (tag.type) {
            TagType.POTHOLE ->
                when (tag.severity) {
                    TagSeverity.MINOR -> stringResource(R.string.label_pothole_minor)
                    TagSeverity.MODERATE -> stringResource(R.string.label_pothole_moderate)
                    TagSeverity.SEVERE -> stringResource(R.string.label_pothole_severe)
                    else -> stringResource(R.string.tag_pothole)
                }

            TagType.SPEED_BREAKER -> stringResource(R.string.tag_speed_breaker)
            TagType.ROUGH_PATCH_START -> stringResource(R.string.tag_rough_start)
            TagType.ROUGH_PATCH_END -> stringResource(R.string.tag_rough_end)
            TagType.OTHER -> stringResource(R.string.tag_other)
        }
    return stringResource(R.string.last_tag, label, ElapsedFormat.hms(tag.tMs))
}

@Composable
private fun TagButton(
    label: String,
    height: Dp,
    modifier: Modifier = Modifier,
    containerColor: Color = MaterialTheme.colorScheme.primary,
    contentColor: Color = MaterialTheme.colorScheme.onPrimary,
    onClick: () -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier.height(height),
        colors = ButtonDefaults.buttonColors(containerColor = containerColor, contentColor = contentColor),
    ) {
        Text(text = label, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
    }
}

/** Keeps the screen on while the recording screen is visible (the power button still turns it off). */
@Composable
private fun KeepScreenOn() {
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
}
