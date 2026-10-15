package com.mapmyroad.ui.settings

import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.upload.UploadScheduler
import kotlinx.coroutines.CancellationException
import timber.log.Timber

/** Result of a "Delete my data" attempt (S11, PRD PV-3). */
enum class DeleteOutcome {
    /** Server data (if any) and all local data are gone. The root gate now shows onboarding. */
    DELETED,

    /** The server could not be reached or was unavailable. Nothing was deleted; retry later. */
    SERVER_UNREACHABLE,

    /** The server refused the deletion for another reason. Nothing was deleted. */
    SERVER_FAILED,

    /** The server answered 426: the update screen takes over. Nothing was deleted. */
    UPDATE_REQUIRED,

    /** Server data (if any) was deleted, but deleting local data failed. Retrying deletes the local data. */
    LOCAL_FAILED,
}

/**
 * "Delete my data" (PRD PV-3, API_SPEC.md 5.2, DATABASE_SCHEMA.md A10 and B3). The order matters:
 *
 * 1. Cancel pending uploads, so no new data reaches the server after it was deleted.
 * 2. Delete the server data while the stored credentials still exist. Doing it the other way round would leave
 *    server data that can no longer be deleted from the app, because the credentials would be gone with the
 *    local data.
 * 3. Delete local trips, then settings. Clearing settings resets "onboarding completed", so the root gate returns
 *    to onboarding.
 *
 * A server failure stops before anything local is deleted. [deleteOnlyOnThisPhone] is the explicit fallback.
 */
class DataDeletion(
    private val tripRepository: TripRepository,
    private val settingsRepository: SettingsRepository,
    private val deviceSession: DeviceSession,
    private val uploadScheduler: UploadScheduler,
) {
    suspend fun deleteEverywhere(): DeleteOutcome {
        uploadScheduler.cancelUploads()
        val serverResult =
            try {
                if (deviceSession.isRegistered()) deviceSession.deleteRemoteData() else ApiResult.Success(Unit)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Timber.e(e, "Deleting server data failed")
                ApiResult.Failure(ApiError.Server(status = null))
            }
        return when (serverResult) {
            is ApiResult.Success -> deleteLocalData()
            is ApiResult.Failure -> serverFailureOutcome(serverResult.error)
        }
    }

    /**
     * Deletes trips and settings on this phone only, after the server could not be reached. The credentials store
     * (a separate DataStore owned by [DeviceSession]) is deliberately left untouched: the install keeps its ID and
     * tokens, so "Delete my data" can still remove the server data later. Settings are reset, which turns
     * contribution off, so nothing new is uploaded.
     */
    suspend fun deleteOnlyOnThisPhone(): DeleteOutcome {
        uploadScheduler.cancelUploads()
        return deleteLocalData()
    }

    private suspend fun deleteLocalData(): DeleteOutcome =
        try {
            tripRepository.deleteAllTrips()
            settingsRepository.clearAll()
            DeleteOutcome.DELETED
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.e(e, "Deleting local data failed")
            DeleteOutcome.LOCAL_FAILED
        }
}

/** Connection-type failures get the "could not reach the server" message with Retry (S11). */
internal fun serverFailureOutcome(error: ApiError): DeleteOutcome =
    when (error) {
        ApiError.UpdateRequired -> DeleteOutcome.UPDATE_REQUIRED
        ApiError.Network,
        ApiError.Unavailable,
        is ApiError.RateLimited,
        is ApiError.Server,
        -> DeleteOutcome.SERVER_UNREACHABLE
        ApiError.Unauthorized,
        ApiError.IntegrityFailed,
        ApiError.NotFound,
        is ApiError.Rejected,
        -> DeleteOutcome.SERVER_FAILED
    }
