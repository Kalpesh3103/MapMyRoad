package com.mapmyroad.sensing

import android.Manifest
import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.mapmyroad.domain.model.StartMode
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.model.UserSettings
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.session.GpsStatus
import com.mapmyroad.domain.session.TripSessionState
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

/**
 * Foreground service (type `location`) that owns the active trip (tasks M2-2, M2-6; ARCHITECTURE.md section 4.2).
 *
 * Commands arrive as Intents ([startIntent], [stopIntent], [pauseIntent], [resumeIntent]) from
 * [DefaultTripSessionController] and from the notification actions.
 *
 * Threading model (CODING_STANDARDS.md section 3.6):
 * - Main thread: onStartCommand, onDestroy, the notification and [activeTrip]. Commands run as coroutines in
 *   [serviceScope] (Dispatchers.Main.immediate) and are serialized by [lifecycleMutex] (fair, so FIFO), so a stop
 *   can never interleave with a start that is still inserting the trip row.
 * - The [TripRecorder] owns its own sensor thread (see its KDoc). Its stop requests (auto-stop, low battery,
 *   permission revoked) arrive on that thread and are re-dispatched to [serviceScope].
 * - The stop sequence runs under NonCancellable, so the trip is closed even if the service is being destroyed.
 *
 * State machine (published in [TripSessionStore]):
 * Idle -> (START) Recording(pauseReason = null) <-> Recording(pauseReason = MANUAL or STATIONARY)
 *      -> (STOP, auto-stop, low battery, permission revoked) Finishing -> emit finished trip ID -> Idle.
 *
 * START_NOT_STICKY: after process death the system does not restart the service. Restarting a trip from the
 * background is not reliable (Android 12+ background start restrictions, no background location permission), and
 * a silently restarted trip would surprise the rider. Instead [TripRecovery] closes the killed trip with
 * everything persisted so far on the next app start (PRD: no data loss; UI_UX_GUIDELINES.md section 8).
 *
 * No dialogs or toasts ever (UI_UX_GUIDELINES.md section 6): failures are logged and reflected in the state.
 * Logs contain trip IDs and counts only, never coordinates (SECURITY.md section 8).
 */
@AndroidEntryPoint
class TripTrackingService : Service() {
    @Inject
    lateinit var store: TripSessionStore

    @Inject
    lateinit var tripRepository: TripRepository

    @Inject
    lateinit var settingsRepository: SettingsRepository

    @Inject
    lateinit var locationPermissionChecker: LocationPermissionChecker

    @Inject
    lateinit var recorderFactory: TripRecorderFactory

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val lifecycleMutex = Mutex()
    private lateinit var notificationFactory: TripNotificationFactory

    // Main thread only.
    private var activeTrip: ActiveTrip? = null
    private var isDestroyed = false

    /** Most recent startId, for stops that do not come from a command (recorder requests). */
    private var lastStartId = 0

    override fun onCreate() {
        // Hilt injects the fields in super.onCreate().
        super.onCreate()
        notificationFactory = TripNotificationFactory(this)
        notificationFactory.createChannel()
        observePauseStateForNotification()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int,
    ): Int {
        lastStartId = startId
        when (intent?.action) {
            ACTION_START -> onStartRequested(startId)
            ACTION_STOP -> launchCommand { stopTripLocked(StopReason.MANUAL, expectedTripId = null, startId = startId) }
            ACTION_PAUSE -> launchCommand { activeTrip?.recorder?.pauseManually() ?: stopIfIdle(startId) }
            ACTION_RESUME -> launchCommand { activeTrip?.recorder?.resumeManually() ?: stopIfIdle(startId) }
            else -> launchCommand { stopIfIdle(startId) }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        isDestroyed = true
        val trip = activeTrip
        activeTrip = null
        if (trip != null) {
            // Destroyed without our stop sequence (not expected: only this service stops itself). Close the trip in
            // the application scope, which outlives this service.
            Timber.w("Trip %s: service destroyed while recording", trip.tripId)
            recorderFactory.applicationScope.launch { finishTrip(trip, StopReason.SERVICE_KILLED) }
        }
        serviceScope.cancel()
        super.onDestroy()
    }

    private fun onStartRequested(startId: Int) {
        // startForeground first, before any check and even when already recording: every startForegroundService
        // call must be followed by startForeground within the time limit or the system crashes the app.
        try {
            ServiceCompat.startForeground(this, TripNotificationFactory.NOTIFICATION_ID, currentNotification(), foregroundServiceType())
        } catch (e: RuntimeException) {
            // For example SecurityException (location permission missing on API 34+) or
            // ForegroundServiceStartNotAllowedException (API 31+).
            Timber.w(e, "startForeground failed")
            launchCommand { stopIfIdle(startId) }
            return
        }
        launchCommand { startTripLocked(startId) }
    }

    private fun launchCommand(block: suspend () -> Unit) {
        serviceScope.launch { lifecycleMutex.withLock { block() } }
    }

    private suspend fun startTripLocked(startId: Int) {
        if (isDestroyed) return
        if (activeTrip != null) {
            Timber.d("Start ignored: already recording")
            return
        }
        if (!locationPermissionChecker.hasFineLocation()) {
            // No trip exists yet, so there is nothing to close: just stop (PERMISSION_REVOKED semantics, no dialog).
            Timber.w("Start ignored: fine location permission missing")
            stopForegroundAndSelf(startId)
            return
        }
        val settings = readSettings()
        val vehicleType = settings?.vehicleType
        if (settings == null || vehicleType == null) {
            // Onboarding (S1) sets the vehicle type before Start is offered; without it the detector has no profile.
            Timber.w("Start ignored: no vehicle type in settings")
            stopForegroundAndSelf(startId)
            return
        }
        val tripId = UUID.randomUUID().toString()
        val startedAtMs = System.currentTimeMillis()
        // Published before the row is inserted, so TripRecovery never closes this trip.
        store.setState(
            TripSessionState.Recording(
                tripId = tripId,
                startedAtMs = startedAtMs,
                distanceM = 0.0,
                minorCount = 0,
                moderateCount = 0,
                severeCount = 0,
                gpsStatus = GpsStatus.WAITING,
                pauseReason = null,
            ),
        )
        val trip =
            Trip(
                id = tripId,
                vehicleType = vehicleType,
                startMode = StartMode.MANUAL,
                startedAtMs = startedAtMs,
                endedAtMs = null,
                distanceM = 0.0,
                durationS = 0,
                smoothnessScore = null,
                minorCount = 0,
                moderateCount = 0,
                severeCount = 0,
                stopReason = null,
            )
        try {
            tripRepository.startTrip(trip)
        } catch (e: CancellationException) {
            store.setState(TripSessionState.Idle)
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Trip %s: cannot insert trip", tripId)
            store.setState(TripSessionState.Idle)
            stopForegroundAndSelf(startId)
            return
        }
        val recorder =
            recorderFactory.create(tripId, vehicleType, settings) { reason ->
                // Called on the recorder's sensor thread.
                launchCommand { stopTripLocked(reason, expectedTripId = tripId, startId = lastStartId) }
            }
        activeTrip = ActiveTrip(tripId = tripId, startedAtMs = startedAtMs, recorder = recorder)
        updateNotification(NotificationModel(startedAtMs = startedAtMs, isPaused = false))
        recorder.start()
        // TODO(M2-14): auto-start (Activity Recognition) would start trips with StartMode.AUTO from here.
        Timber.i("Trip %s: started (%s)", tripId, vehicleType.code)
    }

    private suspend fun readSettings(): UserSettings? =
        try {
            settingsRepository.settings.first()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Cannot read settings")
            null
        }

    /**
     * [expectedTripId] guards requests from a recorder: they must not stop a later trip. [startId] is the command's
     * start ID: the service only stops if no newer command (for example a new START) has been delivered since.
     */
    private suspend fun stopTripLocked(
        reason: StopReason,
        expectedTripId: String?,
        startId: Int,
    ) {
        val trip = activeTrip
        if (trip == null) {
            stopIfIdle(startId)
            return
        }
        if (expectedTripId != null && trip.tripId != expectedTripId) return
        activeTrip = null
        withContext(NonCancellable) { finishTrip(trip, reason) }
        stopForegroundAndSelf(startId)
    }

    /** Stops the recorder, writes the last batch and closes the trip. Ends in Idle whatever fails. */
    private suspend fun finishTrip(
        trip: ActiveTrip,
        reason: StopReason,
    ) {
        store.setState(TripSessionState.Finishing(trip.tripId))
        var finishedTrip: Trip? = null
        try {
            try {
                trip.recorder.stop()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Trip %s: recorder stop failed", trip.tripId)
            }
            try {
                finishedTrip = tripRepository.finishTrip(trip.tripId, System.currentTimeMillis(), reason)
                Timber.i("Trip %s: finished (%s)", trip.tripId, reason.code)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The row stays unfinished; TripRecovery closes it on the next app start.
                Timber.e(e, "Trip %s: cannot finish trip", trip.tripId)
            }
        } finally {
            if (finishedTrip != null) store.emitFinished(trip.tripId)
            store.setState(TripSessionState.Idle)
        }
    }

    private fun stopIfIdle(startId: Int) {
        if (activeTrip == null) stopForegroundAndSelf(startId)
    }

    /**
     * Stops only if [startId] is the latest command. If a newer command (for example START) is already queued, the
     * service and its foreground state stay, and that command decides.
     */
    private fun stopForegroundAndSelf(startId: Int) {
        if (isDestroyed) return
        if (stopSelfResult(startId)) {
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        }
    }

    private fun observePauseStateForNotification() {
        serviceScope.launch {
            store
                .state
                .map { state ->
                    (state as? TripSessionState.Recording)?.let {
                        NotificationModel(startedAtMs = it.startedAtMs, isPaused = it.pauseReason != null)
                    }
                }
                .distinctUntilChanged()
                .collect { model -> if (model != null) updateNotification(model) }
        }
    }

    private fun currentNotification() =
        when (val state = store.state.value) {
            is TripSessionState.Recording -> notificationFactory.build(state.startedAtMs, isPaused = state.pauseReason != null)
            else -> notificationFactory.build(System.currentTimeMillis(), isPaused = false)
        }

    // Lint cannot see the explicit check below through the SDK branch; posting is skipped when it is not granted.
    @SuppressLint("MissingPermission")
    private fun updateNotification(model: NotificationModel) {
        // A late pause change must not re-post the notification after the trip has stopped.
        if (isDestroyed || activeTrip == null) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // Without the permission the foreground notification only appears in the task manager; recording continues.
            return
        }
        NotificationManagerCompat
            .from(this)
            .notify(TripNotificationFactory.NOTIFICATION_ID, notificationFactory.build(model.startedAtMs, model.isPaused))
    }

    private fun foregroundServiceType(): Int =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION else 0

    private class ActiveTrip(
        val tripId: String,
        val startedAtMs: Long,
        val recorder: TripRecorder,
    )

    private data class NotificationModel(
        val startedAtMs: Long,
        val isPaused: Boolean,
    )

    companion object {
        private const val ACTION_START = "com.mapmyroad.sensing.action.START_TRIP"
        private const val ACTION_STOP = "com.mapmyroad.sensing.action.STOP_TRIP"
        private const val ACTION_PAUSE = "com.mapmyroad.sensing.action.PAUSE_TRIP"
        private const val ACTION_RESUME = "com.mapmyroad.sensing.action.RESUME_TRIP"

        fun startIntent(context: Context): Intent = Intent(context, TripTrackingService::class.java).setAction(ACTION_START)

        fun stopIntent(context: Context): Intent = Intent(context, TripTrackingService::class.java).setAction(ACTION_STOP)

        fun pauseIntent(context: Context): Intent = Intent(context, TripTrackingService::class.java).setAction(ACTION_PAUSE)

        fun resumeIntent(context: Context): Intent = Intent(context, TripTrackingService::class.java).setAction(ACTION_RESUME)
    }
}
