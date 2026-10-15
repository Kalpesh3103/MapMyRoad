package com.mapmyroad.domain.upload

import com.mapmyroad.domain.model.ApiError
import org.junit.Assert.assertEquals
import org.junit.Test

class UploadDecisionTest {
    @Test
    fun transientUploadErrors_retryLater() {
        listOf(ApiError.Network, ApiError.Unavailable, ApiError.Server(500), ApiError.Server(null)).forEach { error ->
            assertEquals(
                UploadAction.RetryLater(UploadDecision.category(error)),
                UploadDecision.forUploadError(error),
            )
        }
    }

    @Test
    fun rateLimited_retriesNoEarlierThanRetryAfter() {
        assertEquals(
            UploadAction.RetryLater("rate_limited", notBeforeDelayMs = 120_000L),
            UploadDecision.forUploadError(ApiError.RateLimited(retryAfterSec = 120L)),
        )
        assertEquals(
            UploadAction.RetryLater("rate_limited", notBeforeDelayMs = 0L),
            UploadDecision.forUploadError(ApiError.RateLimited(retryAfterSec = null)),
        )
        assertEquals(
            UploadAction.RetryLater("rate_limited", notBeforeDelayMs = 0L),
            UploadDecision.forUploadError(ApiError.RateLimited(retryAfterSec = -5L)),
        )
    }

    @Test
    fun otherClientErrors_failOnlyThatTrip() {
        assertEquals(
            UploadAction.FailTrip("rejected:INVALID_REQUEST"),
            UploadDecision.forUploadError(ApiError.Rejected(400, "INVALID_REQUEST")),
        )
        assertEquals(UploadAction.FailTrip("rejected:413"), UploadDecision.forUploadError(ApiError.Rejected(413, null)))
        assertEquals(UploadAction.FailTrip("not_found"), UploadDecision.forUploadError(ApiError.NotFound))
    }

    @Test
    fun unauthorized_registersAgain_andUpdateRequired_stops() {
        assertEquals(UploadAction.Reregister, UploadDecision.forUploadError(ApiError.Unauthorized))
        assertEquals(UploadAction.Stop("update_required"), UploadDecision.forUploadError(ApiError.UpdateRequired))
    }

    @Test
    fun registrationErrors_retryOnlyWhenTransient() {
        assertEquals(UploadAction.RetryLater("network"), UploadDecision.forRegistrationError(ApiError.Network))
        assertEquals(UploadAction.RetryLater("server:502"), UploadDecision.forRegistrationError(ApiError.Server(502)))
        assertEquals(UploadAction.Stop("integrity_failed"), UploadDecision.forRegistrationError(ApiError.IntegrityFailed))
        assertEquals(UploadAction.Stop("update_required"), UploadDecision.forRegistrationError(ApiError.UpdateRequired))
        assertEquals(UploadAction.Stop("unauthorized"), UploadDecision.forRegistrationError(ApiError.Unauthorized))
    }

    @Test
    fun category_neverStoresFreeText() {
        // A code that is not a stable upper-case identifier falls back to the HTTP status.
        assertEquals("rejected:400", UploadDecision.category(ApiError.Rejected(400, "Free text from the server")))
        assertEquals("rejected:400", UploadDecision.category(ApiError.Rejected(400, "")))
        assertEquals("server:unreadable", UploadDecision.category(ApiError.Server(null)))
    }

    @Test
    fun network_isUnmeteredUnlessMobileDataIsAllowed() {
        assertEquals(UploadNetwork.UNMETERED, UploadNetwork.forSettings(uploadOnMobileData = false))
        assertEquals(UploadNetwork.CONNECTED, UploadNetwork.forSettings(uploadOnMobileData = true))
    }

    @Test
    fun schedulerCommand_followsTheSharingSettings() {
        val off = SharingSettings(contributionEnabled = false, uploadOnMobileData = false)
        val wifi = SharingSettings(contributionEnabled = true, uploadOnMobileData = false)
        val mobile = SharingSettings(contributionEnabled = true, uploadOnMobileData = true)

        assertEquals(SchedulerCommand.CANCEL, schedulerCommandFor(previous = null, current = off))
        assertEquals(SchedulerCommand.SCHEDULE, schedulerCommandFor(previous = null, current = wifi))
        assertEquals(SchedulerCommand.SCHEDULE, schedulerCommandFor(previous = off, current = mobile))
        assertEquals(SchedulerCommand.RESCHEDULE, schedulerCommandFor(previous = wifi, current = mobile))
        assertEquals(SchedulerCommand.RESCHEDULE, schedulerCommandFor(previous = mobile, current = wifi))
        assertEquals(SchedulerCommand.CANCEL, schedulerCommandFor(previous = mobile, current = off))
        assertEquals(SchedulerCommand.NONE, schedulerCommandFor(previous = wifi, current = wifi))
    }
}
