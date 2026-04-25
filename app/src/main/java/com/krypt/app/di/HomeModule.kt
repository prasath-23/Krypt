package com.krypt.app.di

import com.krypt.app.ui.home.AndroidInstalledAppsRepository
import com.krypt.app.ui.home.InstalledAppsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides [InstalledAppsRepository] into the Hilt graph.
 * [com.krypt.app.ui.home.AppIconCache] is [Singleton]-annotated and
 * constructor-injected so Hilt resolves it automatically without a @Provides.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class HomeModule {

    @Binds
    @Singleton
    abstract fun bindInstalledAppsRepository(
        impl: AndroidInstalledAppsRepository,
    ): InstalledAppsRepository
}
