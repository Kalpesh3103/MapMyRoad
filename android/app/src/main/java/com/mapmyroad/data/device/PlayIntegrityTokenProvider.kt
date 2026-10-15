package com.mapmyroad.data.device

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManager
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.mapmyroad.BuildConfig
import com.mapmyroad.domain.model.ApiError
import com.mapmyroad.domain.model.ApiResult
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.tasks.await
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Classic Play Integrity request with the server nonce (API_SPEC.md section 2).
 *
 * Debug builds: the Integrity API usually fails for sideloaded builds and on devices without Play services, so a
 * failure returns the placeholder [DEBUG_PLACEHOLDER_TOKEN]. Only the backend's local profile accepts it;
 * production enforces the verdict. Release builds never use the placeholder: a failure is
 * [ApiError.IntegrityFailed].
 */
@Singleton
class PlayIntegrityTokenProvider
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
    ) : IntegrityTokenProvider {
        private val integrityManager: IntegrityManager by lazy { IntegrityManagerFactory.create(context) }

        override suspend fun requestToken(nonce: String): ApiResult<String> =
            try {
                val request =
                    IntegrityTokenRequest
                        .builder()
                        .setNonce(nonce)
                        .build()
                val response = integrityManager.requestIntegrityToken(request).await()
                ApiResult.Success(response.token())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // The Task fails with IntegrityServiceException or other exceptions from Play services; all mean
                // "no token now".
                if (BuildConfig.DEBUG) {
                    // No exception details: the message may include request data.
                    Timber.w("Play Integrity unavailable, using the debug placeholder token")
                    ApiResult.Success(DEBUG_PLACEHOLDER_TOKEN)
                } else {
                    Timber.w("Play Integrity request failed")
                    ApiResult.Failure(ApiError.IntegrityFailed)
                }
            }

        companion object {
            /** Accepted only by a backend with integrity enforcement off (local profile). */
            const val DEBUG_PLACEHOLDER_TOKEN = "debug-unattested"
        }
    }
