package com.mapmyroad.sensorlogger.service

import com.mapmyroad.sensorlogger.core.PhonePlacement
import com.mapmyroad.sensorlogger.core.TagType
import com.mapmyroad.sensorlogger.core.VehicleType
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Settings chosen on the setup screen and passed to [LoggerService] in the start Intent. */
data class RecordingConfig(
    val vehicle: VehicleType,
    val placement: PhonePlacement,
    val note: String,
    val keepCpuAwake: Boolean,
)

/** Count and approximate rate of one stream over the last status tick. */
data class StreamStatus(
    val code: String,
    val count: Long,
    val rateHz: Double,
)

/** The last tag written to tags.csv. [severity] is 0 when not applicable. */
data class TagRecord(
    val type: TagType,
    val severity: Int,
    val tMs: Long,
)

/** Problems the service reports to the UI. Mapped to strings in the UI layer. */
enum class LoggerError {
    STORAGE_UNAVAILABLE,
    FOREGROUND_START_FAILED,
    LOCATION_PERMISSION_MISSING,
    LOCATION_REQUEST_FAILED,
}

/** Snapshot published by the service about once per second. Immutable. */
data class LoggerStatus(
    val isRecording: Boolean = false,
    val sessionName: String? = null,
    val elapsedMs: Long = 0L,
    val streams: List<StreamStatus> = emptyList(),
    val missingSensors: List<String> = emptyList(),
    val locationCount: Long = 0L,
    val tagCount: Long = 0L,
    val lastAccuracyM: Float? = null,
    val lastSpeedKmh: Float? = null,
    val lastFixAgeMs: Long? = null,
    val lastTag: TagRecord? = null,
    val error: LoggerError? = null,
)

/**
 * Process-wide status holder shared by [LoggerService] (writer) and the UI (reader).
 *
 * Threading: written from the service's IO thread and the main thread; MutableStateFlow is
 * thread-safe. The UI collects with collectAsStateWithLifecycle, so collection stops when hidden.
 */
object LoggerState {
    private val _status = MutableStateFlow(LoggerStatus())
    val status: StateFlow<LoggerStatus> = _status.asStateFlow()

    internal fun publish(status: LoggerStatus) {
        _status.value = status
    }
}
