package com.krypt.app.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.data.LockSource
import com.krypt.app.data.LockedAppsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the Home Screen (feature 002).
 *
 * Exposes a reactive [StateFlow<HomeUiState>] that combines the live installed-apps
 * list, the locked-app set, and the current search query. Toggle taps are
 * one-way (FR-032 / FR-033):
 *
 *  - Unlocked -> Locked: instant write to [LockedAppsRepository] (atomic).
 *  - Locked -> (attempted unlock): does NOT write; emits a `krypt://request?...`
 *    share [Intent] via [shareIntents] so the screen can dispatch it via the
 *    system chooser.
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    private val installedRepo: InstalledAppsRepository,
    private val lockedRepo: LockedAppsRepository,
    val iconCache: AppIconCache,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    private val _allInstalled = MutableStateFlow<List<InstalledAppMeta>>(emptyList())

    val state: StateFlow<HomeUiState> = combine(
        _allInstalled,
        lockedRepo.allLockedFlow(),
        _query,
    ) { all, lockedSet, query ->
        val rows = all
            .filter { query.isBlank() || it.displayName.contains(query, ignoreCase = true) }
            .map { meta ->
                InstalledAppRowState(
                    packageName = meta.packageName,
                    displayName = meta.displayName,
                    isLocked = meta.packageName in lockedSet,
                )
            }
        HomeUiState(rows = rows, query = query)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    init { refresh() }

    /** Update the search query filter. */
    fun setQuery(q: String) { _query.value = q }

    /** Reload the installed-apps list from PackageManager (call on onResume). */
    fun refresh() {
        viewModelScope.launch {
            _allInstalled.value = installedRepo.allInstalled()
        }
    }

    /**
     * Handle a toggle tap on [row].
     *
     * - If [row] is unlocked: lock it immediately (FR-032).
     * - If [row] is locked: do NOT unlock; emit a share intent with the Guardian
     *   request URL instead (FR-033). The toggle UI must NOT change state.
     */
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
}

data class HomeUiState(
    val rows: List<InstalledAppRowState> = emptyList(),
    val query: String = "",
)

data class InstalledAppRowState(
    val packageName: String,
    val displayName: String,
    val isLocked: Boolean,
)
