package com.krypt.app.data

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * In-memory [LockedAppsRepository] for tests that don't need Room.
 * Thread-safe via a `MutableStateFlow`.
 */
class FakeLockedAppsRepository : LockedAppsRepository {

    private val state = MutableStateFlow<List<LockedApp>>(emptyList())
    val observed: StateFlow<List<LockedApp>> get() = state.asStateFlow()

    override suspend fun checkIfAppIsLocked(pkg: String): Boolean {
        val app = state.value.firstOrNull { it.packageName == pkg } ?: return false
        return app.lockState == LockState.LOCKED
    }

    override suspend fun lockNewlyInstalledApp(pkg: String, displayName: String) {
        val now = System.currentTimeMillis()
        val app = LockedApp(
            packageName = pkg,
            displayName = displayName,
            lockState = LockState.LOCKED,
            lockSource = LockSource.DEFAULT_DENY,
            createdAt = now, updatedAt = now,
        )
        state.value = state.value.filterNot { it.packageName == pkg } + app
    }

    override suspend fun lock(packageName: String, displayName: String, source: LockSource) {
        val now = System.currentTimeMillis()
        val app = LockedApp(
            packageName = packageName,
            displayName = displayName,
            lockState = LockState.LOCKED,
            lockSource = source,
            createdAt = now, updatedAt = now,
        )
        state.value = state.value.filterNot { it.packageName == packageName } + app
    }

    override fun observeLockedApps() = observed

    override fun allLockedFlow() = state.map { list ->
        list.filter { it.lockState == LockState.LOCKED }.map { it.packageName }.toSet()
    }

    override suspend fun unlockAppUntil(pkg: String, expiresAtMs: Long) {
        state.value = state.value.map {
            if (it.packageName == pkg) it.copy(lockState = LockState.UNLOCKED) else it
        }
    }

    override suspend fun unlock(pkg: String) {
        state.value = state.value.map {
            if (it.packageName == pkg) it.copy(lockState = LockState.UNLOCKED) else it
        }
    }

    override suspend fun findByPackage(pkg: String): LockedApp? =
        state.value.firstOrNull { it.packageName == pkg }
}
