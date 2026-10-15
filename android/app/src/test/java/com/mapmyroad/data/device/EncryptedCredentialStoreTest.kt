package com.mapmyroad.data.device

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

class EncryptedCredentialStoreTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    // DataStore does real file I/O, so it gets a real dispatcher; the scope is cancelled after each test.
    private val dataStoreScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val cipher = FakeCredentialCipher()

    private val dataStore: DataStore<Preferences> by lazy {
        PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { File(temporaryFolder.root, "credentials.preferences_pb") },
        )
    }

    // A new instance has an empty memory cache, so it proves what was persisted.
    private fun newStore() = EncryptedCredentialStore(dataStore, cipher, Dispatchers.IO)

    private suspend fun storedPreferences() = dataStore.data.first().asMap()

    @After
    fun tearDown() {
        dataStoreScope.cancel()
    }

    @Test
    fun freshStore_isEmpty() =
        runTest {
            assertNull(newStore().get())
        }

    @Test
    fun saved_credentials_arePersisted_andReadBack() =
        runTest {
            newStore().save(testCredentials())

            assertEquals(testCredentials(), newStore().get())
        }

    @Test
    fun storedBlob_doesNotContainTheTokensInPlaintext() =
        runTest {
            newStore().save(testCredentials(accessToken = "access-secret", refreshToken = "refresh-secret"))

            val values = storedPreferences().values
            assertEquals(1, values.size)
            val decoded = String(Base64.getDecoder().decode(values.single() as String), Charsets.ISO_8859_1)
            assertFalse(decoded.contains("secret"))
            assertFalse(decoded.contains(testCredentials().deviceId))
        }

    @Test
    fun clear_removesCredentials_andDeletesTheKey() =
        runTest {
            val store = newStore()
            store.save(testCredentials())

            store.clear()

            assertNull(store.get())
            assertNull(newStore().get())
            assertTrue(storedPreferences().isEmpty())
            assertEquals(1, cipher.deleteKeyCalls)
        }

    @Test
    fun undecryptableBlob_isDiscarded() =
        runTest {
            newStore().save(testCredentials())
            cipher.failDecrypt = true

            assertNull(newStore().get())
            assertTrue(storedPreferences().isEmpty())
        }

    @Test
    fun update_appliesTheTransformToTheCurrentValue() =
        runTest {
            val store = newStore()
            store.save(testCredentials())

            store.update { current -> current?.copy(accessToken = "access-2") }

            assertEquals("access-2", newStore().get()?.accessToken)
        }
}
