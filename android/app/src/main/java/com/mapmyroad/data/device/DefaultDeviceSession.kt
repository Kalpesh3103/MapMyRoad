package com.mapmyroad.data.device

import com.mapmyroad.data.remote.DeviceApi
import com.mapmyroad.data.remote.DeviceAuthApi
import com.mapmyroad.data.remote.RegisterDeviceRequest
import com.mapmyroad.data.remote.UpdateGate
import com.mapmyroad.data.remote.apiCall
import com.mapmyroad.domain.device.DeviceSession
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DeviceSession] backed by the device endpoints (API_SPEC.md sections 2 and 5.2) and the [CredentialStore].
 *
 * Registration and deletion are serialized by [sessionMutex], so concurrent `ensureRegistered()` calls register
 * once and a deletion waits for a registration in progress. Token refresh is not done here: the HTTP layer does it
 * lazily on 401 ([DeviceTokenManager]).
 */
@Singleton
class DefaultDeviceSession
    @Inject
    constructor(
        private val store: CredentialStore,
        private val authApi: DeviceAuthApi,
        private val deviceApi: DeviceApi,
        private val integrity: IntegrityTokenProvider,
        private val updateGate: UpdateGate,
        private val deviceInfo: DeviceInfo,
        private val clock: EpochClock,
    ) : DeviceSession {
        private val sessionMutex = Mutex()

        override val updateRequired: StateFlow<Boolean> = updateGate.updateRequired

        override suspend fun isRegistered(): Boolean = storedOrNull() != null

        override suspend fun ensureRegistered(): ApiResult<Unit> =
            sessionMutex.withLock {
                guardStorage {
                    if (store.get() != null) ApiResult.Success(Unit) else register()
                }
            }

        override suspend fun deleteRemoteData(): ApiResult<Unit> =
            sessionMutex.withLock {
                guardStorage {
                    if (store.get() == null) ApiResult.Success(Unit) else deleteRegisteredDevice()
                }
            }

        override suspend fun clearCredentials() {
            store.clear()
        }

        private suspend fun register(): ApiResult<Unit> {
            val challenge =
                when (val result = apiCall { authApi.challenge() }) {
                    is ApiResult.Success -> result.value
                    is ApiResult.Failure -> return result
                }
            val integrityToken =
                when (val result = integrity.requestToken(challenge.nonce)) {
                    is ApiResult.Success -> result.value
                    is ApiResult.Failure -> return result
                }
            val request =
                RegisterDeviceRequest(
                    integrityToken = integrityToken,
                    nonce = challenge.nonce,
                    appVersion = deviceInfo.appVersion,
                    platform = RegisterDeviceRequest.PLATFORM_ANDROID,
                    osApiLevel = deviceInfo.osApiLevel,
                )
            val registered =
                when (val result = apiCall { authApi.register(request) }) {
                    is ApiResult.Success -> result.value
                    is ApiResult.Failure -> return result
                }
            store.save(DeviceCredentials.fromRegistration(registered, clock.nowMs()))
            Timber.i("Device registered")
            return ApiResult.Success(Unit)
        }

        /**
         * Credentials are cleared only when the server confirms the device is gone: 204, 404, or 401 after the
         * HTTP layer's refresh was itself rejected (which already cleared them). Any other failure (offline, 5xx,
         * a refresh that could not reach the server) keeps them, otherwise the server data could never be deleted.
         */
        private suspend fun deleteRegisteredDevice(): ApiResult<Unit> {
            val result =
                apiCall<Unit> {
                    val response = deviceApi.deleteMe()
                    if (!response.isSuccessful) throw HttpException(response)
                }
            return when {
                result is ApiResult.Success || (result is ApiResult.Failure && result.error == ApiError.NotFound) -> {
                    store.clear()
                    Timber.i("Device deleted on the server")
                    ApiResult.Success(Unit)
                }

                result is ApiResult.Failure && result.error == ApiError.Unauthorized && store.get() == null -> {
                    Timber.i("Device already unknown to the server")
                    ApiResult.Success(Unit)
                }

                else -> {
                    Timber.w("Deleting the device on the server failed, keeping credentials")
                    result
                }
            }
        }

        private suspend fun storedOrNull(): DeviceCredentials? =
            try {
                store.get()
            } catch (e: IOException) {
                Timber.w("Reading credentials failed")
                null
            }

        // Storage failures (disk, Keystore) become results instead of crashing the caller.
        private inline fun guardStorage(block: () -> ApiResult<Unit>): ApiResult<Unit> =
            try {
                block()
            } catch (e: IOException) {
                Timber.w("Credential storage failed")
                ApiResult.Failure(ApiError.Server(status = null))
            } catch (e: GeneralSecurityException) {
                Timber.w("Credential encryption failed")
                ApiResult.Failure(ApiError.Server(status = null))
            }
    }
