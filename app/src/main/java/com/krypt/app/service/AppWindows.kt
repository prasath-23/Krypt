package com.krypt.app.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * An app's window as Android lists it: its id, whether it has input focus
 * or is the one in use, and whether it is a picture-in-picture window.
 */
data class AppWindow(val id: Int, val focused: Boolean, val active: Boolean, val pip: Boolean = false)

/**
 * What is on screen, as Android sees it (Amendment 8): [inFront] is the app
 * whose window has focus, null when that window's app isn't known yet;
 * [showing] is every app with a window on screen, in full screen, split
 * screen, a small floating window or picture-in-picture; [pip] those of them
 * in picture-in-picture.
 */
data class Screen(val inFront: String?, val showing: Set<String>, val pip: Set<String> = emptySet())

/**
 * Which apps are on screen, from Android's list of windows rather than the
 * last window event alone (Amendment 8).
 *
 * A window showing isn't always its app coming to the front. While a home
 * gesture runs, the launcher shows behind the app; when the gesture is
 * cancelled the app never lost focus, and on some phones it sends nothing
 * new. And an app in a small window over another app is in use without
 * having focus at all.
 *
 * Window ids are matched to apps from the window events the service gets
 * anyway ([record]), so asking reads nothing an app shows. A window not
 * heard from yet, as just after Krypt starts, is matched through [lookUp]
 * (its root node's package name, nothing else) off the main thread, and
 * [onFound] says when that is done.
 *
 * [scope] must be single-threaded (the service uses the main thread).
 */
class AppWindows(
    private val scope: CoroutineScope,
    private val background: CoroutineDispatcher,
    /** The apps' windows on screen; null when Android won't say. */
    private val appWindows: () -> List<AppWindow>?,
    private val lookUp: (windowId: Int) -> String?,
    private val onFound: () -> Unit,
) {
    /** Window id -> package, for the [MAX_KNOWN] windows used last. */
    private val known = object : LinkedHashMap<Int, String>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, String>?) = size > MAX_KNOWN
    }
    private val lookingUp = HashSet<Int>()

    /** A window event said that window [windowId] is [pkg]'s. */
    fun record(windowId: Int, pkg: String) {
        if (windowId >= 0) known[windowId] = pkg
    }

    /** The windows on screen that are [pkg]'s, as far as is known. */
    fun windowsOf(pkg: String): List<Int> = known.filterValues { it == pkg }.keys.toList()

    /**
     * What is on screen now; null when Android doesn't say. A window whose
     * app isn't known yet is left out, looked up, and [onFound] is called
     * once it is.
     */
    fun onScreen(): Screen? {
        val windows = appWindows() ?: return null
        val showing = LinkedHashSet<String>()
        val pip = HashSet<String>()
        for (window in windows) {
            val pkg = packageOf(window.id) ?: continue
            showing += pkg
            if (window.pip) pip += pkg
        }
        val front = windows.firstOrNull { it.focused } ?: windows.firstOrNull { it.active }
        return Screen(inFront = front?.let { known[it.id] }, showing = showing, pip = pip)
    }

    private fun packageOf(windowId: Int): String? {
        known[windowId]?.let { return it }
        lookUpLater(windowId)
        return null
    }

    private fun lookUpLater(windowId: Int) {
        if (!lookingUp.add(windowId)) return
        scope.launch {
            val pkg = withContext(background) { lookUp(windowId) }
            lookingUp.remove(windowId)
            if (pkg != null) {
                known[windowId] = pkg
                onFound()
            }
        }
    }

    private companion object {
        const val MAX_KNOWN = 32
    }
}
