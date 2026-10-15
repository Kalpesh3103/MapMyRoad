package com.mapmyroad.data.device

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * AES-256-GCM with a key generated inside the Android Keystore under [KEY_ALIAS] (SECURITY.md section 6.2). The
 * key material never enters app memory. The Keystore picks a random 12-byte IV per encryption (randomized
 * encryption is required for these keys); the output is `[ivLength: 1 byte][iv][ciphertext + 16-byte tag]`.
 * Android-only: not usable in JVM unit tests.
 */
@Singleton
class KeystoreCredentialCipher
    @Inject
    constructor() : CredentialCipher {
        private val keyLock = Any()

        override fun encrypt(plaintext: ByteArray): ByteArray {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val sealed = cipher.doFinal(plaintext)
            return ByteBuffer
                .allocate(1 + iv.size + sealed.size)
                .put(iv.size.toByte())
                .put(iv)
                .put(sealed)
                .array()
        }

        override fun decrypt(ciphertext: ByteArray): ByteArray {
            if (ciphertext.isEmpty()) throw GeneralSecurityException("Empty credential blob")
            val ivLength = ciphertext[0].toInt()
            if (ivLength !in MIN_IV_BYTES..MAX_IV_BYTES || ciphertext.size <= 1 + ivLength) {
                throw GeneralSecurityException("Malformed credential blob")
            }
            val key = existingKey() ?: throw GeneralSecurityException("Credential key missing")
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, ciphertext, 1, ivLength))
            return cipher.doFinal(ciphertext, 1 + ivLength, ciphertext.size - 1 - ivLength)
        }

        override fun deleteKey() {
            synchronized(keyLock) {
                val keyStore = loadKeyStore()
                if (keyStore.containsAlias(KEY_ALIAS)) keyStore.deleteEntry(KEY_ALIAS)
            }
        }

        private fun loadKeyStore(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

        private fun existingKey(): SecretKey? = loadKeyStore().getKey(KEY_ALIAS, null) as? SecretKey

        private fun getOrCreateKey(): SecretKey =
            synchronized(keyLock) {
                existingKey() ?: generateKey()
            }

        private fun generateKey(): SecretKey {
            val spec =
                KeyGenParameterSpec
                    .Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(KEY_SIZE_BITS)
                    .build()
            val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
            generator.init(spec)
            return generator.generateKey()
        }

        companion object {
            const val KEY_ALIAS = "mmr_credentials"
            private const val ANDROID_KEYSTORE = "AndroidKeyStore"
            private const val TRANSFORMATION = "AES/GCM/NoPadding"
            private const val KEY_SIZE_BITS = 256
            private const val TAG_BITS = 128
            private const val MIN_IV_BYTES = 12
            private const val MAX_IV_BYTES = 16
        }
    }
