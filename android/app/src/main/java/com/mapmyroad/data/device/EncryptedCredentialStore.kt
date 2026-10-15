package com.mapmyroad.data.device

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.mapmyroad.di.CredentialsDataStore
import com.mapmyroad.di.IoDispatcher
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.IOException
import java.security.GeneralSecurityException
import java.security.ProviderException
import java.util.Base64
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [CredentialStore] that keeps one encrypted blob in its own Preferences DataStore file (`credentials`, separate
 * from settings; app backups are disabled, SECURITY.md section 8). The blob is the JSON form of the credentials,
 * encrypted by [CredentialCipher] and base64-encoded. A blob that cannot be decrypted (Keystore reset, tampering)
 * is discarded, which makes the install register again.
 *
 * Threading: all access is serialized by [mutex]; DataStore and cipher work runs on the IO dispatcher.
 */
@Singleton
class EncryptedCredentialStore
    @Inject
    constructor(
        @CredentialsDataStore private val dataStore: DataStore<Preferences>,
        private val cipher: CredentialCipher,
        @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    ) : CredentialStore {
        private val mutex = Mutex()

        // Guarded by mutex. isLoaded distinguishes "not read yet" from "read, nothing stored".
        private var cached: DeviceCredentials? = null
        private var isLoaded = false

        override suspend fun get(): DeviceCredentials? = mutex.withLock { loadLocked() }

        override suspend fun save(credentials: DeviceCredentials) {
            mutex.withLock { writeLocked(credentials) }
        }

        override suspend fun update(transform: (DeviceCredentials?) -> DeviceCredentials?) {
            mutex.withLock {
                val current = loadLocked()
                val next = transform(current)
                if (next != current) writeLocked(next)
            }
        }

        override suspend fun clear() {
            mutex.withLock { writeLocked(null) }
        }

        private suspend fun loadLocked(): DeviceCredentials? {
            if (isLoaded) return cached
            val credentials =
                withContext(ioDispatcher) {
                    val blob = dataStore.data.first()[BLOB_KEY]
                    blob?.let { decodeOrNull(it) }
                }
            if (credentials == null) {
                // Nothing stored, or an unreadable blob: make sure no unreadable leftovers stay on disk.
                withContext(ioDispatcher) { dataStore.edit { it.remove(BLOB_KEY) } }
            }
            cached = credentials
            isLoaded = true
            return credentials
        }

        private suspend fun writeLocked(credentials: DeviceCredentials?) {
            withContext(ioDispatcher) {
                if (credentials == null) {
                    dataStore.edit { it.remove(BLOB_KEY) }
                    deleteKeyQuietly()
                } else {
                    val blob = encode(credentials)
                    dataStore.edit { it[BLOB_KEY] = blob }
                }
            }
            cached = credentials
            isLoaded = true
        }

        private fun encode(credentials: DeviceCredentials): String {
            val stored =
                StoredCredentials(
                    deviceId = credentials.deviceId,
                    accessToken = credentials.accessToken,
                    accessExpiresAtMs = credentials.accessExpiresAtMs,
                    refreshToken = credentials.refreshToken,
                    refreshExpiresAtMs = credentials.refreshExpiresAtMs,
                )
            val plaintext = storageJson.encodeToString(StoredCredentials.serializer(), stored).encodeToByteArray()
            val sealed =
                try {
                    cipher.encrypt(plaintext)
                } catch (e: ProviderException) {
                    // The Keystore reports some failures as unchecked ProviderException; callers handle the checked type.
                    throw GeneralSecurityException("Credential encryption failed")
                }
            return Base64.getEncoder().encodeToString(sealed)
        }

        private fun decodeOrNull(blob: String): DeviceCredentials? =
            try {
                val plaintext = cipher.decrypt(Base64.getDecoder().decode(blob)).decodeToString()
                val stored = storageJson.decodeFromString(StoredCredentials.serializer(), plaintext)
                DeviceCredentials(
                    deviceId = stored.deviceId,
                    accessToken = stored.accessToken,
                    accessExpiresAtMs = stored.accessExpiresAtMs,
                    refreshToken = stored.refreshToken,
                    refreshExpiresAtMs = stored.refreshExpiresAtMs,
                )
            } catch (e: GeneralSecurityException) {
                // No exception details: messages from crypto providers are not guaranteed to be free of data.
                Timber.w("Stored credentials could not be decrypted, discarding them")
                null
            } catch (e: ProviderException) {
                Timber.w("Stored credentials could not be decrypted, discarding them")
                null
            } catch (e: IllegalArgumentException) {
                // Bad base64 or JSON (SerializationException is an IllegalArgumentException).
                Timber.w("Stored credentials are malformed, discarding them")
                null
            }

        private fun deleteKeyQuietly() {
            try {
                cipher.deleteKey()
            } catch (e: GeneralSecurityException) {
                Timber.w("Deleting the credential key failed")
            } catch (e: IOException) {
                Timber.w("Deleting the credential key failed")
            }
        }

        private companion object {
            val BLOB_KEY = stringPreferencesKey("device_credentials_v1")
            val storageJson = Json { ignoreUnknownKeys = true }
        }
    }

/** On-disk form, versioned by the preference key. Kept apart from [DeviceCredentials] so storage can evolve. */
@Serializable
private data class StoredCredentials(
    val deviceId: String,
    val accessToken: String,
    val accessExpiresAtMs: Long,
    val refreshToken: String,
    val refreshExpiresAtMs: Long,
) {
    override fun toString(): String = "StoredCredentials(***)"
}
