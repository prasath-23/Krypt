package com.krypt.app.di

import com.krypt.app.security.EncryptedPrefsMasterKeyStore
import com.krypt.app.security.MasterKeyStore
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt bindings for the Amendment 1 security layer (on-device MasterKey
 * storage). Kept separate from [CryptoModule] so the legacy K_pair wiring
 * is not entangled with the new post-PIN-setup world.
 *
 * Test replacement: use
 *   `@TestInstallIn(components = [SingletonComponent::class], replaces = [SecurityModule::class])`
 * with a fake [MasterKeyStore] impl.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class SecurityModule {

    @Binds
    @Singleton
    abstract fun bindMasterKeyStore(
        impl: EncryptedPrefsMasterKeyStore,
    ): MasterKeyStore
}
