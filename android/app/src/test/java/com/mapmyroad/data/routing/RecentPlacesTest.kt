package com.mapmyroad.data.routing

import com.mapmyroad.domain.model.Place
import org.junit.Assert.assertEquals
import org.junit.Test

class RecentPlacesTest {
    private fun place(
        name: String,
        lat: Double = 18.5,
        lon: Double = 73.8,
    ) = Place(name = name, displayName = name, lat = lat, lon = lon, type = null)

    @Test
    fun newestFirst_andCapped() {
        var recents = emptyList<Place>()
        (1..7).forEach { recents = RecentPlaces.add(recents, place("P$it", lat = 18.0 + it), max = 5) }
        assertEquals(listOf("P7", "P6", "P5", "P4", "P3"), recents.map { it.name })
    }

    @Test
    fun choosingAgain_movesToFront_withoutDuplicate() {
        val a = place("A", lat = 18.1)
        val b = place("B", lat = 18.2)
        val recents = RecentPlaces.add(RecentPlaces.add(RecentPlaces.add(emptyList(), a, 5), b, 5), a, 5)
        assertEquals(listOf("A", "B"), recents.map { it.name })
    }

    @Test
    fun sameNameElsewhere_isADifferentPlace() {
        val recents = RecentPlaces.add(listOf(place("Bus stop", lat = 18.1)), place("Bus stop", lat = 18.3), 5)
        assertEquals(2, recents.size)
    }

    @Test
    fun session_keepsRecentsInMemory_andExplainerFlag() {
        val session = RoutingSession()
        session.addRecent(place("A"))
        assertEquals(listOf("A"), session.recentDestinations.value.map { it.name })
        assertEquals(false, session.isTypicalEtaExplained.value)
        session.markTypicalEtaExplained()
        assertEquals(true, session.isTypicalEtaExplained.value)
    }
}
