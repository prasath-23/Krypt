package com.krypt.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.data.LockSource
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.data.LockerSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Home Screen (feature 002).
 *
 * Exposes a reactive [StateFlow<HomeUiState>] that combines the live installed-apps
 * list, the locked-app set, the Guardian unlocks in [LockerSessionStore], and
 * the current search query. The Home Screen is only reachable after the
 * Guardian PIN, so a toggle tap writes straight to [LockedAppsRepository] in
 * either direction.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val installedRepo: InstalledAppsRepository,
    private val lockedRepo: LockedAppsRepository,
    private val sessionStore: LockerSessionStore,
    val iconCache: AppIconCache,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _allInstalled = MutableStateFlow<List<InstalledAppMeta>>(emptyList())

    /** Ticks once a second while any app is unlocked, so its time left counts down. */
    private val countdown: Flow<Unit> = sessionStore.grants
        .map { it.isNotEmpty() }
        .distinctUntilChanged()
        .flatMapLatest { anyUnlocked -> if (anyUnlocked) everySecond() else flowOf(Unit) }

    val state: StateFlow<HomeUiState> = combine(
        _allInstalled,
        lockedRepo.allLockedFlow(),
        sessionStore.grants,
        _query,
        countdown,
    ) { all, lockedSet, grants, query, _ ->
        // Asking the store also clears unlocks that have run out, which stops the countdown.
        val timeLeft = grants.keys.associateWith(sessionStore::remainingMs)
        val rows = all
            .filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
            .map { meta ->
                val isLocked = meta.packageName in lockedSet
                InstalledAppRowState(
                    packageName = meta.packageName,
                    displayName = meta.displayName,
                    isLocked = isLocked,
                    status = appRowStatus(isLocked, timeLeft[meta.packageName] ?: 0L),
                )
            }
        HomeUiState(rows = rows, query = query)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    init { refresh() }

    /** Update the search query filter. */
    fun setQuery(q: String) { _query.value = q }

    /** Reload the installed-apps list from PackageManager (call on onResume). */
    fun refresh() {
        viewModelScope.launch {
            _allInstalled.value = installedRepo.allInstalled()
        }
    }

    /** Handle a toggle tap on [row]: lock an unlocked app, unlock a locked one. */
    fun onToggle(row: InstalledAppRowState) {
        if (!row.isLocked) {
            viewModelScope.launch {
                lockedRepo.lock(row.packageName, row.displayName, LockSource.MANUAL)
            }
        } else {
            viewModelScope.launch {
                lockedRepo.unlock(row.packageName)
            }
        }
    }

    /** End [row]'s Guardian unlock early: the app is locked again at once. */
    fun onLockNow(row: InstalledAppRowState) {
        sessionStore.revoke(row.packageName)
    }

    private fun everySecond(): Flow<Unit> = flow {
        while (true) {
            emit(Unit)
            delay(1_000)
        }
    }
}

data class HomeUiState(
    val rows: List<InstalledAppRowState> = emptyList(),
    val query: String = "",
)

data class InstalledAppRowState(
    val packageName: String,
    val displayName: String,
    val isLocked: Boolean,
    val status: AppRowStatus = if (isLocked) AppRowStatus.Locked else AppRowStatus.NotLocked,
)
