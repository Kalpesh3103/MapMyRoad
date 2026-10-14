package com.mapmyroad.data.local

import com.mapmyroad.detector.Severity
import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.RecordedHazard
import com.mapmyroad.domain.model.SegmentSample
import com.mapmyroad.domain.model.StartMode
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip
import com.mapmyroad.domain.model.TripPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class EntityMappersTest {
    private val trip =
        Trip(
            id = "trip-1",
            vehicleType = VehicleType.SCOOTER,
            startMode = StartMode.AUTO,
            startedAtMs = 1_000L,
            endedAtMs = 61_000L,
            distanceM = 1234.5,
            durationS = 60,
            smoothnessScore = 81,
            minorCount = 1,
            moderateCount = 2,
            severeCount = 3,
            stopReason = StopReason.LOW_BATTERY,
        )

    @Test
    fun trip_roundTrips_andStoresCodes() {
        val entity = trip.toEntity(tripToken = "token")

        assertEquals("token", entity.tripToken)
        assertEquals(VehicleType.SCOOTER.dbCode, entity.vehicleType)
        assertEquals("auto", entity.startMode)
        assertEquals("low_battery", entity.stopReason)
        assertEquals(trip, entity.toDomainOrNull())
    }

    @Test
    fun unfinishedTrip_roundTrips_withNullEndAndStopReason() {
        val recording = trip.copy(endedAtMs = null, smoothnessScore = null, stopReason = null)

        val entity = recording.toEntity(tripToken = "token")

        assertNull(entity.endedAt)
        assertNull(entity.stopReason)
        assertEquals(recording, entity.toDomainOrNull())
    }

    @Test
    fun tripWithUnknownVehicleCode_mapsToNull() {
        val entity = trip.toEntity(tripToken = "token").copy(vehicleType = 99)

        assertNull(entity.toDomainOrNull())
    }

    @Test
    fun unknownStartMode_fallsBackToManual_andUnknownStopReason_isNull() {
        val entity = trip.toEntity(tripToken = "token").copy(startMode = "future_mode", stopReason = "future_reason")

        val mapped = entity.toDomainOrNull()

        assertEquals(StartMode.MANUAL, mapped?.startMode)
        assertNull(mapped?.stopReason)
    }

    @Test
    fun everyStopReasonCode_roundTrips() {
        StopReason.entries.forEach { reason -> assertEquals(reason, stopReasonFromCode(reason.code)) }
        StartMode.entries.forEach { mode -> assertEquals(mode, startModeFromCode(mode.code)) }
    }

    @Test
    fun point_roundTrips() {
        val point = TripPoint(tripId = "trip-1", tMs = 5_000L, lat = 18.5, lon = 73.8, speedMps = 8.5, accuracyM = 4.0, roughness = 0.7)

        val entity = point.toEntity()

        assertEquals(0L, entity.id)
        assertEquals(5_000L, entity.t)
        assertEquals(point, entity.toDomain())
    }

    @Test
    fun hazard_roundTrips_andIsNotUploaded() {
        val hazard =
            RecordedHazard(
                id = "h-1",
                tripId = "trip-1",
                lat = 18.5,
                lon = 73.8,
                observedAtMs = 7_000L,
                severity = Severity.MODERATE,
                speedKmh = 32.0,
                peakScore = 2.4,
                headingDeg = 270,
            )

        val entity = hazard.toEntity()

        assertEquals(2, entity.severity)
        assertFalse(entity.isUploaded)
        assertEquals(hazard, entity.toDomainOrNull())
    }

    @Test
    fun hazardWithUnknownSeverity_mapsToNull() {
        val entity =
            HazardEventLocalEntity(
                id = "h-1",
                tripId = "trip-1",
                lat = 18.5,
                lon = 73.8,
                observedAt = 7_000L,
                severity = 9,
                speedKmh = 32.0,
                peakScore = 2.4,
                headingDeg = null,
            )

        assertNull(entity.toDomainOrNull())
    }

    @Test
    fun sample_roundTrips_andIsNotUploaded() {
        val sample =
            SegmentSample(
                id = "s-1",
                tripId = "trip-1",
                startLat = 18.5,
                startLon = 73.8,
                endLat = 18.501,
                endLon = 73.8,
                observedAtMs = 9_000L,
                lengthM = 111.0,
                roughness = 1.3,
                speedKmh = 40.0,
            )

        val entity = sample.toEntity()

        assertFalse(entity.isUploaded)
        assertEquals(sample, entity.toDomain())
    }
}
