package com.mapmyroad.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `Authorization: Bearer <access token>` to every non-public request (API_SPEC.md section 2). Requests go out
 * without the header when the install is not registered; the server answers 401 and the caller gets
 * [com.mapmyroad.domain.model.ApiError.Unauthorized]. Callers should call `DeviceSession.ensureRegistered()` first.
 */
class AuthInterceptor(
    private val tokens: AuthTokens,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) != null || PublicEndpoints.isPublic(request.method, request.url.encodedPath)) {
            return chain.proceed(request)
        }
        val accessToken = tokens.currentAccessToken() ?: return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER, bearer(accessToken)).build())
    }

    companion object {
        const val HEADER = "Authorization"
        private const val BEARER_PREFIX = "Bearer "

        fun bearer(accessToken: String): String = BEARER_PREFIX + accessToken

        /** The token in a `Bearer` header value, or null for any other scheme. */
        fun tokenFrom(headerValue: String?): String? =
            headerValue
                ?.takeIf { it.startsWith(BEARER_PREFIX) }
                ?.removePrefix(BEARER_PREFIX)
                ?.takeIf { it.isNotEmpty() }
    }
}
