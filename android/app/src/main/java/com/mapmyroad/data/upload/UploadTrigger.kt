package com.mapmyroad.data.upload

import com.mapmyroad.di.ApplicationScope
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.domain.upload.SchedulerCommand
import com.mapmyroad.domain.upload.SharingSettings
import com.mapmyroad.domain.upload.schedulerCommandFor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Starts uploads when they become possible (M5-4): after every finished trip, and when the sharing settings change.
 * Started once from MapMyRoadApp.onCreate; lives in the application scope.
 */
@Singleton
class UploadTrigger
    @Inject
    constructor(
        @ApplicationScope private val scope: CoroutineScope,
        private val sessionController: TripSessionController,
        private val settingsRepository: SettingsRepository,
        private val scheduler: WorkManagerUploadScheduler,
    ) {
        private var isStarted = false

        fun start() {
            if (isStarted) return
            isStarted = true
            scope.launch {
                // scheduleUploads() checks the opt-in itself, so a finished trip never uploads without consent.
                sessionController.finishedTrips.collect { scheduler.scheduleUploads() }
            }
            scope.launch {
                var previous: SharingSettings? = null
                settingsRepository
                    .settings
                    .map { SharingSettings(it.contributionEnabled, it.uploadOnMobileData) }
                    .distinctUntilChanged()
                    .collect { current ->
                        when (schedulerCommandFor(previous, current)) {
                            SchedulerCommand.CANCEL -> scheduler.cancelUploads()
                            SchedulerCommand.SCHEDULE -> scheduler.scheduleUploads()
                            SchedulerCommand.RESCHEDULE -> scheduler.rescheduleWithCurrentSettings()
                            SchedulerCommand.NONE -> Unit
                        }
                        previous = current
                    }
            }
        }
    }
