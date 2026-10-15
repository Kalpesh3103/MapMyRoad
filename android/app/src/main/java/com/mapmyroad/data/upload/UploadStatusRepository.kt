package com.mapmyroad.data.upload

import com.mapmyroad.data.local.TripDao
import com.mapmyroad.data.local.UploadDao
import com.mapmyroad.domain.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only upload status for the UI: the trip summary line (S4) and the Sharing group in Settings (S10). */
@Singleton
class UploadStatusRepository
    @Inject
    constructor(
        private val uploadDao: UploadDao,
        private val tripDao: TripDao,
        private val settingsRepository: SettingsRepository,
    ) {
        fun observeUploadState(tripId: String): Flow<TripUploadState> {
            val queueRow =
                uploadDao
                    .observeForTrip(tripId)
                    .map { row -> row?.state to row?.lastError }
                    .distinctUntilChanged()
            val tripStartedAtMs =
                tripDao
                    .observeTrip(tripId)
                    .map { trip -> trip?.startedAt }
                    .distinctUntilChanged()
            // Null while contribution is off.
            val contributionEnabledAtMs =
                settingsRepository
                    .settings
                    .map { if (it.contributionEnabled) it.contributionEnabledAtMs else null }
                    .distinctUntilChanged()
            return combine(queueRow, tripStartedAtMs, contributionEnabledAtMs) { (state, lastError), startedAtMs, enabledAtMs ->
                tripUploadStateOf(state, lastError, startedAtMs, enabledAtMs)
            }.distinctUntilChanged()
        }

        /** Trips queued and not uploaded yet. */
        fun observeWaitingTripCount(): Flow<Int> = uploadDao.observeWaitingCount().distinctUntilChanged()
    }
