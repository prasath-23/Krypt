package com.krypt.app.e2e

import com.krypt.app.di.SecurityModule
import com.krypt.app.security.MasterKeyStore
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt test module that swaps the production
 * [com.krypt.app.security.EncryptedPrefsMasterKeyStore] for an in-memory
 * [InMemoryMasterKeyStore] in Amendment 1 androidTest suites.
 *
 * Keeping this override narrow (only [SecurityModule]) - the real Room
 * database / LockedAppsRepository / SettingsRepository from [DataModule]
 * are used as-is so the tests exercise the real persistence paths.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [SecurityModule::class],
)
abstract class Amendment1SecurityTestModule {

    @Binds
    @Singleton
    abstract fun bindMasterKeyStore(impl: InMemoryMasterKeyStore): MasterKeyStore
}

/**
 * Same contract as production [com.krypt.app.security.EncryptedPrefsMasterKeyStore]
 * but purely RAM-resident. Safe across a single instrumented test class
 * because Hilt spins a fresh component per `@HiltAndroidRule`.
 */
@Singleton
class InMemoryMasterKeyStore @Inject constructor() : MasterKeyStore {
    @Volatile private var salt: ByteArray? = null
    @Volatile private var masterKey: ByteArray? = null
    @Volatile private var pinProof: ByteArray? = null

    override suspend fun isConfigured(): Boolean = masterKey != null

    override suspend fun save(salt: ByteArray, masterKey: ByteArray, pinProof: ByteArray) {
        this.salt = salt.copyOf()
        this.masterKey = masterKey.copyOf()
        this.pinProof = pinProof.copyOf()
    }

    override suspend fun loadMasterKey(): ByteArray? = masterKey?.copyOf()
    override suspend fun loadSalt(): ByteArray? = salt?.copyOf()
    override suspend fun loadPinProof(): ByteArray? = pinProof?.copyOf()

    override suspend fun clear() {
        salt = null
        masterKey = null
        pinProof = null
    }
}
