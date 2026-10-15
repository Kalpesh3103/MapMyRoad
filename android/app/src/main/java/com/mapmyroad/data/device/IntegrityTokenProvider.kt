package com.mapmyroad.data.device

import com.mapmyroad.domain.model.ApiResult

/** Obtains a Play Integrity token bound to a server challenge (API_SPEC.md section 2, SECURITY.md T8). */
interface IntegrityTokenProvider {
    /**
     * @param nonce the server's base64url nonce, passed through unchanged.
     * @return the token, or [com.mapmyroad.domain.model.ApiError.IntegrityFailed] when no token can be obtained.
     */
    suspend fun requestToken(nonce: String): ApiResult<String>
}
