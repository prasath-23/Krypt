package com.krypt.app.di

import com.krypt.app.common.Clock
import com.krypt.app.common.SystemClock
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds common utilities (currently just [Clock]) into the Hilt graph.
 * Split out from [CryptoModule] so non-crypto consumers of [Clock] don't
 * drag a crypto-labelled module into their tests.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CoreModule {

    @Binds
    @Singleton
    abstract fun bindClock(impl: SystemClock): Clock
}
