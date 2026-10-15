package com.mapmyroad.data.routing

import com.mapmyroad.domain.model.Place
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.abs

/**
 * Planning state that lives for the app process only (UI_UX S8). Recent destinations are kept in memory and never
 * written to disk: a list of places someone travels to is sensitive, and v2 does not need it across restarts
 * (decision noted for DECISIONS.md). Also remembers whether the "typical ETA" explainer was shown this session.
 */
@Singleton
class RoutingSession
    @Inject
    constructor() {
        private val recents = MutableStateFlow<List<Place>>(emptyList())
        val recentDestinations: StateFlow<List<Place>> = recents.asStateFlow()

        private val typicalEtaExplained = MutableStateFlow(false)
        val isTypicalEtaExplained: StateFlow<Boolean> = typicalEtaExplained.asStateFlow()

        fun addRecent(place: Place) {
            recents.update { current -> RecentPlaces.add(current, place, MAX_RECENTS) }
        }

        fun markTypicalEtaExplained() {
            typicalEtaExplained.value = true
        }

        companion object {
            const val MAX_RECENTS = 5
        }
    }

/** Pure list logic for [RoutingSession], unit tested. */
object RecentPlaces {
    /** About 11 m: two picks closer than this are the same destination. */
    private const val SAME_PLACE_EPSILON_DEG = 0.0001

    /** Puts [place] first, removes an earlier copy of the same destination and keeps at most [max] entries. */
    fun add(
        current: List<Place>,
        place: Place,
        max: Int,
    ): List<Place> = (listOf(place) + current.filterNot { isSame(it, place) }).take(max)

    private fun isSame(
        a: Place,
        b: Place,
    ): Boolean =
        a.name == b.name &&
            abs(a.lat - b.lat) < SAME_PLACE_EPSILON_DEG &&
            abs(a.lon - b.lon) < SAME_PLACE_EPSILON_DEG
}
