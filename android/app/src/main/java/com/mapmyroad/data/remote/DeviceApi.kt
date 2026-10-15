package com.mapmyroad.data.remote

import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.POST

/**
 * The public device endpoints (API_SPEC.md section 5.2). Served by the unauthenticated client (no Authorization
 * header, no token authenticator), so a refresh can never recurse into another refresh.
 */
interface DeviceAuthApi {
    @POST("v1/devices/challenge")
    suspend fun challenge(): ChallengeResponse

    /** Answers `201`. 403 INTEGRITY_FAILED for a bad nonce or verdict. */
    @POST("v1/devices")
    suspend fun register(
        @Body request: RegisterDeviceRequest,
    ): RegisterDeviceResponse

    /** 401 UNAUTHORIZED for an unknown, expired, revoked or reused refresh token. */
    @POST("v1/devices/token")
    suspend fun refresh(
        @Body request: RefreshTokenRequest,
    ): TokenResponse
}

/** The authenticated device endpoints, served by the main client. */
interface DeviceApi {
    /** "Delete my data": `204` on success. The response is returned as is; the caller checks the status. */
    @DELETE("v1/devices/me")
    suspend fun deleteMe(): Response<Unit>
}
