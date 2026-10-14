package com.mapmyroad.sensing

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.domain.session.TripSessionState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [TripSessionController] backed by [TripTrackingService]. Commands are Intents to the service; results are
 * observed through [TripSessionStore]. Never shows UI (UI_UX_GUIDELINES.md section 6): failures are logged and the
 * state simply stays as it is.
 *
 * Threading: call from the main thread (UI events).
 */
@Singleton
class DefaultTripSessionController
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: TripSessionStore,
        private val locationPermissionChecker: LocationPermissionChecker,
    ) : TripSessionController {
        override val state: StateFlow<TripSessionState> = store.state

        override val finishedTrips: Flow<String> = store.finishedTrips

        override fun startTrip() {
            if (store.state.value !is TripSessionState.Idle) return
            // Checked here, not only in the service: a service started with startForegroundService that cannot call
            // startForeground (API 34+ throws without the permission) would crash the app.
            if (!locationPermissionChecker.hasFineLocation()) {
                Timber.w("Start ignored: fine location permission missing")
                return
            }
            try {
                // Allowed from the foreground: the user taps Start while the app is visible.
                ContextCompat.startForegroundService(context, TripTrackingService.startIntent(context))
            } catch (e: IllegalStateException) {
                // ForegroundServiceStartNotAllowedException (API 31+) extends IllegalStateException.
                Timber.w(e, "Cannot start the tracking service")
            }
        }

        override fun stopTrip() {
            sendIfRunning(TripTrackingService.stopIntent(context))
        }

        override fun pauseTrip() {
            sendIfRunning(TripTrackingService.pauseIntent(context))
        }

        override fun resumeTrip() {
            sendIfRunning(TripTrackingService.resumeIntent(context))
        }

        private fun sendIfRunning(intent: Intent) {
            // Only a Recording trip has a running service to talk to. Finishing is already stopping.
            if (store.state.value !is TripSessionState.Recording) return
            try {
                context.startService(intent)
            } catch (e: IllegalStateException) {
                Timber.w(e, "Cannot deliver command to the tracking service")
            }
        }
    }
