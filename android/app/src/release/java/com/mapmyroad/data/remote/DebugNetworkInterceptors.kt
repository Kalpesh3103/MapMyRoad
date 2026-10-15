package com.mapmyroad.data.remote

import okhttp3.Interceptor

/** Release builds log no network traffic. The debug twin in src/debug adds a redacting BASIC logger. */
object DebugNetworkInterceptors {
    fun create(): List<Interceptor> = emptyList()
}
