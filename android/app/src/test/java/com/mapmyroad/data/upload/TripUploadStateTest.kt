package com.mapmyroad.data.upload

import com.mapmyroad.data.local.UploadQueueState
import org.junit.Assert.assertEquals
import org.junit.Test

class TripUploadStateTest {
    @Test
    fun finalStates_showRegardlessOfTheCurrentSetting() {
        listOf(OPT_IN_MS, null).forEach { enabledAtMs ->
            assertEquals(TripUploadState.SHARED, tripUploadStateOf(UploadQueueState.DONE, null, AFTER_MS, enabledAtMs))
            assertEquals(TripUploadState.SKIPPED, tripUploadStateOf(UploadQueueState.SKIPPED, null, AFTER_MS, enabledAtMs))
            assertEquals(TripUploadState.FAILED, tripUploadStateOf(UploadQueueState.FAILED, "network", AFTER_MS, enabledAtMs))
        }
    }

    @Test
    fun queuedOrUnqueued_isWaitingOnlyForTripsAfterTheOptIn() {
        listOf(null, UploadQueueState.PENDING, UploadQueueState.IN_PROGRESS).forEach { state ->
            assertEquals(TripUploadState.WAITING, tripUploadStateOf(state, null, AFTER_MS, OPT_IN_MS))
            assertEquals(TripUploadState.WAITING, tripUploadStateOf(state, null, OPT_IN_MS, OPT_IN_MS))
            assertEquals(TripUploadState.NOT_SHARED, tripUploadStateOf(state, null, OPT_IN_MS - 1L, OPT_IN_MS))
            assertEquals(TripUploadState.NOT_SHARED, tripUploadStateOf(state, null, AFTER_MS, contributionEnabledAtMs = null))
            assertEquals(TripUploadState.NOT_SHARED, tripUploadStateOf(state, null, tripStartedAtMs = null, OPT_IN_MS))
        }
    }

    @Test
    fun skippedBeforeOptIn_showsNothing() {
        assertEquals(
            TripUploadState.NOT_SHARED,
            tripUploadStateOf(UploadQueueState.SKIPPED, UploadQueueState.CATEGORY_BEFORE_OPT_IN, OPT_IN_MS - 1L, OPT_IN_MS),
        )
    }

    private companion object {
        const val OPT_IN_MS = 1_000_000L
        const val AFTER_MS = 2_000_000L
    }
}
