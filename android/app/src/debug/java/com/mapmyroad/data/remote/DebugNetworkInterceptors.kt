package com.mapmyroad.data.remote

import okhttp3.Interceptor
import okhttp3.logging.HttpLoggingInterceptor
import timber.log.Timber

/**
 * Debug builds only (src/release has an empty twin with the same signature, so release never references the
 * logging library). BASIC level logs the request line, status and timing: never headers or bodies. Query strings
 * are cut because some carry coordinates or search text (AGENTS.md section 6).
 */
object DebugNetworkInterceptors {
    private val queryString = Regex("""\?\S*""")

    fun create(): List<Interceptor> {
        val logger =
            object : HttpLoggingInterceptor.Logger {
                override fun log(message: String) {
                    Timber.tag("OkHttp").d(message.replace(queryString, "?***"))
                }
            }
        val logging =
            HttpLoggingInterceptor(logger).apply {
                level = HttpLoggingInterceptor.Level.BASIC
                redactHeader("Authorization")
            }
        return listOf(logging)
    }
}
