package com.mapmyroad.data.remote

import kotlinx.serialization.json.Json
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.kotlinx.serialization.asConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Builds the HTTP stack (API_SPEC.md sections 1, 2 and 6). Used by the Hilt network module and by the tests, so
 * the tests exercise the same interceptor order as the app.
 *
 * Retries: the client does not retry POSTs by itself; callers (workers) decide from the
 * [com.mapmyroad.domain.model.ApiError]. OkHttp's default `retryOnConnectionFailure` stays on for connection-level
 * failures.
 */
object ApiClients {
    private const val CONNECT_TIMEOUT_S = 10L
    private const val READ_TIMEOUT_S = 20L
    private const val WRITE_TIMEOUT_S = 20L

    /** Clients must ignore unknown fields (API_SPEC.md section 7). */
    fun json(): Json =
        Json {
            ignoreUnknownKeys = true
            explicitNulls = false
            encodeDefaults = true
        }

    /**
     * The OkHttp client. With [authTokens] it is the main, authenticated client: interceptors in the order
     * app version, request ID, auth, update gate, then [extraInterceptors] (debug logging), plus the 401
     * [TokenAuthenticator]. Without it, it is the client for the public device endpoints and the token refresh.
     *
     * Each call builds a new client with its own dispatcher. That matters: the authenticator blocks an OkHttp
     * thread of the main client while it refreshes, so the refresh must not queue on that same dispatcher.
     */
    fun okHttpClient(
        appVersion: String,
        updateGate: UpdateGate,
        authTokens: AuthTokens?,
        extraInterceptors: List<Interceptor> = emptyList(),
    ): OkHttpClient {
        val builder =
            OkHttpClient
                .Builder()
                .connectTimeout(CONNECT_TIMEOUT_S, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_S, TimeUnit.SECONDS)
                .writeTimeout(WRITE_TIMEOUT_S, TimeUnit.SECONDS)
                .addInterceptor(AppVersionInterceptor(appVersion))
                .addInterceptor(RequestIdInterceptor())
        if (authTokens != null) {
            builder.addInterceptor(AuthInterceptor(authTokens))
            builder.authenticator(TokenAuthenticator(authTokens))
        }
        builder.addInterceptor(UpdateRequiredInterceptor(updateGate))
        extraInterceptors.forEach { builder.addInterceptor(it) }
        return builder.build()
    }

    fun retrofit(
        baseUrl: String,
        json: Json,
        client: OkHttpClient,
    ): Retrofit =
        Retrofit
            .Builder()
            .baseUrl(normalizedBaseUrl(baseUrl))
            .addConverterFactory(json.asConverterFactory("application/json".toMediaType()))
            .client(client)
            .build()

    /** Exactly one trailing slash, as Retrofit requires for relative endpoint paths like `v1/devices`. */
    fun normalizedBaseUrl(baseUrl: String): String = baseUrl.trim().trimEnd('/') + "/"
}
