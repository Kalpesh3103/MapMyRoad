package com.mapmyroad.data.device

import com.mapmyroad.data.remote.AuthTokens
import com.mapmyroad.data.remote.DeviceAuthApi
import com.mapmyroad.data.remote.RefreshTokenRequest
import com.mapmyroad.data.remote.apiCall
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Supplies access tokens to the HTTP layer and refreshes them (API_SPEC.md sections 2 and 6, SECURITY.md T7).
 *
 * Refreshes are serialized by [refreshMutex]. A request that failed with a token that is no longer the stored one
 * reuses the stored token instead of refreshing again, so N concurrent 401s cause one refresh. That matters because
 * refresh tokens rotate and reusing a rotated one revokes the device on the server.
 *
 * Outcomes of the refresh call: success stores the new pair; 401 means the server no longer accepts this install,
 * so the credentials are cleared and the next `ensureRegistered()` registers again; anything else (offline, 429,
 * 5xx) keeps the credentials so a later call can try again.
 *
 * The blocking [AuthTokens] functions run on OkHttp threads (interceptor and authenticator), never on the main
 * thread. The refresh call uses the unauthenticated client, which has its own dispatcher.
 */
@Singleton
class DeviceTokenManager
    @Inject
    constructor(
        private val store: CredentialStore,
        private val authApi: DeviceAuthApi,
        private val clock: EpochClock,
    ) : AuthTokens {
        private val refreshMutex = Mutex()

        override fun currentAccessToken(): String? = runBlocking { store.get()?.accessToken }

        override fun refreshAfterRejection(rejectedAccessToken: String): String? = runBlocking { refresh(rejectedAccessToken) }

        /** Suspending core of [refreshAfterRejection]. */
        suspend fun refresh(rejectedAccessToken: String): String? =
            refreshMutex.withLock {
                val current = store.get() ?: return@withLock null
                if (current.accessToken != rejectedAccessToken) {
                    // Another request refreshed while this one waited: retry with that token.
                    return@withLock current.accessToken
                }
                when (val result = apiCall { authApi.refresh(RefreshTokenRequest(current.refreshToken)) }) {
                    is ApiResult.Success -> {
                        val refreshed = current.refreshed(result.value, clock.nowMs())
                        var stored: DeviceCredentials? = null
                        try {
                            store.update { latest ->
                                // Store only if nobody cleared or replaced the credentials meanwhile (deletion, reset).
                                val next = if (latest?.refreshToken == current.refreshToken) refreshed else latest
                                stored = next
                                next
                            }
                        } catch (e: GeneralSecurityException) {
                            // Thrown on an OkHttp thread, where only IOException is handled: report "no token" instead.
                            Timber.w("Storing refreshed credentials failed")
                            return@withLock null
                        }
                        stored?.accessToken
                    }

                    is ApiResult.Failure -> {
                        if (result.error == ApiError.Unauthorized) {
                            Timber.w("Refresh token rejected, clearing credentials")
                            store.update { latest -> if (latest?.refreshToken == current.refreshToken) null else latest }
                        } else {
                            Timber.w("Token refresh failed, keeping credentials")
                        }
                        null
                    }
                }
            }
    }
