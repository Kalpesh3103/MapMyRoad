package com.mapmyroad.di

import com.mapmyroad.data.local.MapMyRoadDatabase
import com.mapmyroad.data.local.UploadDao
import com.mapmyroad.data.upload.EventsApi
import com.mapmyroad.data.upload.EventsUploader
import com.mapmyroad.data.upload.RetrofitEventsUploader
import com.mapmyroad.data.upload.RoomUploadStore
import com.mapmyroad.data.upload.UploadClock
import com.mapmyroad.data.upload.UploadStore
import com.mapmyroad.data.upload.WorkManagerUploadScheduler
import com.mapmyroad.domain.upload.UploadScheduler
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Contribution uploads (M5-4): scheduler, worker dependencies and the events API. */
@Module
@InstallIn(SingletonComponent::class)
abstract class UploadModule {
    @Binds
    abstract fun bindUploadScheduler(impl: WorkManagerUploadScheduler): UploadScheduler

    @Binds
    abstract fun bindUploadStore(impl: RoomUploadStore): UploadStore

    @Binds
    abstract fun bindEventsUploader(impl: RetrofitEventsUploader): EventsUploader

    companion object {
        @Provides
        @Singleton
        fun provideEventsApi(retrofit: Retrofit): EventsApi = retrofit.create(EventsApi::class.java)

        @Provides
        fun provideUploadDao(database: MapMyRoadDatabase): UploadDao = database.uploadDao()

        @Provides
        fun provideUploadClock(): UploadClock = UploadClock { System.currentTimeMillis() }
    }
}
