package com.krypt.app.di

import com.krypt.app.crypto.RealSecureRandomSource
import com.krypt.app.crypto.SecureRandomSource
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the [com.krypt.app.crypto] layer.
 *
 * Concrete types ([com.krypt.app.crypto.KdfProvider] and
 * [com.krypt.app.crypto.RealSecureRandomSource]) have `@Inject constructor`
 * annotations so Hilt can instantiate them without explicit `@Provides`.
 * This module exists only to bind the interface <-> implementation pair for
 * [SecureRandomSource] so that call sites depend on the interface.
 *
 * Test replacement: use
 *   `@TestInstallIn(components = [SingletonComponent::class], replaces = [CryptoModule::class])`
 * in androidTest / test source sets to substitute a deterministic
 * `FakeSecureRandomSource`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class CryptoModule {

    @Binds
    @Singleton
    abstract fun bindSecureRandom(impl: RealSecureRandomSource): SecureRandomSource
}
