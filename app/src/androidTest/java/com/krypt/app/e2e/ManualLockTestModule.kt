package com.krypt.app.e2e

import com.krypt.app.di.HomeModule
import com.krypt.app.permission.PermissionKey
import com.krypt.app.permission.PermissionStatusProbe
import com.krypt.app.ui.home.AndroidInstalledAppsRepository
import com.krypt.app.ui.home.InstalledAppMeta
import com.krypt.app.ui.home.InstalledAppsRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Hilt test module for feature 002 e2e tests.
 *
 * Replaces [HomeModule] with a [FakeInstalledAppsRepository] whose contents
 * are fixed so tests are deterministic regardless of what apps are installed
 * on the test emulator. Also provides a controllable [FakePermissionStatusProbe]
 * so permission state can be driven programmatically.
 */
@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [HomeModule::class],
)
abstract class ManualLockInstalledAppsTestModule {

    @Binds
    @Singleton
    abstract fun bindInstalledAppsRepository(
        impl: FakeInstalledAppsRepository,
    ): InstalledAppsRepository
}

/**
 * Fixed 3-element installed-apps list for e2e tests.
 * The list is stable across emulator configurations.
 */
@Singleton
class FakeInstalledAppsRepository @Inject constructor() : InstalledAppsRepository {
    override suspend fun allInstalled(): List<InstalledAppMeta> = listOf(
        InstalledAppMeta("com.example.alpha", "Alpha"),
        InstalledAppMeta("com.example.beta", "Beta"),
        InstalledAppMeta("com.example.gamma", "Gamma"),
    )
}

/**
 * Controllable [PermissionStatusProbe] for e2e tests.
 * Tests set [permissions] before the screen renders.
 */
@Singleton
class FakeE2EPermissionStatusProbe @Inject constructor() : PermissionStatusProbe {
    var permissions: Map<PermissionKey, Boolean> = PermissionKey.values().associateWith { false }

    override fun statusOf(key: PermissionKey): Boolean = permissions[key] ?: false
}
