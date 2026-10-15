package com.mapmyroad.di

import android.content.Context
import android.os.Build
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import com.mapmyroad.BuildConfig
import com.mapmyroad.data.device.CredentialCipher
import com.mapmyroad.data.device.CredentialStore
import com.mapmyroad.data.device.DefaultDeviceSession
import com.mapmyroad.data.device.DeviceInfo
import com.mapmyroad.data.device.DeviceTokenManager
import com.mapmyroad.data.device.EncryptedCredentialStore
import com.mapmyroad.data.device.EpochClock
import com.mapmyroad.data.device.IntegrityTokenProvider
import com.mapmyroad.data.device.KeystoreCredentialCipher
import com.mapmyroad.data.device.PlayIntegrityTokenProvider
import com.mapmyroad.data.remote.AppVersionInterceptor
import com.mapmyroad.data.remote.AuthTokens
import com.mapmyroad.data.remote.DeviceApi
import com.mapmyroad.data.remote.DeviceAuthApi
import com.mapmyroad.domain.device.DeviceSession
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Qualifier
import javax.inject.Singleton

/** The DataStore holding the encrypted device credentials (file `credentials.preferences_pb`), apart from settings. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class CredentialsDataStore

/** Device identity (M5-2): session, credential storage, Play Integrity and token refresh. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DeviceModule {
    @Binds
    abstract fun bindDeviceSession(impl: DefaultDeviceSession): DeviceSession

    @Binds
    abstract fun bindCredentialStore(impl: EncryptedCredentialStore): CredentialStore

    @Binds
    abstract fun bindCredentialCipher(impl: KeystoreCredentialCipher): CredentialCipher

    @Binds
    abstract fun bindIntegrityTokenProvider(impl: PlayIntegrityTokenProvider): IntegrityTokenProvider

    @Binds
    abstract fun bindAuthTokens(impl: DeviceTokenManager): AuthTokens

    companion object {
        // One DataStore per file per process, hence @Singleton.
        @Provides
        @Singleton
        @CredentialsDataStore
        fun provideCredentialsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                // A corrupt file means "not registered": the app registers again instead of crashing.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile(CREDENTIALS_DATASTORE_NAME) },
            )

        @Provides
        fun provideDeviceInfo(): DeviceInfo =
            DeviceInfo(
                appVersion = AppVersionInterceptor.headerValue(BuildConfig.VERSION_NAME),
                osApiLevel = Build.VERSION.SDK_INT,
            )

        @Provides
        fun provideEpochClock(): EpochClock = EpochClock { System.currentTimeMillis() }

        @Provides
        @Singleton
        fun provideDeviceAuthApi(
            @UnauthenticatedHttp retrofit: Retrofit,
        ): DeviceAuthApi = retrofit.create(DeviceAuthApi::class.java)

        @Provides
        @Singleton
        fun provideDeviceApi(retrofit: Retrofit): DeviceApi = retrofit.create(DeviceApi::class.java)

        private const val CREDENTIALS_DATASTORE_NAME = "credentials"
    }
}
