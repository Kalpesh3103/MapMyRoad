package com.mapmyroad.sensing

import android.content.Context
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.di.ApplicationScope
import com.mapmyroad.di.IoDispatcher
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.UserSettings
import com.mapmyroad.domain.repository.TripRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject

/**
 * Creates one [TripRecorder] per trip with its singleton dependencies. Keeps qualifiers on constructor parameters
 * (instead of field injection in the service).
 */
class TripRecorderFactory
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val store: TripSessionStore,
        private val tripRepository: TripRepository,
        private val locationPermissionChecker: LocationPermissionChecker,
        /** Outlives the service: used for the final writes and for closing a trip from onDestroy. */
        @ApplicationScope val applicationScope: CoroutineScope,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) {
        fun create(
            tripId: String,
            vehicleType: VehicleType,
            settings: UserSettings,
            onStopRequested: (StopReason) -> Unit,
        ): TripRecorder =
            TripRecorder(
                context = context,
                tripId = tripId,
                vehicleType = vehicleType,
                // Read once at trip start; a settings change applies from the next trip.
                isAutoPauseEnabled = settings.autoPauseEnabled,
                lowBatteryStopPercent = settings.lowBatteryStopPercent,
                store = store,
                tripRepository = tripRepository,
                locationPermissionChecker = locationPermissionChecker,
                persistenceScope = applicationScope,
                ioDispatcher = ioDispatcher,
                onStopRequested = onStopRequested,
            )
    }
