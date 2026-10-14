package com.mapmyroad.map

/**
 * Map constants for v1. From v2 the base map style URL comes from `GET /v1/config` (API_SPEC.md section 5.1),
 * so the hosted style can be swapped (for example to the Protomaps fallback) without an app release.
 */
object MapConfig {
    /** OpenFreeMap hosted style: free, no API key (TECH_STACK.md section 5). */
    const val BASE_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

    /** Linked from the in-app attribution (PRD PV-7). */
    const val OSM_COPYRIGHT_URL = "https://www.openstreetmap.org/copyright"

    /** Initial camera: Pune, the launch city. Replaced by the user's location once location access exists (M2-11). */
    const val INITIAL_LAT = 18.5204
    const val INITIAL_LON = 73.8567
    const val INITIAL_ZOOM = 11.0
}
