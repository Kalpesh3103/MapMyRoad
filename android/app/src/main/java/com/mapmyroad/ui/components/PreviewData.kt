package com.mapmyroad.ui.components

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.StartMode
import com.mapmyroad.domain.model.StopReason
import com.mapmyroad.domain.model.Trip

/** Realistic fake data for Compose previews only. */
internal object PreviewData {
    private const val TRIP_START_MS = 1_791_955_260_000L

    val trip: Trip =
        Trip(
            id = "preview-trip-1",
            vehicleType = VehicleType.SCOOTER,
            startMode = StartMode.MANUAL,
            startedAtMs = TRIP_START_MS,
            endedAtMs = TRIP_START_MS + 1_421_000L,
            distanceM = 12_400.0,
            durationS = 1_421,
            smoothnessScore = 72,
            minorCount = 5,
            moderateCount = 2,
            severeCount = 1,
            stopReason = StopReason.MANUAL,
        )

    val trips: List<Trip> =
        listOf(
            trip,
            trip.copy(
                id = "preview-trip-2",
                startedAtMs = TRIP_START_MS - 86_400_000L,
                distanceM = 8_150.0,
                durationS = 1_090,
                smoothnessScore = 48,
            ),
            trip.copy(
                id = "preview-trip-3",
                startedAtMs = TRIP_START_MS - 172_800_000L,
                distanceM = 950.0,
                durationS = 260,
                smoothnessScore = null,
            ),
        )
}
