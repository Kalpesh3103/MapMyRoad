package com.mapmyroad.sensorlogger.ui

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.mapmyroad.sensorlogger.core.TagType
import com.mapmyroad.sensorlogger.service.LoggerService
import com.mapmyroad.sensorlogger.service.LoggerState

/**
 * Root composable: shows the recording screen while the service records, otherwise the setup
 * screen or the sessions list. The service is the source of truth via [LoggerState].
 */
@Composable
fun LoggerApp(resumeCount: Int) {
    val context = LocalContext.current
    val status by LoggerState.status.collectAsStateWithLifecycle()
    var isShowingSessions by rememberSaveable { mutableStateOf(false) }

    Scaffold { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            when {
                status.isRecording ->
                    RecordingScreen(
                        status = status,
                        onTag = { type, severity -> sendTag(context, type, severity) },
                        onStop = { sendToService(context, LoggerService.stopIntent(context)) },
                    )

                isShowingSessions -> SessionsScreen(onBack = { isShowingSessions = false })

                else ->
                    SetupScreen(
                        resumeCount = resumeCount,
                        lastError = status.error,
                        lastSessionName = status.sessionName,
                        onStart = { config ->
                            ContextCompat.startForegroundService(context, LoggerService.startIntent(context, config))
                        },
                        onOpenSessions = { isShowingSessions = true },
                    )
            }
        }
    }
}

private fun sendTag(
    context: Context,
    type: TagType,
    severity: Int,
) {
    // Capture the tap time here so Intent delivery delay does not shift the tag.
    val intent = LoggerService.tagIntent(context, type, severity, SystemClock.elapsedRealtimeNanos(), System.currentTimeMillis())
    sendToService(context, intent)
}

private fun sendToService(
    context: Context,
    intent: Intent,
) {
    try {
        // The Activity is visible when the user taps, so a plain startService is allowed.
        context.startService(intent)
    } catch (e: IllegalStateException) {
        Log.w("MmrLoggerApp", "Cannot deliver action to service", e)
    }
}
