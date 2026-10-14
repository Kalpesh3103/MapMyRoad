package com.mapmyroad.detector

import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Small geodesy helpers. Allocation-free; coordinates in degrees. Not valid across the antimeridian (India only). */
internal object GeoMath {
    /** Mean Earth radius (IUGG). */
    const val EARTH_RADIUS_M = 6_371_008.8

    fun haversineM(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dPhi = Math.toRadians(lat2 - lat1)
        val dLambda = Math.toRadians(lon2 - lon1)
        val sinHalfPhi = sin(dPhi / 2.0)
        val sinHalfLambda = sin(dLambda / 2.0)
        val a = sinHalfPhi * sinHalfPhi + cos(phi1) * cos(phi2) * sinHalfLambda * sinHalfLambda
        return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
    }

    /** Initial great-circle bearing from point 1 to point 2, 0 until 360 (0 is north, clockwise). */
    fun bearingDeg(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
    ): Double {
        val phi1 = Math.toRadians(lat1)
        val phi2 = Math.toRadians(lat2)
        val dLambda = Math.toRadians(lon2 - lon1)
        val yComponent = sin(dLambda) * cos(phi2)
        val xComponent = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(dLambda)
        return normalizeDeg(Math.toDegrees(atan2(yComponent, xComponent)))
    }

    /** Latitude after moving [distanceM] along [bearingDeg] (local flat-earth step, fine for tens of meters). */
    fun offsetLat(
        lat: Double,
        distanceM: Double,
        bearingDeg: Double,
    ): Double = lat + Math.toDegrees(distanceM * cos(Math.toRadians(bearingDeg)) / EARTH_RADIUS_M)

    /** Longitude after moving [distanceM] along [bearingDeg] (local flat-earth step, fine for tens of meters). */
    fun offsetLon(
        lat: Double,
        lon: Double,
        distanceM: Double,
        bearingDeg: Double,
    ): Double = lon + Math.toDegrees(distanceM * sin(Math.toRadians(bearingDeg)) / (EARTH_RADIUS_M * cos(Math.toRadians(lat))))

    /** Smallest absolute difference between two headings, 0 to 180. */
    fun headingDifferenceDeg(
        a: Double,
        b: Double,
    ): Double {
        val diff = normalizeDeg(a - b)
        return if (diff > 180.0) 360.0 - diff else diff
    }

    /** Maps any angle to [0, 360). */
    fun normalizeDeg(angleDeg: Double): Double {
        val r = angleDeg % 360.0
        val positive = if (r < 0.0) r + 360.0 else r
        return if (positive >= 360.0) 0.0 else positive
    }
}
