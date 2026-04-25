package com.krypt.app.di

import android.content.ComponentName
import android.content.Context
import com.krypt.app.permission.AndroidPermissionStatusProbe
import com.krypt.app.permission.PermissionStatusProbe
import com.krypt.app.service.KryptDeviceAdminReceiver
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Provides permission-probing infrastructure into the Hilt graph (feature 002).
 *
 * [PermissionStatusProbe] is singleton so the in-memory cache of lazy system
 * service references is initialised once per process. [PermissionStateObserver]
 * is also singleton; the [StateFlow] is shared across all observers (onboarding
 * steps, ViewModel, etc.) without redundant OS queries.
 */
@Module
@InstallIn(SingletonComponent::class)
object PermissionModule {

    @Provides
    @Singleton
    fun provideDeviceAdminComponent(
        @ApplicationContext ctx: Context,
    ): ComponentName = ComponentName(ctx, KryptDeviceAdminReceiver::class.java)

    @Provides
    @Singleton
    fun providePermissionStatusProbe(
        @ApplicationContext ctx: Context,
        adminComponent: ComponentName,
    ): PermissionStatusProbe = AndroidPermissionStatusProbe(ctx, adminComponent)
}
