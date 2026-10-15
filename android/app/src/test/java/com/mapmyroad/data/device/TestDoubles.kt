package com.mapmyroad.data.device

import com.mapmyroad.domain.model.ApiResult
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.security.GeneralSecurityException
import java.util.concurrent.CopyOnWriteArrayList

/** In-memory [CredentialStore]. */
class FakeCredentialStore(
    initial: DeviceCredentials? = null,
) : CredentialStore {
    private val mutex = Mutex()

    @Volatile
    var value: DeviceCredentials? = initial
        private set

    override suspend fun get(): DeviceCredentials? = mutex.withLock { value }

    override suspend fun save(credentials: DeviceCredentials) {
        mutex.withLock { value = credentials }
    }

    override suspend fun update(transform: (DeviceCredentials?) -> DeviceCredentials?) {
        mutex.withLock { value = transform(value) }
    }

    override suspend fun clear() {
        mutex.withLock { value = null }
    }
}

/** Reversible stand-in for the Keystore cipher: a marker byte plus XOR, so the plaintext is not visible on disk. */
class FakeCredentialCipher : CredentialCipher {
    @Volatile
    var failDecrypt = false

    @Volatile
    var deleteKeyCalls = 0

    override fun encrypt(plaintext: ByteArray): ByteArray = byteArrayOf(MARKER) + scramble(plaintext)

    override fun decrypt(ciphertext: ByteArray): ByteArray {
        if (failDecrypt || ciphertext.firstOrNull() != MARKER) throw GeneralSecurityException("cannot decrypt")
        return scramble(ciphertext.copyOfRange(1, ciphertext.size))
    }

    override fun deleteKey() {
        deleteKeyCalls++
    }

    private fun scramble(bytes: ByteArray): ByteArray = ByteArray(bytes.size) { (bytes[it].toInt() xor 0x5A).toByte() }

    private companion object {
        const val MARKER: Byte = 0x7F
    }
}

/** [IntegrityTokenProvider] with a configurable result that records the nonces it was asked for. */
class FakeIntegrityTokenProvider(
    @Volatile var result: ApiResult<String> = ApiResult.Success("integrity-token"),
) : IntegrityTokenProvider {
    val nonces = CopyOnWriteArrayList<String>()

    override suspend fun requestToken(nonce: String): ApiResult<String> {
        nonces += nonce
        return result
    }
}

fun testCredentials(
    accessToken: String = "access-1",
    refreshToken: String = "refresh-1",
): DeviceCredentials =
    DeviceCredentials(
        deviceId = "6f1f7a52-1e0e-4f0d-9d3c-1b2f6d9b8a10",
        accessToken = accessToken,
        accessExpiresAtMs = 1_000L,
        refreshToken = refreshToken,
        refreshExpiresAtMs = 2_000L,
    )
