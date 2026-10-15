package com.mapmyroad.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/** Raises the [UpdateGate] when the server answers 426 APP_UPDATE_REQUIRED to any request (API_SPEC.md section 6). */
class UpdateRequiredInterceptor(
    private val updateGate: UpdateGate,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val response = chain.proceed(chain.request())
        if (response.code == HTTP_UPGRADE_REQUIRED) updateGate.markUpdateRequired()
        return response
    }

    private companion object {
        const val HTTP_UPGRADE_REQUIRED = 426
    }
}
