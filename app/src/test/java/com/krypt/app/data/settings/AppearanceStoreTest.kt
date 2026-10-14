package com.krypt.app.data.settings

import com.krypt.app.security.FakeSharedPreferences
import org.junit.Assert.assertEquals
import org.junit.Test

class AppearanceStoreTest {

    private val prefs = FakeSharedPreferences()
    private val inProcess = mutableListOf<Appearance>()
    private val toSystem = mutableListOf<Appearance>()

    private val nightMode = object : AppearanceStore.NightMode {
        override fun applyInProcess(appearance: Appearance) {
            inProcess += appearance
        }

        override fun applyToSystem(appearance: Appearance) {
            toSystem += appearance
        }
    }

    private fun store() = AppearanceStore(prefs, nightMode)

    @Test
    fun followsThePhone_untilSomethingIsChosen() {
        val store = store()
        assertEquals(Appearance.SYSTEM, store.appearance.value)

        store.applyStored()
        store.syncSystem()
        assertEquals(listOf(Appearance.SYSTEM), inProcess)
        assertEquals(listOf(Appearance.SYSTEM), toSystem)
    }

    @Test
    fun aChoice_isAppliedEverywhereAtOnce_andKeptForTheNextStart() {
        store().set(Appearance.DARK)
        // Both: AppCompat for Krypt's activities, the system for its starting windows.
        assertEquals(listOf(Appearance.DARK), inProcess)
        assertEquals(listOf(Appearance.DARK), toSystem)

        val restarted = store()
        assertEquals(Appearance.DARK, restarted.appearance.value)
        restarted.applyStored()
        assertEquals(listOf(Appearance.DARK, Appearance.DARK), inProcess)
    }

    @Test
    fun choosingTheCurrentValueAgain_changesNothing() {
        val store = store()
        store.set(Appearance.LIGHT)
        store.set(Appearance.LIGHT)

        assertEquals(listOf(Appearance.LIGHT), inProcess)
        assertEquals(listOf(Appearance.LIGHT), toSystem)
        assertEquals(Appearance.LIGHT, store.appearance.value)
    }

    @Test
    fun backToSystemDefault_isStoredToo() {
        store().set(Appearance.LIGHT)
        store().set(Appearance.SYSTEM)

        assertEquals(Appearance.SYSTEM, store().appearance.value)
    }

    @Test
    fun anUnknownStoredValue_fallsBackToSystemDefault() {
        prefs.edit().putString("appearance", "SEPIA").apply()

        assertEquals(Appearance.SYSTEM, store().appearance.value)
    }
}
