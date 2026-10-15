package com.mapmyroad.di

import com.mapmyroad.data.routing.CurrentLocationSource
import com.mapmyroad.data.routing.FusedCurrentLocationSource
import com.mapmyroad.data.routing.NetworkRouteRepository
import com.mapmyroad.data.routing.RoutingApi
import com.mapmyroad.domain.repository.RouteRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Destination search and route candidates (M5-7, M5-8). Retrofit comes from the network module. */
@Module
@InstallIn(SingletonComponent::class)
abstract class RoutingModule {
    @Binds
    abstract fun bindRouteRepository(impl: NetworkRouteRepository): RouteRepository

    @Binds
    abstract fun bindCurrentLocationSource(impl: FusedCurrentLocationSource): CurrentLocationSource

    companion object {
        @Provides
        @Singleton
        fun provideRoutingApi(retrofit: Retrofit): RoutingApi = retrofit.create(RoutingApi::class.java)
    }
}
