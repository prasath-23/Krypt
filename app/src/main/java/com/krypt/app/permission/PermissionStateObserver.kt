package com.krypt.app.permission

import android.content.ContentResolver
import android.content.Context
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Exposes a [StateFlow] of the current OS-level grant state for every
 * [PermissionKey]. The flow refreshes:
 *
 * 1. On every [LifecycleOwner.onResume] (polled via lifecycle observer).
 * 2. On Accessibility service changes via [AccessibilityManager.AccessibilityStateChangeListener].
 * 3. As a fallback on some OEMs (Xiaomi/MIUI), via a [ContentObserver] on the
 *    ENABLED_ACCESSIBILITY_SERVICES secure setting.
 *
 * Call [bind] once when the observing Activity / Fragment starts to register
 * lifecycle hooks and begin live updates.
 */
@Singleton
class PermissionStateObserver @Inject constructor(
    private val probe: PermissionStatusProbe,
    @ApplicationContext private val context: Context,
) {
    private val _state = MutableStateFlow(probe.statusAll())

    /** Live map of every [PermissionKey] to its current grant state. */
    val state: StateFlow<Map<PermissionKey, Boolean>> = _state.asStateFlow()

    /** Re-polls all permissions and emits the updated map. */
    fun refresh() {
        _state.value = probe.statusAll()
    }

    private val a11yManager by lazy {
        context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
    }

    private val a11yListener = AccessibilityManager.AccessibilityStateChangeListener { _ ->
        refresh()
    }

    private val a11yUri: Uri = Settings.Secure.getUriFor(
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    )

    private val contentObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) {
            refresh()
        }
    }

    /**
     * Attaches this observer to [owner]'s lifecycle. On each [LifecycleOwner.onResume]
     * the probe runs synchronously and the StateFlow emits. Listeners are cleaned up
     * on [LifecycleOwner.onPause] to prevent leaks.
     */
    fun bind(owner: LifecycleOwner) {
        owner.lifecycle.addObserver(object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) {
                refresh()
                registerListeners()
            }

            override fun onPause(owner: LifecycleOwner) {
                unregisterListeners()
            }
        })
    }

    private fun registerListeners() {
        try {
            a11yManager.addAccessibilityStateChangeListener(a11yListener)
        } catch (_: Throwable) {
            // Best-effort; ContentObserver fallback below covers this.
        }
        try {
            context.contentResolver.registerContentObserver(
                a11yUri,
                false,
                contentObserver,
            )
        } catch (_: Throwable) {
            // Non-critical; onResume polling remains the contract floor.
        }
    }

    private fun unregisterListeners() {
        try { a11yManager.removeAccessibilityStateChangeListener(a11yListener) } catch (_: Throwable) {}
        try { context.contentResolver.unregisterContentObserver(contentObserver) } catch (_: Throwable) {}
    }
}
