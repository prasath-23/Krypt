package com.krypt.app.service

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppWindowsTest {

    /** What Android lists: the apps' windows on screen, or null when it doesn't say. */
    private var listed: List<AppWindow>? = emptyList()

    /** Each window's package, as its root node would give it. */
    private val roots = HashMap<Int, String>()
    private val lookedUp = mutableListOf<Int>()
    private var found = 0

    private fun TestScope.appWindows() = AppWindows(
        scope = backgroundScope,
        background = StandardTestDispatcher(testScheduler),
        appWindows = { listed },
        lookUp = { id ->
            lookedUp += id
            roots[id]
        },
        onFound = { found++ },
    )

    @Test
    fun theAppWithFocus_isInFront_andEveryAppShowingIsOnScreen() = runTest {
        val w = appWindows()
        w.record(1, OTHER)
        w.record(2, APP)
        listed = listOf(AppWindow(1, focused = false, active = false), AppWindow(2, focused = true, active = true))

        assertEquals(Screen(inFront = APP, showing = setOf(OTHER, APP)), w.onScreen())
    }

    @Test
    fun aLauncherThatShowedBehindTheApp_doesntTakeItsPlace() = runTest {
        // A home gesture started and was cancelled: the launcher's window showed (its event came last),
        // but the app kept focus, and the launcher isn't listed as on screen.
        val w = appWindows()
        w.record(2, APP)
        w.record(1, LAUNCHER)
        listed = listOf(AppWindow(2, focused = true, active = true))

        assertEquals(Screen(inFront = APP, showing = setOf(APP)), w.onScreen())
    }

    @Test
    fun anAppInASmallWindow_isOnScreen_withoutFocus() = runTest {
        val w = appWindows()
        w.record(1, OTHER)
        w.record(2, APP)
        listed = listOf(
            AppWindow(1, focused = true, active = true),
            AppWindow(2, focused = false, active = false, pip = true),
        )

        assertEquals(Screen(inFront = OTHER, showing = setOf(OTHER, APP), pip = setOf(APP)), w.onScreen())
    }

    @Test
    fun withNoWindowFocused_theOneInUseIsInFront() = runTest {
        val w = appWindows()
        w.record(1, OTHER)
        w.record(2, APP)
        listed = listOf(AppWindow(1, focused = false, active = false), AppWindow(2, focused = false, active = true))

        assertEquals(APP, w.onScreen()?.inFront)
    }

    @Test
    fun withNoAppWindowOnScreen_noneIsInFront() = runTest {
        // The notification shade, say, covers the app: Android lists only system windows.
        listed = emptyList()

        assertEquals(Screen(inFront = null, showing = emptySet()), appWindows().onScreen())
    }

    @Test
    fun whenAndroidDoesntSay_thereIsNoAnswer() = runTest {
        listed = null

        assertNull(appWindows().onScreen())
    }

    @Test
    fun aWindowNotHeardFrom_isLookedUpOnceOffTheMainThread_andThenCounts() = runTest {
        // Just after Krypt starts: the app in front sent no window event since.
        val w = appWindows()
        roots[7] = APP
        listed = listOf(AppWindow(7, focused = true, active = true))

        assertEquals(Screen(inFront = null, showing = emptySet()), w.onScreen())
        assertEquals("asked again before the answer came", Screen(inFront = null, showing = emptySet()), w.onScreen())
        runCurrent()

        assertEquals(listOf(7), lookedUp)
        assertEquals(1, found)
        assertEquals(Screen(inFront = APP, showing = setOf(APP)), w.onScreen())
    }

    @Test
    fun aWindowThatCantBeLookedUp_staysUnknown_andNoOneIsTold() = runTest {
        val w = appWindows()
        listed = listOf(AppWindow(7, focused = true, active = true))

        w.onScreen()
        runCurrent()

        assertEquals(0, found)
        assertEquals(Screen(inFront = null, showing = emptySet()), w.onScreen())
    }

    @Test
    fun anAppsWindows_areTheOnesRecordedForIt() = runTest {
        val w = appWindows()
        w.record(1, APP)
        w.record(2, OTHER)
        w.record(3, APP)
        w.record(-1, APP) // no window id: ignored

        assertEquals(setOf(1, 3), w.windowsOf(APP).toSet())
    }

    private companion object {
        const val APP = "com.example.app"
        const val OTHER = "com.example.other"
        const val LAUNCHER = "com.example.launcher"
    }
}
