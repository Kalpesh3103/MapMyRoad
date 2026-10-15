package com.mapmyroad.di

import com.mapmyroad.data.community.ApiCommunityRepository
import com.mapmyroad.data.community.CommunityApi
import com.mapmyroad.domain.repository.CommunityRepository
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import retrofit2.Retrofit
import javax.inject.Singleton

/** Community map and hazard feedback (M5-5, M5-6). The shared [Retrofit] client comes from the network module. */
@Module
@InstallIn(SingletonComponent::class)
abstract class CommunityModule {
    @Binds
    abstract fun bindCommunityRepository(impl: ApiCommunityRepository): CommunityRepository

    companion object {
        @Provides
        @Singleton
        fun provideCommunityApi(retrofit: Retrofit): CommunityApi = retrofit.create(CommunityApi::class.java)
    }
}
