package com.krypt.app.ui.home

import androidx.lifecycle.ViewModel
import com.krypt.app.data.settings.Appearance
import com.krypt.app.data.settings.AppearanceStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

/**
 * Drives the Appearance sheet opened from Home's header: System default,
 * Light or Dark. It sits behind the Guardian PIN, as Home does.
 */
@HiltViewModel
class AppearanceViewModel @Inject constructor(
    private val store: AppearanceStore,
) : ViewModel() {

    val appearance: StateFlow<Appearance> = store.appearance

    /** Applies [value] at once to every Krypt screen, the lock screen included. */
    fun choose(value: Appearance) = store.set(value)
}
