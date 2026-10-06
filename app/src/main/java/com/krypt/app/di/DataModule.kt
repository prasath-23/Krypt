package com.krypt.app.di

import android.content.Context
import androidx.room.Room
import com.krypt.app.crypto.EncryptedKPairStore
import com.krypt.app.crypto.KPairStore
import com.krypt.app.data.GuardianPairingDao
import com.krypt.app.data.GuardianRepository
import com.krypt.app.data.KryptDatabase
import com.krypt.app.data.LockedAppDao
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.data.MIGRATION_1_2
import com.krypt.app.data.OutstandingRequestDao
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.data.RoomGuardianRepository
import com.krypt.app.data.RoomLockedAppsRepository
import com.krypt.app.data.RoomOutstandingRequestRepository
import com.krypt.app.data.RoomUnlockGrantRepository
import com.krypt.app.data.UnlockGrantDao
import com.krypt.app.data.UnlockGrantRepository
import com.krypt.app.data.daily.DailyAllowanceDao
import com.krypt.app.data.daily.DailyAllowanceMeter
import com.krypt.app.data.daily.DailyAllowanceRepository
import com.krypt.app.data.daily.DailyAllowances
import com.krypt.app.data.daily.DailyUsageDao
import com.krypt.app.data.daily.RoomDailyAllowanceRepository
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

    @Binds @Singleton
    abstract fun bindDailyAllowanceRepository(impl: RoomDailyAllowanceRepository): DailyAllowanceRepository

    @Binds @Singleton
    abstract fun bindDailyAllowances(impl: DailyAllowanceMeter): DailyAllowances
}

@Module
@InstallIn(SingletonComponent::class)
object DataProvidersModule {

    @Provides @Singleton
    fun provideDatabase(@ApplicationContext context: Context): KryptDatabase =
        Room.databaseBuilder(context, KryptDatabase::class.java, KryptDatabase.DATABASE_NAME)
            // Never a destructive fallback: wiping locked_apps would unlock every app.
            .addMigrations(MIGRATION_1_2)
            .build()

    @Provides fun provideLockedAppDao(db: KryptDatabase): LockedAppDao = db.lockedAppDao()
    @Provides fun provideGuardianDao(db: KryptDatabase): GuardianPairingDao = db.guardianPairingDao()
    @Provides fun provideOutstandingDao(db: KryptDatabase): OutstandingRequestDao = db.outstandingRequestDao()
    @Provides fun provideGrantDao(db: KryptDatabase): UnlockGrantDao = db.unlockGrantDao()
    @Provides fun provideDailyAllowanceDao(db: KryptDatabase): DailyAllowanceDao = db.dailyAllowanceDao()
    @Provides fun provideDailyUsageDao(db: KryptDatabase): DailyUsageDao = db.dailyUsageDao()
}
