package com.mapmyroad.sensing

import app.cash.turbine.test
import com.mapmyroad.domain.session.GpsStatus
import com.mapmyroad.domain.session.TripSessionState
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

class TripSessionStoreTest {
    private val store = TripSessionStore()

    private fun recording(tripId: String) =
        TripSessionState.Recording(
            tripId = tripId,
            startedAtMs = 1_000L,
            distanceM = 0.0,
            minorCount = 0,
            moderateCount = 0,
            severeCount = 0,
            gpsStatus = GpsStatus.WAITING,
            pauseReason = null,
        )

    @Test
    fun updateRecording_appliesToTheCurrentTrip() {
        store.setState(recording("a"))
        store.updateRecording("a") { it.copy(distanceM = 42.0) }
        assertEquals(42.0, (store.state.value as TripSessionState.Recording).distanceM, 0.0)
    }

    @Test
    fun updateRecording_ignoresOtherTripsAndOtherStates() {
        store.setState(recording("a"))
        store.updateRecording("b") { it.copy(distanceM = 42.0) }
        assertEquals(recording("a"), store.state.value)

        store.setState(TripSessionState.Finishing("a"))
        store.updateRecording("a") { it.copy(distanceM = 42.0) }
        assertEquals(TripSessionState.Finishing("a"), store.state.value)
    }

    @Test
    fun finishedTrips_areDeliveredToCollectors() =
        runTest {
            store.finishedTrips.test {
                store.emitFinished("a")
                assertEquals("a", awaitItem())
            }
        }
}
