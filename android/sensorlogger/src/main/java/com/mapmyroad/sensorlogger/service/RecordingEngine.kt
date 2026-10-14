package com.mapmyroad.sensorlogger.service

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Build
import android.os.Handler
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mapmyroad.sensorlogger.BuildConfig
import com.mapmyroad.sensorlogger.core.CsvFormat
import com.mapmyroad.sensorlogger.core.CsvStreamWriter
import com.mapmyroad.sensorlogger.core.SensorStream
import com.mapmyroad.sensorlogger.core.SessionNaming
import com.mapmyroad.sensorlogger.core.TagSeverity
import com.mapmyroad.sensorlogger.core.TagType
import com.mapmyroad.sensorlogger.core.TimeBase
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * One recording session: registers sensors and location updates, writes sensors.csv,
 * location.csv, tags.csv and meta.json, and publishes [LoggerStatus] once per second.
 *
 * Threading model (CODING_STANDARDS.md section 3.6):
 * - Every method of this class runs on the logger IO HandlerThread owned by [LoggerService]
 *   ([ioHandler]). Sensors are registered with that Handler and location updates with its Looper,
 *   so their callbacks arrive on the same thread. The service posts start, tags and stop to it.
 * - All mutable state is therefore confined to one thread and needs no locks. The only exception
 *   is [isLocationRequestFailed], written by a Play services Task listener (main thread): @Volatile.
 * - Hot path (onSensorChanged): no logging, no String.format, rows are built in one reused
 *   StringBuilder per stream and copied into the 64 KB buffered writer without allocation
 *   (Float-to-text conversion inside StringBuilder may still allocate small temporaries).
 * - Files are flushed every [FLUSH_EVERY_TICKS] seconds, so a killed process keeps partial data.
 */
class RecordingEngine(
    context: Context,
    private val config: RecordingConfig,
    private val ioHandler: Handler,
    private val onFatalError: (LoggerError) -> Unit,
) : SensorEventListener {
    private val appContext: Context = context.applicationContext
    private val sensorManager: SensorManager? = appContext.getSystemService(SensorManager::class.java)
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(appContext)

    private val streamCount = SensorStream.entries.size
    private val streamCodes = Array(streamCount) { SensorStream.entries[it].code }
    private val sensorTypes =
        IntArray(streamCount) {
            when (SensorStream.entries[it]) {
                SensorStream.ACC -> Sensor.TYPE_ACCELEROMETER
                SensorStream.GYRO -> Sensor.TYPE_GYROSCOPE
                SensorStream.GRAV -> Sensor.TYPE_GRAVITY
                SensorStream.ROT -> Sensor.TYPE_GAME_ROTATION_VECTOR
            }
        }
    private val sensors = arrayOfNulls<Sensor>(streamCount)
    private val isRegistered = BooleanArray(streamCount)
    private val sensorCounts = LongArray(streamCount)
    private val previousCounts = LongArray(streamCount)
    private val firstEventLagMs = DoubleArray(streamCount) { Double.NaN }

    private val sensorRow = StringBuilder(ROW_CAPACITY)
    private val locationRow = StringBuilder(ROW_CAPACITY)
    private val tagRow = StringBuilder(ROW_CAPACITY)

    private var sensorsWriter: CsvStreamWriter? = null
    private var locationWriter: CsvStreamWriter? = null
    private var tagsWriter: CsvStreamWriter? = null
    private var sessionDir: File? = null
    private var sessionName: String? = null

    private var startElapsedNanos = 0L
    private var startWallMs = 0L
    private var previousTickElapsedNanos = 0L
    private var tickCount = 0L
    private var droppedSensorEvents = 0L
    private var droppedLocations = 0L
    private var lastAccuracyM: Float? = null
    private var lastSpeedMps: Float? = null
    private var lastFixElapsedNanos = 0L
    private var lastTag: TagRecord? = null
    private var isStarted = false
    private var isStopped = false
    private var hasReportedFatal = false

    @Volatile
    private var isLocationRequestFailed = false

    private val locationCallback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) {
                    onLocation(location)
                }
            }
        }

    private val ticker =
        object : Runnable {
            override fun run() {
                if (isStopped) return
                tickCount++
                publishStatus()
                if (tickCount % FLUSH_EVERY_TICKS == 0L) {
                    flushWriters()
                    writeMeta(isComplete = false, endWallMs = null)
                }
                ioHandler.postDelayed(this, TICK_MS)
            }
        }

    /** Opens the session files and starts sensors and location. Reports problems via [onFatalError]. */
    fun start() {
        if (isStarted || isStopped) return
        isStarted = true
        if (!hasFineLocationPermission()) {
            reportFatal(LoggerError.LOCATION_PERMISSION_MISSING)
            return
        }
        startWallMs = System.currentTimeMillis()
        startElapsedNanos = SystemClock.elapsedRealtimeNanos()
        previousTickElapsedNanos = startElapsedNanos

        val root = appContext.getExternalFilesDir(null)
        if (root == null) {
            reportFatal(LoggerError.STORAGE_UNAVAILABLE)
            return
        }
        try {
            val dir = createSessionDir(File(root, SessionNaming.SESSIONS_DIR))
            sensorsWriter = CsvStreamWriter(File(dir, SessionNaming.SENSORS_FILE), CsvFormat.SENSORS_HEADER)
            locationWriter = CsvStreamWriter(File(dir, SessionNaming.LOCATION_FILE), CsvFormat.LOCATION_HEADER)
            tagsWriter = CsvStreamWriter(File(dir, SessionNaming.TAGS_FILE), CsvFormat.TAGS_HEADER)
            sessionDir = dir
            sessionName = dir.name
        } catch (e: IOException) {
            Log.w(TAG, "Cannot create session files", e)
            closeWriters()
            reportFatal(LoggerError.STORAGE_UNAVAILABLE)
            return
        }

        registerSensors()
        if (!requestLocationUpdates()) {
            reportFatal(LoggerError.LOCATION_PERMISSION_MISSING)
            return
        }
        writeMeta(isComplete = false, endWallMs = null)
        publishStatus()
        ioHandler.postDelayed(ticker, TICK_MS)
    }

    /** Writes one tag. [tapElapsedNanos] is SystemClock.elapsedRealtimeNanos() taken at the tap. */
    fun recordTag(
        type: TagType,
        severity: Int,
        tapElapsedNanos: Long,
        tapWallMs: Long,
    ) {
        if (isStopped) return
        val writer = tagsWriter ?: return
        val tUs = TimeBase.toSessionUs(tapElapsedNanos, startElapsedNanos)
        if (tUs < 0L || !TagSeverity.isValid(severity)) return
        tagRow.setLength(0)
        CsvFormat.appendTagRow(tagRow, tUs, tapWallMs, type.code, severity)
        if (!safeWrite(writer, tagRow)) return
        // Tags are rare and are the ground truth for calibration: flush immediately.
        try {
            writer.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Tag flush failed", e)
        }
        lastTag = TagRecord(type, severity, tUs / MICROS_PER_MILLI)
        publishStatus()
    }

    /** Stops sensors and location, closes files, writes the final meta.json. Idempotent. */
    fun stop(error: LoggerError?) {
        if (isStopped) return
        isStopped = true
        ioHandler.removeCallbacks(ticker)
        sensorManager?.unregisterListener(this)
        fusedClient.removeLocationUpdates(locationCallback)
        closeWriters()
        if (sessionDir != null) writeMeta(isComplete = true, endWallMs = System.currentTimeMillis())
        LoggerState.publish(LoggerStatus(isRecording = false, sessionName = sessionName, error = error))
    }

    override fun onSensorChanged(event: SensorEvent?) {
        if (event == null || isStopped) return
        val writer = sensorsWriter ?: return
        val index = streamIndexForType(event.sensor.type)
        if (index < 0) return
        val tUs = TimeBase.toSessionUs(event.timestamp, startElapsedNanos)
        if (tUs < 0L) {
            droppedSensorEvents++
            return
        }
        if (firstEventLagMs[index].isNaN()) {
            // Sanity check of the shared elapsed-realtime assumption (see TimeBase): should be small.
            firstEventLagMs[index] = (SystemClock.elapsedRealtimeNanos() - event.timestamp) / NANOS_PER_MILLI_D
        }
        val values = event.values
        if (values.size < 3) return
        val w = if (index == SensorStream.ROT.ordinal && values.size > 3) values[3] else Float.NaN
        sensorRow.setLength(0)
        CsvFormat.appendSensorRow(sensorRow, tUs, streamCodes[index], values[0], values[1], values[2], w)
        if (safeWrite(writer, sensorRow)) sensorCounts[index]++
    }

    override fun onAccuracyChanged(
        sensor: Sensor?,
        accuracy: Int,
    ) {
        // Not recorded: calibration status changes are rare and not used by the analysis.
    }

    private fun onLocation(location: Location) {
        if (isStopped) return
        val writer = locationWriter ?: return
        val tUs = TimeBase.toSessionUs(location.elapsedRealtimeNanos, startElapsedNanos)
        if (tUs < 0L) {
            // Cached fix from before the session started.
            droppedLocations++
            return
        }
        val speedMps = if (location.hasSpeed()) location.speed else Float.NaN
        val accuracyM = if (location.hasAccuracy()) location.accuracy else Float.NaN
        val bearingDeg = if (location.hasBearing()) location.bearing else Float.NaN
        val altitudeM = if (location.hasAltitude()) location.altitude else Double.NaN
        locationRow.setLength(0)
        CsvFormat.appendLocationRow(
            locationRow,
            tUs,
            location.time,
            location.latitude,
            location.longitude,
            speedMps,
            accuracyM,
            bearingDeg,
            altitudeM,
        )
        safeWrite(writer, locationRow)
        lastAccuracyM = if (location.hasAccuracy()) location.accuracy else null
        lastSpeedMps = if (location.hasSpeed()) location.speed else null
        lastFixElapsedNanos = location.elapsedRealtimeNanos
    }

    private fun registerSensors() {
        val manager = sensorManager ?: return
        for (i in 0 until streamCount) {
            val sensor = manager.getDefaultSensor(sensorTypes[i]) ?: continue
            sensors[i] = sensor
            // 10_000 us = 100 Hz, explicitly not SENSOR_DELAY_FASTEST: stays under the 200 Hz cap that
            // would need HIGH_SAMPLING_RATE_SENSORS (ARCHITECTURE.md section 5 step 1).
            // maxReportLatencyUs = 0: the logger favors fidelity and simple timing over battery. The
            // product app batches instead (ARCHITECTURE.md section 4.2).
            isRegistered[i] = manager.registerListener(this, sensor, SAMPLING_PERIOD_US, MAX_REPORT_LATENCY_US, ioHandler)
        }
    }

    @SuppressLint("MissingPermission") // Checked by hasFineLocationPermission() right before the request.
    private fun requestLocationUpdates(): Boolean {
        if (!hasFineLocationPermission()) return false
        val request =
            LocationRequest
                .Builder(Priority.PRIORITY_HIGH_ACCURACY, LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(LOCATION_INTERVAL_MS)
                .build()
        return try {
            fusedClient
                .requestLocationUpdates(request, locationCallback, ioHandler.looper)
                .addOnFailureListener { e ->
                    Log.w(TAG, "Location request failed", e)
                    isLocationRequestFailed = true
                }
            true
        } catch (e: SecurityException) {
            Log.w(TAG, "Location permission revoked", e)
            false
        }
    }

    private fun hasFineLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    private fun streamIndexForType(sensorType: Int): Int {
        for (i in 0 until streamCount) {
            if (sensorTypes[i] == sensorType) return i
        }
        return -1
    }

    private fun safeWrite(
        writer: CsvStreamWriter,
        row: StringBuilder,
    ): Boolean =
        try {
            writer.writeRow(row)
            true
        } catch (e: IOException) {
            Log.w(TAG, "Write failed", e)
            reportFatal(LoggerError.STORAGE_UNAVAILABLE)
            false
        }

    private fun reportFatal(error: LoggerError) {
        if (hasReportedFatal) return
        hasReportedFatal = true
        onFatalError(error)
    }

    @Throws(IOException::class)
    private fun createSessionDir(sessionsRoot: File): File {
        val baseName = SessionNaming.directoryName(startWallMs, config.vehicle, config.placement)
        var dir = File(sessionsRoot, baseName)
        var suffix = 2
        while (dir.exists()) {
            dir = File(sessionsRoot, "${baseName}_$suffix")
            suffix++
        }
        if (!dir.mkdirs()) throw IOException("Cannot create session directory")
        return dir
    }

    private fun flushWriters() {
        try {
            sensorsWriter?.flush()
            locationWriter?.flush()
            tagsWriter?.flush()
        } catch (e: IOException) {
            Log.w(TAG, "Flush failed", e)
            reportFatal(LoggerError.STORAGE_UNAVAILABLE)
        }
    }

    private fun closeWriters() {
        for (writer in listOf(sensorsWriter, locationWriter, tagsWriter)) {
            try {
                writer?.close()
            } catch (e: IOException) {
                Log.w(TAG, "Close failed", e)
            }
        }
    }

    private fun publishStatus() {
        val nowNanos = SystemClock.elapsedRealtimeNanos()
        val dtS = (nowNanos - previousTickElapsedNanos) / NANOS_PER_SECOND_D
        val streams = ArrayList<StreamStatus>(streamCount)
        for (i in 0 until streamCount) {
            if (!isRegistered[i]) continue
            val rateHz = if (dtS > 0.0) (sensorCounts[i] - previousCounts[i]) / dtS else 0.0
            streams.add(StreamStatus(streamCodes[i], sensorCounts[i], rateHz))
            previousCounts[i] = sensorCounts[i]
        }
        previousTickElapsedNanos = nowNanos
        LoggerState.publish(
            LoggerStatus(
                isRecording = true,
                sessionName = sessionName,
                elapsedMs = TimeBase.nanosToMs(nowNanos - startElapsedNanos),
                streams = streams,
                missingSensors = missingStreamCodes(),
                locationCount = locationWriter?.rowCount ?: 0L,
                tagCount = tagsWriter?.rowCount ?: 0L,
                lastAccuracyM = lastAccuracyM,
                lastSpeedKmh = lastSpeedMps?.let { it * KMH_PER_MPS },
                lastFixAgeMs = if (lastFixElapsedNanos > 0L) TimeBase.nanosToMs(nowNanos - lastFixElapsedNanos) else null,
                lastTag = lastTag,
                error = if (isLocationRequestFailed) LoggerError.LOCATION_REQUEST_FAILED else null,
            ),
        )
    }

    private fun missingStreamCodes(): List<String> = (0 until streamCount).filter { !isRegistered[it] }.map { streamCodes[it] }

    private fun writeMeta(
        isComplete: Boolean,
        endWallMs: Long?,
    ) {
        val dir = sessionDir ?: return
        try {
            val tmp = File(dir, "${SessionNaming.META_FILE}.tmp")
            tmp.writeText(buildMeta(isComplete, endWallMs).toString(2))
            val target = File(dir, SessionNaming.META_FILE)
            if (!tmp.renameTo(target)) {
                target.delete()
                tmp.renameTo(target)
            }
        } catch (e: IOException) {
            Log.w(TAG, "meta.json write failed", e)
        } catch (e: JSONException) {
            Log.w(TAG, "meta.json build failed", e)
        }
    }

    private fun buildMeta(
        isComplete: Boolean,
        endWallMs: Long?,
    ): JSONObject {
        val sensorsJson = JSONArray()
        for (i in 0 until streamCount) {
            val sensor = sensors[i]
            val item =
                JSONObject()
                    .put("stream", streamCodes[i])
                    .put("available", sensor != null)
                    .put("registered", isRegistered[i])
            if (sensor != null) {
                item
                    .put("name", sensor.name)
                    .put("vendor", sensor.vendor)
                    .put("type", sensor.stringType)
                    .put("resolution", sensor.resolution.toDouble())
                    .put("maxRange", sensor.maximumRange.toDouble())
                    .put("minDelayUs", sensor.minDelay)
            }
            if (!firstEventLagMs[i].isNaN()) item.put("firstEventLagMs", firstEventLagMs[i])
            sensorsJson.put(item)
        }
        val missingJson = JSONArray()
        for (code in missingStreamCodes()) missingJson.put(code)

        val counts = JSONObject()
        for (i in 0 until streamCount) counts.put(streamCodes[i], sensorCounts[i])
        counts.put("location", locationWriter?.rowCount ?: 0L)
        counts.put("tags", tagsWriter?.rowCount ?: 0L)

        val nowNanos = SystemClock.elapsedRealtimeNanos()
        return JSONObject()
            .put("schemaVersion", META_SCHEMA_VERSION)
            .put("sessionId", sessionName)
            .put("appId", BuildConfig.APPLICATION_ID)
            .put("appVersionName", BuildConfig.VERSION_NAME)
            .put("appVersionCode", BuildConfig.VERSION_CODE)
            .put("deviceManufacturer", Build.MANUFACTURER)
            .put("deviceModel", Build.MODEL)
            .put("androidSdkInt", Build.VERSION.SDK_INT)
            .put("androidRelease", Build.VERSION.RELEASE)
            .put("vehicleType", config.vehicle.code)
            .put("phonePlacement", config.placement.code)
            .put("note", config.note)
            .put("keepCpuAwake", config.keepCpuAwake)
            .put("samplingPeriodUs", SAMPLING_PERIOD_US)
            .put("maxReportLatencyUs", MAX_REPORT_LATENCY_US)
            .put("locationIntervalMs", LOCATION_INTERVAL_MS)
            .put("timeBase", TIME_BASE_DESCRIPTION)
            .put("startElapsedRealtimeNanos", startElapsedNanos)
            .put("startWallMs", startWallMs)
            .put("endWallMs", endWallMs ?: JSONObject.NULL)
            .put("durationMs", TimeBase.nanosToMs(nowNanos - startElapsedNanos))
            .put("isComplete", isComplete)
            .put("sensors", sensorsJson)
            .put("missingSensors", missingJson)
            .put("counts", counts)
            .put("droppedBeforeStart", JSONObject().put("sensors", droppedSensorEvents).put("location", droppedLocations))
            .put("locationRequestFailed", isLocationRequestFailed)
    }

    companion object {
        /** 100 Hz. Explicitly not SENSOR_DELAY_FASTEST (see registerSensors). */
        const val SAMPLING_PERIOD_US = 10_000

        /** No batching in the logger (fidelity over battery). */
        const val MAX_REPORT_LATENCY_US = 0

        /** About 1 Hz GPS, as in the product (ARCHITECTURE.md section 4.2). */
        const val LOCATION_INTERVAL_MS = 1_000L

        private const val TAG = "MmrRecordingEngine"
        private const val META_SCHEMA_VERSION = 1
        private const val TICK_MS = 1_000L
        private const val FLUSH_EVERY_TICKS = 3L
        private const val ROW_CAPACITY = 160
        private const val MICROS_PER_MILLI = 1_000L
        private const val NANOS_PER_MILLI_D = 1_000_000.0
        private const val NANOS_PER_SECOND_D = 1_000_000_000.0
        private const val KMH_PER_MPS = 3.6f
        private const val TIME_BASE_DESCRIPTION =
            "t_ms = (elapsedRealtimeNanos - startElapsedRealtimeNanos) / 1e6, 3 decimals; " +
                "SensorEvent.timestamp and Location.elapsedRealtimeNanos assumed on the elapsed-realtime clock"
    }
}
