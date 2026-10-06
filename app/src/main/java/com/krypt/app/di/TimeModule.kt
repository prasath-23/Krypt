package com.krypt.app.di

import com.krypt.app.common.AutoTimeSetting
import com.krypt.app.common.SystemAutoTimeSetting
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/** Binds the "Set time automatically" seam; instrumented tests replace it. */
@Module
@InstallIn(SingletonComponent::class)
abstract class TimeModule {
    @Binds
    abstract fun bindAutoTimeSetting(impl: SystemAutoTimeSetting): AutoTimeSetting
}
