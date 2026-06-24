package com.krypt.app.di

import android.content.Context
import com.krypt.app.crypto.EncryptedKPairStore
import com.krypt.app.crypto.KPairStore
import com.krypt.app.data.GuardianPairingDao
import com.krypt.app.data.GuardianRepository
import com.krypt.app.data.KryptDatabase
import com.krypt.app.data.LockedAppDao
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.data.OutstandingRequestDao
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.data.RoomGuardianRepository
import com.krypt.app.data.RoomLockedAppsRepository
import com.krypt.app.data.RoomOutstandingRequestRepository
import com.krypt.app.data.RoomUnlockGrantRepository
import com.krypt.app.data.UnlockGrantDao
import com.krypt.app.data.UnlockGrantRepository
import androidx.room.Room
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Binds data-layer abstractions to their production (Room + Keystore) impls.
 * Tests replace specific bindings via `@TestInstallIn(replaces = [DataModule::class])`.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DataModule {

    @Binds @Singleton
    abstract fun bindLockedApps(impl: RoomLockedAppsRepository): LockedAppsRepository

    @Binds @Singleton
    abstract fun bindGuardian(impl: RoomGuardianRepository): GuardianRepository

    @Binds @Singleton
    abstract fun bindOutstanding(impl: RoomOutstandingRequestRepository): OutstandingRequestRepository

    @Binds @Singleton
    abstract fun bindGrants(impl: RoomUnlockGrantRepository): UnlockGrantRepository

    @Binds @Singleton
    abstract fun bindKPairStore(impl: EncryptedKPairStore): KPairStore
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvidersModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KryptDatabase =
        Room.databaseBuilder(context, KryptDatabase::class.java, KryptDatabase.DATABASE_NAME)
            .build()

    @Provides fun provideLockedAppDao(db: KryptDatabase): LockedAppDao = db.lockedAppDao()
    @Provides fun provideGuardianDao(db: KryptDatabase): GuardianPairingDao = db.guardianPairingDao()
    @Provides fun provideOutstandingDao(db: KryptDatabase): OutstandingRequestDao = db.outstandingRequestDao()
    @Provides fun provideGrantDao(db: KryptDatabase): UnlockGrantDao = db.unlockGrantDao()
}
