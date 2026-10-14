package com.mapmyroad.sensing

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.StartMode
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.model.TripPoint
import com.mapmyroad.domain.repository.TripRepository
import com.mapmyroad.domain.session.GpsStatus
import com.mapmyroad.domain.session.TripSessionState
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TripRecoveryTest {
    private val repository = mockk<TripRepository>()
    private val store = TripSessionStore()
    private var hasPermission = true
    private val permissionChecker =
        object : LocationPermissionChecker {
            override fun hasFineLocation(): Boolean = hasPermission
        }
    private val recovery = TripRecovery(repository, store, permissionChecker, TestScope())

    private fun unfinishedTrip(
        id: String,
        startedAtMs: Long,
    ) = Trip(
        id = id,
        vehicleType = VehicleType.SCOOTER,
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

    private fun point(
        tripId: String,
        tMs: Long,
    ) = TripPoint(tripId = tripId, tMs = tMs, lat = 0.0, lon = 0.0, speedMps = 0.0, accuracyM = 5.0, roughness = null)

    @Test
    fun closesUnfinishedTrips_atLastPointTime_asServiceKilled() =
        runTest {
            coEvery { repository.findUnfinishedTrips() } returns listOf(unfinishedTrip("a", 1_000L), unfinishedTrip("b", 5_000L))
            every { repository.observeTripPoints("a") } returns flowOf(listOf(point("a", 2_000L), point("a", 3_000L)))
            every { repository.observeTripPoints("b") } returns flowOf(emptyList())
            coEvery { repository.finishTrip(any(), any(), any()) } answers { unfinishedTrip(firstArg(), 0L) }

            assertEquals(2, recovery.recoverUnfinishedTrips())

            coVerify { repository.finishTrip("a", 3_000L, StopReason.SERVICE_KILLED) }
            coVerify { repository.finishTrip("b", 5_000L, StopReason.SERVICE_KILLED) }
        }

    @Test
    fun usesPermissionRevoked_whenLocationIsNoLongerGranted() =
        runTest {
            hasPermission = false
            coEvery { repository.findUnfinishedTrips() } returns listOf(unfinishedTrip("a", 1_000L))
            every { repository.observeTripPoints("a") } returns flowOf(emptyList())
            coEvery { repository.finishTrip(any(), any(), any()) } answers { unfinishedTrip(firstArg(), 0L) }

            recovery.recoverUnfinishedTrips()

            coVerify { repository.finishTrip("a", 1_000L, StopReason.PERMISSION_REVOKED) }
        }

    @Test
    fun skipsTheTripOwnedByTheLiveSession() =
        runTest {
            store.setState(
                TripSessionState.Recording(
                    tripId = "live",
                    startedAtMs = 1_000L,
                    distanceM = 0.0,
                    minorCount = 0,
                    moderateCount = 0,
                    severeCount = 0,
                    gpsStatus = GpsStatus.WAITING,
                    pauseReason = null,
                ),
            )
            coEvery { repository.findUnfinishedTrips() } returns listOf(unfinishedTrip("live", 1_000L), unfinishedTrip("old", 500L))
            every { repository.observeTripPoints("old") } returns flowOf(emptyList())
            coEvery { repository.finishTrip(any(), any(), any()) } answers { unfinishedTrip(firstArg(), 0L) }

            assertEquals(1, recovery.recoverUnfinishedTrips())

            coVerify(exactly = 0) { repository.finishTrip("live", any(), any()) }
            coVerify { repository.finishTrip("old", 500L, StopReason.SERVICE_KILLED) }
        }

    @Test
    fun failureOnOneTrip_doesNotStopTheOthers() =
        runTest {
            coEvery { repository.findUnfinishedTrips() } returns listOf(unfinishedTrip("a", 1_000L), unfinishedTrip("b", 2_000L))
            every { repository.observeTripPoints(any()) } returns flowOf(emptyList())
            coEvery { repository.finishTrip("a", any(), any()) } throws IllegalStateException("db")
            coEvery { repository.finishTrip("b", any(), any()) } answers { unfinishedTrip("b", 0L) }

            assertEquals(1, recovery.recoverUnfinishedTrips())
        }

    @Test
    fun readFailure_recoversNothing() =
        runTest {
            coEvery { repository.findUnfinishedTrips() } throws IllegalStateException("db")

            assertEquals(0, recovery.recoverUnfinishedTrips())
        }
}
