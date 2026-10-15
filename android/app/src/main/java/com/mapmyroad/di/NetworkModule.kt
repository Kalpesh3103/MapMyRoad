package com.mapmyroad.di

import com.mapmyroad.BuildConfig
import com.mapmyroad.data.device.DeviceInfo
import com.mapmyroad.data.remote.ApiClients
import com.mapmyroad.data.remote.AuthTokens
import com.mapmyroad.data.remote.DebugNetworkInterceptors
import com.mapmyroad.data.remote.UpdateGate
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * The client for the public device endpoints and token refresh: same headers and update gate, but no
 * Authorization header and no token authenticator, so a refresh cannot recurse. Feature code uses the
 * unqualified [OkHttpClient] and [Retrofit].
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class UnauthenticatedHttp

/**
 * HTTP stack for the backend (API_SPEC.md, M5-1). Feature code injects [Retrofit] and [Json] and creates its own API
 * interface with `retrofit.create(...)`; it calls `DeviceSession.ensureRegistered()` before authenticated calls.
 */
@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {
    @Provides
    @Singleton
    fun provideJson(): Json = ApiClients.json()

    @Provides
    @Singleton
    fun provideOkHttpClient(
        deviceInfo: DeviceInfo,
        updateGate: UpdateGate,
        authTokens: AuthTokens,
    ): OkHttpClient =
        ApiClients.okHttpClient(
            appVersion = deviceInfo.appVersion,
            updateGate = updateGate,
            authTokens = authTokens,
            extraInterceptors = DebugNetworkInterceptors.create(),
        )

    @Provides
    @Singleton
    fun provideRetrofit(
        json: Json,
        client: OkHttpClient,
    ): Retrofit = ApiClients.retrofit(BuildConfig.API_BASE_URL, json, client)

    @Provides
    @Singleton
    @UnauthenticatedHttp
    fun provideUnauthenticatedOkHttpClient(
        deviceInfo: DeviceInfo,
        updateGate: UpdateGate,
    ): OkHttpClient =
        ApiClients.okHttpClient(
            appVersion = deviceInfo.appVersion,
            updateGate = updateGate,
            authTokens = null,
            extraInterceptors = DebugNetworkInterceptors.create(),
        )

    @Provides
    @Singleton
    @UnauthenticatedHttp
    fun provideUnauthenticatedRetrofit(
        json: Json,
        @UnauthenticatedHttp client: OkHttpClient,
    ): Retrofit = ApiClients.retrofit(BuildConfig.API_BASE_URL, json, client)
}
