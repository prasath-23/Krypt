package com.krypt.app.data

import com.krypt.app.common.Clock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * FR-016 seam for the locked-apps list. Interface lives in `main`; two impls:
 *  - [RoomLockedAppsRepository] (production, Room-backed)
 *  - `FakeLockedAppsRepository` (tests; `src/test/`)
 */
interface LockedAppsRepository {
    suspend fun checkIfAppIsLocked(pkg: String): Boolean
    suspend fun lockNewlyInstalledApp(pkg: String, displayName: String)
    fun observeLockedApps(): Flow<List<LockedApp>>
    suspend fun unlockAppUntil(pkg: String, expiresAtMs: Long)
    suspend fun findByPackage(pkg: String): LockedApp?
}

@Singleton
class RoomLockedAppsRepository @Inject constructor(
    private val dao: LockedAppDao,
    private val clock: Clock,
) : LockedAppsRepository {

    override suspend fun checkIfAppIsLocked(pkg: String): Boolean {
        val app = dao.findByPackage(pkg) ?: return false
        return app.lockState == LockState.LOCKED
    }

    override suspend fun lockNewlyInstalledApp(pkg: String, displayName: String) {
        val now = clock.nowMs()
        dao.insert(
            LockedAppEntity(
                packageName = pkg,
                displayName = displayName,
                lockState = LockState.LOCKED,
                lockSource = LockSource.DEFAULT_DENY,
                createdAt = now,
                updatedAt = now,
            )
        )
    }

    override fun observeLockedApps(): Flow<List<LockedApp>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override suspend fun unlockAppUntil(pkg: String, expiresAtMs: Long) {
        dao.updateLockState(pkg, LockState.UNLOCKED, clock.nowMs())
    }

    override suspend fun findByPackage(pkg: String): LockedApp? =
        dao.findByPackage(pkg)?.toDomain()
}
