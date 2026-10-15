package com.mapmyroad.domain.device

import com.mapmyroad.domain.model.ApiResult
import kotlinx.coroutines.flow.StateFlow

/**
 * The anonymous backend identity of this install (API_SPEC.md section 2, ADR-0007). Registration happens lazily,
 * the first time a feature needs the backend, never at app start, and only after the user opted in to a v2 feature
 * that needs the network. Tokens are stored encrypted with an Android Keystore key and never logged.
 */
interface DeviceSession {
    /** True after the server answered 426 APP_UPDATE_REQUIRED; the app shows the update screen. */
    val updateRequired: StateFlow<Boolean>

    /** True when this install has backend credentials stored. */
    suspend fun isRegistered(): Boolean

    /** Registers with Play Integrity if needed. Safe to call repeatedly. */
    suspend fun ensureRegistered(): ApiResult<Unit>

    /**
     * "Delete my data" on the server (DELETE /v1/devices/me, PRD PV-3), then forgets the stored credentials.
     * Succeeds without a network call when this install never registered.
     */
    suspend fun deleteRemoteData(): ApiResult<Unit>

    /** Forgets stored credentials locally (after deletion, or when the user resets the install ID). */
    suspend fun clearCredentials()
}
