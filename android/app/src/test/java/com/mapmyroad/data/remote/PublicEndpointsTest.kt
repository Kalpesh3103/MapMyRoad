package com.mapmyroad.data.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PublicEndpointsTest {
    @Test
    fun publicEndpoints_matchTheBackendList() {
        assertTrue(PublicEndpoints.isPublic("GET", "/v1/config"))
        assertTrue(PublicEndpoints.isPublic("GET", "/v1/tiles/tilejson.json"))
        assertTrue(PublicEndpoints.isPublic("GET", "/v1/tiles/14/11500/7300.mvt"))
        assertTrue(PublicEndpoints.isPublic("POST", "/v1/devices/challenge"))
        assertTrue(PublicEndpoints.isPublic("POST", "/v1/devices"))
        assertTrue(PublicEndpoints.isPublic("POST", "/v1/devices/token"))
    }

    @Test
    fun otherEndpoints_needAuthentication() {
        assertFalse(PublicEndpoints.isPublic("DELETE", "/v1/devices/me"))
        assertFalse(PublicEndpoints.isPublic("POST", "/v1/events"))
        assertFalse(PublicEndpoints.isPublic("GET", "/v1/hazards"))
        assertFalse(PublicEndpoints.isPublic("POST", "/v1/routes"))
        assertFalse(PublicEndpoints.isPublic("GET", "/v1/geocode"))
        assertFalse(PublicEndpoints.isPublic("POST", "/v1/config"))
        assertFalse(PublicEndpoints.isPublic("GET", "/v1/devices"))
        assertFalse(PublicEndpoints.isPublic("GET", "/v1/tilesets"))
    }

    @Test
    fun pathPrefixAndTrailingSlash_areTolerated() {
        assertTrue(PublicEndpoints.isPublic("POST", "/api/v1/devices/"))
        assertTrue(PublicEndpoints.isPublic("get", "/proxy/v1/config"))
        assertFalse(PublicEndpoints.isPublic("DELETE", "/api/v1/devices/me"))
    }
}
