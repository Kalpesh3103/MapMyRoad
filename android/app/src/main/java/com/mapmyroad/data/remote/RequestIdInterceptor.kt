package com.mapmyroad.data.remote

import okhttp3.Interceptor
import okhttp3.Response
import java.util.UUID

/**
 * Adds a random `X-Request-Id` per request (API_SPEC.md section 1) so a failure can be found in the server logs.
 * A caller that already set one (for example to correlate its own retries) keeps it. The ID is random and says
 * nothing about the device.
 */
class RequestIdInterceptor(
    private val newId: () -> String = { UUID.randomUUID().toString() },
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        if (request.header(HEADER) != null) return chain.proceed(request)
        return chain.proceed(request.newBuilder().header(HEADER, newId()).build())
    }

    companion object {
        const val HEADER = "X-Request-Id"
    }
}
