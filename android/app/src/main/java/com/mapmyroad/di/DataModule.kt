package com.mapmyroad.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.mapmyroad.data.local.MapMyRoadDatabase
import com.mapmyroad.data.local.TripDao
import com.mapmyroad.data.repository.RoomTripRepository
import com.mapmyroad.data.settings.DataStoreSettingsRepository
import com.mapmyroad.domain.repository.SettingsRepository
import com.mapmyroad.domain.repository.TripRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton

/** The DataStore holding [com.mapmyroad.domain.model.UserSettings] (file `settings.preferences_pb`). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class SettingsDataStore

/** Local data layer: Room database, DAOs, settings DataStore and the repository bindings. */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {
    @Binds
    abstract fun bindTripRepository(impl: RoomTripRepository): TripRepository

    @Binds
    abstract fun bindSettingsRepository(impl: DataStoreSettingsRepository): SettingsRepository

    companion object {
        // No fallbackToDestructiveMigration: every schema change ships a Migration (CODING_STANDARDS.md 3.10).
        @Provides
        @Singleton
        fun provideDatabase(
            @ApplicationContext context: Context,
        ): MapMyRoadDatabase =
            Room
                .databaseBuilder(context, MapMyRoadDatabase::class.java, MapMyRoadDatabase.NAME)
                .build()

        @Provides
        fun provideTripDao(database: MapMyRoadDatabase): TripDao = database.tripDao()

        // One DataStore per file per process, otherwise DataStore throws: hence @Singleton.
        @Provides
        @Singleton
        @SettingsDataStore
        fun provideSettingsDataStore(
            @ApplicationContext context: Context,
        ): DataStore<Preferences> =
            PreferenceDataStoreFactory.create(
                // A corrupt file resets settings to defaults instead of crashing on every read.
                corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
                produceFile = { context.preferencesDataStoreFile(SETTINGS_DATASTORE_NAME) },
            )

        private const val SETTINGS_DATASTORE_NAME = "settings"
    }
}
