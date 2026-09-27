package com.krypt.app.e2e

import com.krypt.app.common.AutoTimeSetting
import com.krypt.app.di.TimeModule
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * "Set time automatically" for instrumented tests: on unless a test turns it
 * off, whatever the device says (CI emulators often have it off).
 */
@Singleton
class FakeAutoTimeSetting @Inject constructor() : AutoTimeSetting {
    @Volatile var on: Boolean = true

    override fun isOn(): Boolean = on
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [TimeModule::class])
abstract class TimeTestModule {
    @Binds
    abstract fun bindAutoTime(fake: FakeAutoTimeSetting): AutoTimeSetting
}
