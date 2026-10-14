package com.mapmyroad.map

import com.mapmyroad.ui.TopLevelDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapConfigTest {
    @Test
    fun mapUrls_useHttps() {
        // Cleartext traffic is disabled by the network security config (SECURITY.md section 8).
        assertTrue(MapConfig.BASE_STYLE_URL.startsWith("https://"))
        assertTrue(MapConfig.OSM_COPYRIGHT_URL.startsWith("https://"))
    }

    @Test
    fun initialCamera_isInsidePune() {
        assertTrue(MapConfig.INITIAL_LAT in 18.3..18.7)
        assertTrue(MapConfig.INITIAL_LON in 73.6..74.1)
        assertTrue(MapConfig.INITIAL_ZOOM in 0.0..22.0)
    }

    @Test
    fun topLevelDestinations_haveUniqueRoutes_andStartAtHome() {
        val routes = TopLevelDestination.entries.map { it.route }
        assertEquals(routes.size, routes.toSet().size)
        assertEquals(TopLevelDestination.HOME, TopLevelDestination.start)
    }
}
