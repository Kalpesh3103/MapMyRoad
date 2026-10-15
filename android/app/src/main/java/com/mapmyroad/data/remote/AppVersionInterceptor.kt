package com.mapmyroad.data.remote

import okhttp3.Interceptor
import okhttp3.Response

/**
 * Adds `X-App-Version` to every request (API_SPEC.md section 1). The server compares it with
 * `minSupportedAppVersion` and answers 426 when the app is too old.
 *
 * @param appVersion the plain version, for example `0.1.0`; see [headerValue].
 */
class AppVersionInterceptor(
    private val appVersion: String,
) : Interceptor {
    override fun intercept(chain: Interceptor.Chain): Response {
        val request =
            chain
                .request()
                .newBuilder()
                .header(HEADER, appVersion)
                .build()
        return chain.proceed(request)
    }

    companion object {
        const val HEADER = "X-App-Version"

        /** `0.1.0-debug` becomes `0.1.0`: build-type suffixes are not part of the version the server checks. */
        fun headerValue(versionName: String): String = versionName.substringBefore('-').trim()
    }
}
