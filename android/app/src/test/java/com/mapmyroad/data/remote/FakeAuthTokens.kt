package com.mapmyroad.data.remote

import java.util.concurrent.CopyOnWriteArrayList

/** [AuthTokens] for HTTP-layer tests: a fixed current token and a configurable refresh outcome. */
class FakeAuthTokens(
    @Volatile var accessToken: String?,
    @Volatile var refreshedToken: String? = null,
) : AuthTokens {
    val rejectedTokens = CopyOnWriteArrayList<String>()

    override fun currentAccessToken(): String? = accessToken

    override fun refreshAfterRejection(rejectedAccessToken: String): String? {
        rejectedTokens += rejectedAccessToken
        val refreshed = refreshedToken
        if (refreshed != null) accessToken = refreshed
        return refreshed
    }
}
