package com.mapmyroad.data.remote

/**
 * Access-token source for the HTTP layer. Both functions block and are called on OkHttp threads only, never on the
 * main thread. Implemented by [com.mapmyroad.data.device.DeviceTokenManager].
 */
interface AuthTokens {
    /** The stored access token, or null when this install is not registered. */
    fun currentAccessToken(): String?

    /**
     * Called after the server rejected [rejectedAccessToken] with 401. Returns a token worth one retry: the token
     * another request already refreshed to, or a freshly refreshed one. Returns null when no retry makes sense
     * (not registered, refresh failed); after a refresh rejected with 401 the credentials are cleared.
     */
    fun refreshAfterRejection(rejectedAccessToken: String): String?
}
