package com.mapmyroad.data.device

import java.security.GeneralSecurityException

/**
 * Encrypts the credential blob at rest. The production implementation is [KeystoreCredentialCipher] (AES-256-GCM
 * with a key that never leaves the Android Keystore); JVM tests use a fake. Calls block, use an IO dispatcher.
 */
interface CredentialCipher {
    @Throws(GeneralSecurityException::class)
    fun encrypt(plaintext: ByteArray): ByteArray

    /** Throws when the blob was tampered with, is malformed, or the key is gone (for example after a Keystore reset). */
    @Throws(GeneralSecurityException::class)
    fun decrypt(ciphertext: ByteArray): ByteArray

    /** Deletes the key. A later [encrypt] creates a new one. */
    fun deleteKey()
}
