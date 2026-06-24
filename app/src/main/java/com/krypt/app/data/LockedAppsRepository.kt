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
    /** Manually lock an already-installed app (feature 002 Home Screen). */
    suspend fun lock(packageName: String, displayName: String, source: LockSource = LockSource.MANUAL)
    fun observeLockedApps(): Flow<List<LockedApp>>
    /** Emits the live set of locked package names for the Home Screen list (feature 002). */
    fun allLockedFlow(): Flow<Set<String>>
    suspend fun unlockAppUntil(pkg: String, expiresAtMs: Long)
    suspend fun unlock(pkg: String)
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

    override suspend fun lock(packageName: String, displayName: String, source: LockSource) {
        val now = clock.nowMs()
        val existing = dao.findByPackage(packageName)
        if (existing != null && existing.lockState == LockState.LOCKED) return
        dao.insert(
            LockedAppEntity(
                packageName = packageName,
                displayName = displayName,
                lockState = LockState.LOCKED,
                lockSource = source,
                createdAt = existing?.createdAt ?: now,
                updatedAt = now,
            )
        )
    }

    override fun observeLockedApps(): Flow<List<LockedApp>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    override fun allLockedFlow(): Flow<Set<String>> =
        dao.observeAll().map { list ->
            list.filter { it.lockState == LockState.LOCKED }
                .map { it.packageName }
                .toSet()
        }

    override suspend fun unlockAppUntil(pkg: String, expiresAtMs: Long) {
        dao.updateLockState(pkg, LockState.UNLOCKED, clock.nowMs())
    }

    override suspend fun unlock(pkg: String) {
        dao.updateLockState(pkg, LockState.UNLOCKED, clock.nowMs())
    }

    override suspend fun findByPackage(pkg: String): LockedApp? =
        dao.findByPackage(pkg)?.toDomain()
}
