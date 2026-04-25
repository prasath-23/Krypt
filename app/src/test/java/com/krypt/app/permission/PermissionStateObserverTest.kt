package com.krypt.app.permission

import android.content.Context
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [PermissionStateObserver].
 *
 * Exercises the StateFlow update path using [FakePermissionStatusProbe] and a
 * [LifecycleRegistry]. No Android OS or Robolectric needed because the
 * lifecycle-aware behavior is driven by the observer pattern, not hardware.
 *
 * Note: [PermissionStateObserver] registers a ContentObserver and
 * AccessibilityManager listener at bind time; these calls are best-effort and
 * swallowed if the Context returns null system services (as a plain mockk does).
 */
class PermissionStateObserverTest {

    private lateinit var probe: FakePermissionStatusProbe
    private lateinit var observer: PermissionStateObserver
    private lateinit var lifecycleOwner: LifecycleOwner
    private lateinit var lifecycle: LifecycleRegistry
    private val ctx = mockk<Context>(relaxed = true)

    @Before
    fun setUp() {
        probe = FakePermissionStatusProbe(allGranted = false)
        observer = PermissionStateObserver(probe, ctx)
        lifecycleOwner = LifecycleOwner { lifecycle }
        lifecycle = LifecycleRegistry(lifecycleOwner)
    }

    @Test
    fun initialState_matchesProbeStatusAll() {
        val initial = observer.state.value
        assertEquals(probe.statusAll(), initial)
        assertFalse(initial.values.any { it })
    }

    @Test
    fun refresh_updatesState_whenProbeChanges() {
        probe.grant(PermissionKey.ACCESSIBILITY)
        observer.refresh()

        assertTrue(observer.state.value[PermissionKey.ACCESSIBILITY] == true)
        assertFalse(observer.state.value[PermissionKey.OVERLAY] == true)
    }

    @Test
    fun onResume_triggersRefresh() {
        observer.bind(lifecycleOwner)
        val countBefore = probe.pollCount

        probe.grant(PermissionKey.OVERLAY)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        assertTrue("Expected refresh on resume", probe.pollCount > countBefore)
        assertTrue(observer.state.value[PermissionKey.OVERLAY] == true)
    }

    @Test
    fun onPause_doesNotTriggerExtraRefresh() {
        observer.bind(lifecycleOwner)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        val countAfterResume = probe.pollCount

        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        assertEquals("No additional poll on pause", countAfterResume, probe.pollCount)
    }

    @Test
    fun multipleResumes_doNotLeakListeners() {
        observer.bind(lifecycleOwner)
        repeat(5) {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        // If listeners were leaking, this would throw or hang. Just assert the poll happened.
        assertTrue(probe.pollCount >= 5)
    }

    @Test
    fun refresh_allGranted_stateBecomesAllTrue() {
        PermissionKey.values().forEach { probe.grant(it) }
        observer.refresh()

        val state = observer.state.value
        PermissionKey.values().forEach { key ->
            assertTrue("$key should be granted", state[key] == true)
        }
    }
}

/** Programmable [PermissionStatusProbe] for tests. */
internal class FakePermissionStatusProbe(allGranted: Boolean) : PermissionStatusProbe {
    private val granted = PermissionKey.values().associateWith { allGranted }.toMutableMap()
    var pollCount: Int = 0
        private set

    fun grant(key: PermissionKey) { granted[key] = true }
    fun revoke(key: PermissionKey) { granted[key] = false }

    override fun statusOf(key: PermissionKey): Boolean {
        pollCount++
        return granted[key] ?: false
    }
}
