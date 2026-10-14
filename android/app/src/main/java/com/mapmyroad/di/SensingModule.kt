package com.mapmyroad.di

import com.mapmyroad.domain.session.TripSessionController
import com.mapmyroad.sensing.AndroidLocationPermissionChecker
import com.mapmyroad.sensing.DefaultTripSessionController
import com.mapmyroad.sensing.LocationPermissionChecker
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * Scope that lives as long as the process. Used for work that must outlive a screen or the tracking service:
 * startup trip recovery and the final writes of a trip being stopped.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
abstract class SensingModule {
    @Binds
    @Singleton
    abstract fun bindTripSessionController(impl: DefaultTripSessionController): TripSessionController

    @Binds
    abstract fun bindLocationPermissionChecker(impl: AndroidLocationPermissionChecker): LocationPermissionChecker

    companion object {
        @Provides
        @Singleton
        @ApplicationScope
        fun provideApplicationScope(
            @DefaultDispatcher defaultDispatcher: CoroutineDispatcher,
        ): CoroutineScope = CoroutineScope(SupervisorJob() + defaultDispatcher)
    }
}
