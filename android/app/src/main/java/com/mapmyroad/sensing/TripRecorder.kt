package com.mapmyroad.sensing

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.BatteryManager
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.os.SystemClock
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.mapmyroad.detector.BumpEvent
import com.mapmyroad.detector.DetectorListener
import com.mapmyroad.detector.LocationFix
import com.mapmyroad.detector.RoadDetector
import com.mapmyroad.detector.RoughnessWindow
import com.mapmyroad.detector.Severity
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.TripPoint
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.session.GpsStatus
import com.mapmyroad.domain.session.PauseReason
import com.mapmyroad.sensing.policy.AutoPausePolicy
import com.mapmyroad.sensing.policy.BatteryStopPolicy
import com.mapmyroad.sensing.policy.GpsStatusEvaluator
import com.mapmyroad.sensing.policy.LiveDistanceAccumulator
import com.mapmyroad.sensing.policy.TrackDownsampler
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import java.util.UUID

/**
 * Records one trip: sensors and location in, detector events, track points and live state out (tasks M2-2, M2-6).
 * Created per trip by [TripTrackingService].
 *
 * Threading model (CODING_STANDARDS.md section 3.6):
 * - One HandlerThread, "trip-sensors", owns everything mutable in this class: sensor registration (listener on its
 *   Handler), the Fused Location callback (on its Looper), the [RoadDetector] (not thread-safe, so it is fed from
 *   this thread only), the policies, the counters and the write buffers. No locks are needed.
 * - Public functions ([start], [pauseManually], [resumeManually], [stop]) may be called from any thread; they post
 *   to the sensor thread. [stop] suspends until the sensor thread has flushed and all writes are done.
 * - Writes: every [TrackingConfig.PERSIST_INTERVAL_MS] (and on pause and stop) the buffers are handed as an
 *   immutable batch to a Channel consumed by one writer coroutine on the IO dispatcher, which calls
 *   TripRepository.appendRecording (one transaction per batch, DATABASE_SCHEMA.md B3). One consumer keeps batches
 *   in order. The writer runs in the application scope, so the final batch survives the service being destroyed.
 * - Live state goes to [TripSessionStore] (thread-safe) from the sensor thread.
 * - [onStopRequested] (auto-stop, low battery, permission revoked) is invoked on the sensor thread, at most once.
 * - Hot path (sensor events at 100 Hz, batched): no allocation, no logging; values are read from event.values.
 *
 * No wake lock: the foreground service and 1 Hz location updates keep the trip alive, and sensor batching lets
 * the CPU sleep between deliveries (PRD battery target).
 *
 * Pause: a manual pause stops sensors and location (zero cost while the rider takes a break; resumes only by
 * user action). An auto-pause (stationary) stops sensors but keeps location, which is needed to notice movement
 * again. Both reset the detector so that no roughness window spans the pause.
 */
class TripRecorder(
    context: Context,
    private val tripId: String,
    vehicleType: VehicleType,
    private val isAutoPauseEnabled: Boolean,
    lowBatteryStopPercent: Int,
    private val store: TripSessionStore,
    private val tripRepository: TripRepository,
    private val locationPermissionChecker: LocationPermissionChecker,
    persistenceScope: CoroutineScope,
    ioDispatcher: CoroutineDispatcher,
    private val onStopRequested: (StopReason) -> Unit,
) {
    private val appContext: Context = context.applicationContext
    private val sensorManager: SensorManager? = appContext.getSystemService(SensorManager::class.java)
    private val batteryManager: BatteryManager? = appContext.getSystemService(BatteryManager::class.java)
    private val fusedClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(appContext)

    private val thread = HandlerThread(THREAD_NAME, Process.THREAD_PRIORITY_FOREGROUND).apply { start() }
    private val handler = Handler(thread.looper)

    // Everything below is confined to the sensor thread.

    private val detectorListener =
        object : DetectorListener {
            override fun onBump(event: BumpEvent) = recordBump(event)

            override fun onRoughnessWindow(window: RoughnessWindow) = recordRoughnessWindow(window)
        }
    private val detector: RoadDetector = RoadDetector.create(vehicleType, detectorListener)

    private val gpsStatusEvaluator = GpsStatusEvaluator()
    private val trackDownsampler = TrackDownsampler()
    private val distanceAccumulator = LiveDistanceAccumulator()
    private val autoPausePolicy = AutoPausePolicy()
    private val batteryStopPolicy = BatteryStopPolicy(lowBatteryStopPercent)

    private val points = ArrayList<TripPoint>()
    private val hazards = ArrayList<RecordedHazard>()
    private val samples = ArrayList<SegmentSample>()

    private var isStarted = false
    private var isStopped = false
    private var isStopRequested = false
    private var isSensing = false
    private var isLocationActive = false
    private var pauseReason: PauseReason? = null
    private var minorCount = 0
    private var moderateCount = 0
    private var severeCount = 0
    private var latestRoughness: Double? = null
    private var lastPersistAtMs = 0L
    private var hasCheckedBattery = false
    private var lastBatteryCheckAtMs = 0L

    private val batches = Channel<RecordingBatch>(Channel.UNLIMITED)
    private val writerJob: Job =
        persistenceScope.launch(ioDispatcher) {
            for (batch in batches) writeBatch(batch)
        }

    private val sensorListener =
        object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent?) {
                if (event == null || !isSensing) return
                val values = event.values
                if (values.size < AXIS_COUNT) return
                when (event.sensor.type) {
                    Sensor.TYPE_ACCELEROMETER -> detector.onAccelerometer(event.timestamp, values[0], values[1], values[2])
                    Sensor.TYPE_GYROSCOPE -> detector.onGyroscope(event.timestamp, values[0], values[1], values[2])
                    Sensor.TYPE_GRAVITY -> detector.onGravity(event.timestamp, values[0], values[1], values[2])
                }
            }

            override fun onAccuracyChanged(
                sensor: Sensor?,
                accuracy: Int,
            ) {
                // Not used: the detector normalizes against its own rolling baseline.
            }
        }

    private val locationCallback =
        object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                for (location in result.locations) onLocation(location)
            }
        }

    private val ticker =
        object : Runnable {
            override fun run() {
                if (isStopped) return
                onTick()
                handler.postDelayed(this, TrackingConfig.TICK_MS)
            }
        }

    /** Registers sensors and location updates and starts the 1 s tick. */
    fun start() {
        handler.post { startOnSensorThread() }
    }

    fun pauseManually() {
        handler.post { if (!isStopped) enterPause(PauseReason.MANUAL) }
    }

    /** Resumes from a manual pause or, as a user override, from an auto-pause. */
    fun resumeManually() {
        handler.post { if (!isStopped) exitPause(SystemClock.elapsedRealtime(), isManual = true) }
    }

    /**
     * Unregisters everything, flushes the detector, writes the last batch and quits the sensor thread.
     * Idempotent. Does not touch the trip row: the caller finishes the trip afterwards.
     */
    suspend fun stop() {
        val sensorWorkDone = CompletableDeferred<Unit>()
        val isPosted =
            handler.post {
                try {
                    stopOnSensorThread()
                } finally {
                    sensorWorkDone.complete(Unit)
                }
            }
        if (isPosted) {
            val completed = withTimeoutOrNull(TrackingConfig.STOP_SENSOR_THREAD_TIMEOUT_MS) { sensorWorkDone.await() }
            if (completed == null) Timber.w("Trip %s: sensor thread did not finish stopping in time", tripId)
        }
        // No more batches after the final one; the writer drains the channel and ends.
        batches.close()
        writerJob.join()
        thread.quitSafely()
    }

    private fun startOnSensorThread() {
        if (isStarted || isStopped) return
        isStarted = true
        val nowMs = SystemClock.elapsedRealtime()
        lastPersistAtMs = nowMs
        autoPausePolicy.reset(nowMs)
        // No Activity Recognition in v1 manual trips (auto-start is M2-14): gating relies on speed.
        detector.onInVehicle(null)
        registerSensors()
        if (!requestLocationUpdates()) {
            requestStop(StopReason.PERMISSION_REVOKED)
            return
        }
        handler.postDelayed(ticker, TrackingConfig.TICK_MS)
        publishState(nowMs)
        Timber.i("Trip %s: recorder started", tripId)
    }

    private fun stopOnSensorThread() {
        if (isStopped) return
        isStopped = true
        handler.removeCallbacks(ticker)
        unregisterSensors()
        removeLocationUpdates()
        try {
            // Emits the last partial roughness window if it is long enough.
            detector.flush()
        } catch (e: RuntimeException) {
            Timber.e(e, "Trip %s: detector flush failed", tripId)
        }
        persistNow(SystemClock.elapsedRealtime())
        Timber.i("Trip %s: recorder stopped (%d minor, %d moderate, %d severe)", tripId, minorCount, moderateCount, severeCount)
    }

    private fun onLocation(location: Location) {
        if (!isStarted || isStopped || pauseReason == PauseReason.MANUAL) return
        val nowMs = SystemClock.elapsedRealtime()
        val fixAtMs = location.elapsedRealtimeNanos / NANOS_PER_MILLI
        // The first delivery can be a cached fix from before the trip: too old to describe the current position.
        if (nowMs - fixAtMs > TrackingConfig.GPS_MAX_FIX_AGE_MS) return
        val speedMps = if (location.hasSpeed()) location.speed else null
        val accuracyM = if (location.hasAccuracy()) location.accuracy else null
        val bearingDeg = if (location.hasBearing()) location.bearing else null
        val lat = location.latitude
        val lon = location.longitude
        gpsStatusEvaluator.onFix(fixAtMs, accuracyM)

        if (pauseReason == null) {
            detector.onLocation(
                LocationFix(
                    elapsedNanos = location.elapsedRealtimeNanos,
                    wallTimeMs = location.time,
                    lat = lat,
                    lon = lon,
                    speedMps = speedMps,
                    accuracyM = accuracyM,
                    bearingDeg = bearingDeg,
                ),
            )
            distanceAccumulator.onFix(fixAtMs, lat, lon, accuracyM)
            if (accuracyM != null && trackDownsampler.shouldKeep(fixAtMs, accuracyM)) {
                points.add(
                    TripPoint(
                        tripId = tripId,
                        tMs = location.time,
                        lat = lat,
                        lon = lon,
                        speedMps = speedMps?.toDouble() ?: 0.0,
                        accuracyM = accuracyM.toDouble(),
                        roughness = latestRoughness,
                    ),
                )
            }
        }

        if (isAutoPauseEnabled) {
            val decision =
                autoPausePolicy.onFix(
                    fixAtMs = fixAtMs,
                    lat = lat,
                    lon = lon,
                    speedMps = speedMps,
                    accuracyM = accuracyM,
                    isPaused = pauseReason == PauseReason.STATIONARY,
                )
            applyAutoPauseDecision(decision, nowMs)
        }
        publishState(nowMs)
    }

    private fun onTick() {
        val nowMs = SystemClock.elapsedRealtime()
        if (isAutoPauseEnabled && pauseReason != PauseReason.MANUAL) {
            applyAutoPauseDecision(autoPausePolicy.onTick(nowMs, isPaused = pauseReason == PauseReason.STATIONARY), nowMs)
        }
        if (nowMs - lastPersistAtMs >= TrackingConfig.PERSIST_INTERVAL_MS) {
            persistNow(nowMs)
            // Android normally kills the process on revocation (recovery then closes the trip); this covers the rest.
            if (!locationPermissionChecker.hasFineLocation()) requestStop(StopReason.PERMISSION_REVOKED)
        }
        if (!hasCheckedBattery || nowMs - lastBatteryCheckAtMs >= TrackingConfig.BATTERY_CHECK_INTERVAL_MS) {
            checkBattery(nowMs)
        }
        publishState(nowMs)
    }

    private fun applyAutoPauseDecision(
        decision: AutoPausePolicy.Decision,
        nowMs: Long,
    ) {
        when (decision) {
            AutoPausePolicy.Decision.NONE -> Unit
            AutoPausePolicy.Decision.PAUSE -> if (pauseReason == null) enterPause(PauseReason.STATIONARY)
            AutoPausePolicy.Decision.RESUME -> if (pauseReason == PauseReason.STATIONARY) exitPause(nowMs, isManual = false)
            AutoPausePolicy.Decision.STOP -> requestStop(StopReason.AUTO)
        }
    }

    private fun enterPause(reason: PauseReason) {
        if (!isStarted || pauseReason == reason) return
        val wasPaused = pauseReason != null
        pauseReason = reason
        if (!wasPaused) {
            unregisterSensors()
            // Avoid a roughness window or bump baseline that spans the pause.
            detector.reset()
            distanceAccumulator.breakSegment()
            latestRoughness = null
        }
        if (reason == PauseReason.MANUAL) {
            removeLocationUpdates()
            gpsStatusEvaluator.reset()
        }
        val nowMs = SystemClock.elapsedRealtime()
        persistNow(nowMs)
        publishState(nowMs)
        Timber.i("Trip %s: paused (%s)", tripId, reason)
    }

    private fun exitPause(
        nowMs: Long,
        isManual: Boolean,
    ) {
        val previousReason = pauseReason ?: return
        if (previousReason == PauseReason.MANUAL && !requestLocationUpdates()) {
            requestStop(StopReason.PERMISSION_REVOKED)
            return
        }
        pauseReason = null
        registerSensors()
        // After a manual resume the rider is about to move: give auto-pause a fresh stationary clock.
        if (isManual) autoPausePolicy.reset(nowMs)
        publishState(nowMs)
        Timber.i("Trip %s: resumed from %s", tripId, previousReason)
    }

    private fun recordBump(event: BumpEvent) {
        hazards.add(
            RecordedHazard(
                id = UUID.randomUUID().toString(),
                tripId = tripId,
                lat = event.lat,
                lon = event.lon,
                observedAtMs = event.wallTimeMs,
                severity = event.severity,
                speedKmh = event.speedKmh,
                peakScore = event.peakScore,
                headingDeg = event.headingDeg,
            ),
        )
        when (event.severity) {
            Severity.MINOR -> minorCount++
            Severity.MODERATE -> moderateCount++
            Severity.SEVERE -> severeCount++
        }
        publishState(SystemClock.elapsedRealtime())
    }

    private fun recordRoughnessWindow(window: RoughnessWindow) {
        samples.add(
            SegmentSample(
                id = UUID.randomUUID().toString(),
                tripId = tripId,
                startLat = window.startLat,
                startLon = window.startLon,
                endLat = window.endLat,
                endLon = window.endLon,
                observedAtMs = window.wallTimeMs,
                lengthM = window.lengthM,
                roughness = window.roughness,
                speedKmh = window.speedKmh,
            ),
        )
        latestRoughness = window.roughness
    }

    private fun registerSensors() {
        if (isSensing) return
        val manager = sensorManager
        if (manager == null) {
            Timber.w("Trip %s: no SensorManager, recording the track only", tripId)
            return
        }
        var registeredCount = 0
        for (type in SENSOR_TYPES) {
            // Gravity is optional: without it the detector estimates gravity from the accelerometer.
            val sensor = manager.getDefaultSensor(type) ?: continue
            val isRegistered =
                manager.registerListener(
                    sensorListener,
                    sensor,
                    TrackingConfig.SENSOR_SAMPLING_PERIOD_US,
                    TrackingConfig.SENSOR_MAX_REPORT_LATENCY_US,
                    handler,
                )
            if (isRegistered) registeredCount++
        }
        isSensing = true
        Timber.d("Trip %s: %d sensor(s) registered", tripId, registeredCount)
    }

    private fun unregisterSensors() {
        if (!isSensing) return
        isSensing = false
        sensorManager?.unregisterListener(sensorListener)
    }

    // Checked by locationPermissionChecker right before the request.
    @SuppressLint("MissingPermission")
    private fun requestLocationUpdates(): Boolean {
        if (isLocationActive) return true
        if (!locationPermissionChecker.hasFineLocation()) return false
        val request =
            LocationRequest
                .Builder(Priority.PRIORITY_HIGH_ACCURACY, TrackingConfig.LOCATION_INTERVAL_MS)
                .setMinUpdateIntervalMillis(TrackingConfig.LOCATION_INTERVAL_MS)
                .build()
        return try {
            fusedClient
                .requestLocationUpdates(request, locationCallback, handler.looper)
                .addOnFailureListener { e ->
                    // Delivered on the main thread. Without location the trip shows "Waiting for GPS".
                    Timber.w(e, "Trip %s: location request failed", tripId)
                    if (e is SecurityException) handler.post { requestStop(StopReason.PERMISSION_REVOKED) }
                }
            isLocationActive = true
            true
        } catch (e: SecurityException) {
            Timber.w(e, "Trip %s: location permission revoked", tripId)
            false
        }
    }

    private fun removeLocationUpdates() {
        if (!isLocationActive) return
        isLocationActive = false
        fusedClient.removeLocationUpdates(locationCallback)
    }

    private fun checkBattery(nowMs: Long) {
        hasCheckedBattery = true
        lastBatteryCheckAtMs = nowMs
        val manager = batteryManager ?: return
        val levelPercent = manager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        if (batteryStopPolicy.shouldStop(levelPercent, manager.isCharging)) {
            Timber.i("Trip %s: battery at %d%%, stopping", tripId, levelPercent)
            requestStop(StopReason.LOW_BATTERY)
        }
    }

    private fun requestStop(reason: StopReason) {
        if (isStopRequested || isStopped) return
        isStopRequested = true
        Timber.i("Trip %s: stop requested (%s)", tripId, reason.code)
        onStopRequested(reason)
    }

    private fun publishState(nowMs: Long) {
        val gpsStatus: GpsStatus = gpsStatusEvaluator.evaluate(nowMs)
        val distanceM = distanceAccumulator.distanceM
        val currentPauseReason = pauseReason
        val minor = minorCount
        val moderate = moderateCount
        val severe = severeCount
        store.updateRecording(tripId) { recording ->
            recording.copy(
                distanceM = distanceM,
                minorCount = minor,
                moderateCount = moderate,
                severeCount = severe,
                gpsStatus = gpsStatus,
                pauseReason = currentPauseReason,
            )
        }
    }

    private fun persistNow(nowMs: Long) {
        lastPersistAtMs = nowMs
        if (points.isEmpty() && hazards.isEmpty() && samples.isEmpty()) return
        val batch = RecordingBatch(points = points.toList(), hazards = hazards.toList(), samples = samples.toList())
        points.clear()
        hazards.clear()
        samples.clear()
        if (batches.trySend(batch).isFailure) {
            Timber.w("Trip %s: batch dropped after stop (%d points)", tripId, batch.points.size)
        }
    }

    private suspend fun writeBatch(batch: RecordingBatch) {
        try {
            tripRepository.appendRecording(tripId, batch.points, batch.hazards, batch.samples)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Room exceptions carry no coordinates. The batch is lost; the trip continues (it must not crash).
            Timber.e(
                e,
                "Trip %s: write failed (%d points, %d hazards, %d samples)",
                tripId,
                batch.points.size,
                batch.hazards.size,
                batch.samples.size,
            )
        }
    }

    /** Immutable snapshot of the write buffers. */
    private class RecordingBatch(
        val points: List<TripPoint>,
        val hazards: List<RecordedHazard>,
        val samples: List<SegmentSample>,
    )

    private companion object {
        const val THREAD_NAME = "trip-sensors"
        const val AXIS_COUNT = 3
        const val NANOS_PER_MILLI = 1_000_000L
        val SENSOR_TYPES = intArrayOf(Sensor.TYPE_ACCELEROMETER, Sensor.TYPE_GYROSCOPE, Sensor.TYPE_GRAVITY)
    }
}
