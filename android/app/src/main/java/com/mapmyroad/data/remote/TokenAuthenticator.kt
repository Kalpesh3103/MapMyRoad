package com.mapmyroad.data.remote

import okhttp3.Authenticator
import okhttp3.Request
import okhttp3.Response
import okhttp3.Route

/**
 * Handles 401 on authenticated requests (API_SPEC.md section 6): refreshes the access token once and retries the
 * request with the new token. Never more than one refresh per request: if the request already went through a 401,
 * the second 401 is returned to the caller as [com.mapmyroad.domain.model.ApiError.Unauthorized].
 *
 * Every 401 for the token we sent triggers the refresh, not only `TOKEN_EXPIRED`: an `UNAUTHORIZED` access token
 * (for example after a signing key rotation) is also recovered by a refresh, and a refresh rejected with 401 clears
 * the credentials so the next `ensureRegistered()` registers again. Either way the server has the final word.
 *
 * Retries returned from here skip the application interceptors, so the retried request is built from the one
 * already sent, which carries `X-App-Version` and `X-Request-Id`.
 */
class TokenAuthenticator(
    private val tokens: AuthTokens,
) : Authenticator {
    override fun authenticate(
        route: Route?,
        response: Response,
    ): Request? {
        if (hasPriorUnauthorized(response)) return null
        val request = response.request
        if (PublicEndpoints.isPublic(request.method, request.url.encodedPath)) return null
        val rejectedAccessToken = AuthInterceptor.tokenFrom(request.header(AuthInterceptor.HEADER)) ?: return null
        val accessToken = tokens.refreshAfterRejection(rejectedAccessToken) ?: return null
        return request
            .newBuilder()
            .header(AuthInterceptor.HEADER, AuthInterceptor.bearer(accessToken))
            .build()
    }

    private fun hasPriorUnauthorized(response: Response): Boolean {
        var prior = response.priorResponse
        while (prior != null) {
            if (prior.code == HTTP_UNAUTHORIZED) return true
            prior = prior.priorResponse
        }
        return false
    }

    private companion object {
        const val HTTP_UNAUTHORIZED = 401
    }
}
