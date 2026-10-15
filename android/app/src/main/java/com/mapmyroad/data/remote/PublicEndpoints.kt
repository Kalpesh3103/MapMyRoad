package com.mapmyroad.data.remote

/**
 * The endpoints that take no access token (API_SPEC.md section 2), mirroring the backend's `PublicEndpoints`:
 * config, tiles, challenge, registration and refresh (the refresh token is its own credential). Decided by method
 * and path, so a base URL with a path prefix (a reverse proxy) still matches.
 */
internal object PublicEndpoints {
    private const val VERSION_SEGMENT = "/v1/"

    fun isPublic(
        method: String,
        encodedPath: String,
    ): Boolean {
        val start = encodedPath.indexOf(VERSION_SEGMENT)
        if (start < 0) return false
        val path = encodedPath.substring(start).trimEnd('/')
        return when (method.uppercase()) {
            "GET" -> path == "/v1/config" || path == "/v1/tiles" || path.startsWith("/v1/tiles/")
            "POST" -> path == "/v1/devices" || path == "/v1/devices/challenge" || path == "/v1/devices/token"
            else -> false
        }
    }
}
