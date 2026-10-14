package com.mapmyroad.sensing.policy

import com.mapmyroad.detector.TripScoring

/** Distance between two coordinates in meters. A fun interface (not a lambda type) to avoid boxing. */
fun interface DistanceCalculator {
    fun distanceM(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double

    companion object {
        /** Great-circle distance from the detector module. */
        val HAVERSINE: DistanceCalculator =
            DistanceCalculator { lat1, lon1, lat2, lon2 -> TripScoring.haversineM(lat1, lon1, lat2, lon2) }
    }
}
