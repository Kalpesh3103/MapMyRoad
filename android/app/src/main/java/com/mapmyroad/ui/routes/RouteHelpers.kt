package com.mapmyroad.ui.routes

import com.mapmyroad.detector.VehicleType
import com.mapmyroad.domain.model.CoverageLevel
import com.mapmyroad.domain.model.RouteCandidate
import com.mapmyroad.domain.model.RouteCoverage
import com.mapmyroad.domain.model.RouteLabel
import java.util.Locale
import kotlin.math.roundToInt

/** How a candidate's hazards compare with the fastest route, for the trade-off sentence (UI_UX S8). */
enum class HazardComparison {
    FAR_FEWER_SEVERE,
    FEWER_SEVERE,
    MORE_SEVERE,
    FEWER_ROUGH,
    MORE_ROUGH,
    SIMILAR,

    /** One of the two routes has no road data, so nothing honest can be said about hazards. */
    UNKNOWN,
}

/**
 * Trade-off of one candidate against the fastest, for example "4 min slower, far fewer severe potholes".
 * [minutesDifference] is positive when the candidate is slower, negative when faster and 0 when about the same.
 */
data class TradeOff(
    val minutesDifference: Int,
    val hazards: HazardComparison,
)

/** Pure helpers for the route candidates screen (M5-8). No Android imports, so they run in JVM tests. */
object RouteTradeOffs {
    private const val S_PER_MIN = 60.0

    /** "Far fewer" when the candidate has at most half the severe hazards of the fastest (an initial value). */
    private const val FAR_FEWER_RATIO = 0.5

    /** The reference route: the one labeled fastest, otherwise the shortest by time. */
    fun fastestOf(candidates: List<RouteCandidate>): RouteCandidate? =
        candidates.firstOrNull { RouteLabel.FASTEST in it.labels } ?: candidates.minByOrNull { it.durationS }

    /**
     * Trade-off of [candidate] against [fastest], or null for the fastest route itself. Hazards are compared on
     * severe counts first, then moderate ("rough") counts. When either route has no data the comparison is
     * [HazardComparison.UNKNOWN].
     */
    fun tradeOff(
        candidate: RouteCandidate,
        fastest: RouteCandidate,
        fallbackCoverage: RouteCoverage? = null,
    ): TradeOff? {
        if (candidate.id == fastest.id) return null
        val minutes = ((candidate.durationS - fastest.durationS) / S_PER_MIN).roundToInt()
        val hasNoData =
            coverageOf(candidate, fallbackCoverage) == CoverageLevel.NONE ||
                coverageOf(fastest, fallbackCoverage) == CoverageLevel.NONE
        val hazards = if (hasNoData) HazardComparison.UNKNOWN else compareHazards(candidate, fastest)
        return TradeOff(minutesDifference = minutes, hazards = hazards)
    }

    fun compareHazards(
        candidate: RouteCandidate,
        fastest: RouteCandidate,
    ): HazardComparison =
        when {
            candidate.severeCount < fastest.severeCount ->
                if (candidate.severeCount <= fastest.severeCount * FAR_FEWER_RATIO) {
                    HazardComparison.FAR_FEWER_SEVERE
                } else {
                    HazardComparison.FEWER_SEVERE
                }
            candidate.severeCount > fastest.severeCount -> HazardComparison.MORE_SEVERE
            candidate.moderateCount < fastest.moderateCount -> HazardComparison.FEWER_ROUGH
            candidate.moderateCount > fastest.moderateCount -> HazardComparison.MORE_ROUGH
            else -> HazardComparison.SIMILAR
        }

    /** Per-candidate coverage when the server sent it, otherwise the top-level coverage. */
    fun coverageOf(
        candidate: RouteCandidate,
        fallback: RouteCoverage?,
    ): CoverageLevel? = (candidate.coverage ?: fallback)?.level

    /** "Limited data on this route" is shown for partial or no coverage (UI_UX S8, section 4.2). */
    fun isLimitedData(
        candidate: RouteCandidate,
        fallback: RouteCoverage?,
    ): Boolean = coverageOf(candidate, fallback).let { it == CoverageLevel.PARTIAL || it == CoverageLevel.NONE }

    /** Labels in a fixed display order, so "Smoothest · Balanced" reads the same on every card. */
    fun orderedLabels(labels: Set<RouteLabel>): List<RouteLabel> =
        listOf(RouteLabel.FASTEST, RouteLabel.SMOOTHEST, RouteLabel.BALANCED).filter { it in labels }
}

/** Whole hours and minutes of a typical ETA, rounded to the nearest minute and at least 1 minute. */
data class EtaParts(
    val hours: Int,
    val minutes: Int,
) {
    companion object {
        private const val S_PER_MIN = 60.0
        private const val MIN_PER_H = 60

        fun of(durationS: Int): EtaParts {
            val totalMinutes = (durationS.coerceAtLeast(0) / S_PER_MIN).roundToInt().coerceAtLeast(1)
            return EtaParts(hours = totalMinutes / MIN_PER_H, minutes = totalMinutes % MIN_PER_H)
        }
    }
}

/**
 * Comfort versus speed slider (PRD RT-3). The wireframe puts Comfort on the left and Speed on the right
 * (UI_UX S8), so slider position 0 is "prefer comfort". The API's preference is the opposite way round:
 * 1 prefers the smoothest route (API_SPEC.md 5.8).
 */
object RoutePreference {
    const val DEFAULT = 0.5

    fun fromSliderPosition(position: Float): Double = (1.0 - position.toDouble()).coerceIn(0.0, 1.0)

    fun toSliderPosition(preference: Double): Float = (1.0 - preference).coerceIn(0.0, 1.0).toFloat()
}

/**
 * Hand-off to Google Maps (M5-9, PRD RT-4) with a Maps URL, which needs no API key:
 * `https://www.google.com/maps/dir/?api=1&origin=LAT,LON&destination=LAT,LON&travelmode=MODE`.
 * Google Maps picks its own path; it cannot be told to follow our candidate geometry, which the screen says.
 */
object GoogleMapsHandoff {
    const val GOOGLE_MAPS_PACKAGE = "com.google.android.apps.maps"
    private const val BASE_URL = "https://www.google.com/maps/dir/?api=1"

    /**
     * "two-wheeler" is a documented Maps URLs travel mode. Google Maps supports it in India (the launch region);
     * where it is not supported, Google Maps may fall back to another mode.
     */
    fun travelMode(vehicleType: VehicleType?): String =
        when (vehicleType) {
            VehicleType.MOTORCYCLE, VehicleType.SCOOTER -> "two-wheeler"
            VehicleType.CAR, VehicleType.AUTO_RICKSHAW, null -> "driving"
        }

    /** Without an origin, Google Maps starts from the user's current location. */
    fun directionsUrl(
        originLat: Double?,
        originLon: Double?,
        destinationLat: Double,
        destinationLon: Double,
        vehicleType: VehicleType?,
    ): String {
        val builder = StringBuilder(BASE_URL)
        if (originLat != null && originLon != null) {
            builder.append("&origin=").append(coordinates(originLat, originLon))
        }
        builder
            .append("&destination=")
            .append(coordinates(destinationLat, destinationLon))
            .append("&travelmode=")
            .append(travelMode(vehicleType))
        return builder.toString()
    }

    /** Six decimals with a dot, whatever the device locale: a comma decimal would break the "lat,lon" pair. */
    fun coordinates(
        lat: Double,
        lon: Double,
    ): String = String.format(Locale.ROOT, "%.6f,%.6f", lat, lon)
}
