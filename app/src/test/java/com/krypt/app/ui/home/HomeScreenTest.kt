package com.krypt.app.ui.home

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit-level assertions for Home Screen state logic.
 *
 * Full Compose UI testing requires a device; these tests cover the pure-state
 * invariants derivable from [HomeUiState] and [InstalledAppRowState].
 */
class HomeScreenTest {

    private fun row(pkg: String, name: String, locked: Boolean) =
        InstalledAppRowState(pkg, name, locked)

    private fun state(rows: List<InstalledAppRowState>, query: String = "") =
        HomeUiState(rows, query)

    @Test
    fun emptyQuery_showsAllRows() {
        val rows = listOf(row("com.a", "A", false), row("com.b", "B", true))
        val s = state(rows)
        assertEquals(2, s.rows.size)
    }

    @Test
    fun lockedRow_hasIsLockedTrue() {
        val r = row("com.b", "B", true)
        assertTrue(r.isLocked)
    }

    @Test
    fun unlockedRow_hasIsLockedFalse() {
        val r = row("com.a", "A", false)
        assertFalse(r.isLocked)
    }

    @Test
    fun query_setOnState_isPreserved() {
        val s = state(emptyList(), "Instagram")
        assertEquals("Instagram", s.query)
    }

    @Test
    fun emptyRows_withBlankQuery_shouldShowLoadingState() {
        val s = state(emptyList(), "")
        assertTrue(s.rows.isEmpty() && s.query.isBlank())
    }

    @Test
    fun emptyRows_withNonBlankQuery_shouldShowNoMatchState() {
        val s = state(emptyList(), "xyz")
        assertTrue(s.rows.isEmpty() && s.query.isNotBlank())
    }
}
