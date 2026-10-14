package com.krypt.app.ui.components

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.ui.theme.KryptTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Krypt's keypad when taps queue up behind a busy main thread: they reach the keys back to
 * back, before the next recomposition. The frames are held here to do the same.
 */
@RunWith(AndroidJUnit4::class)
class PinEntryTest {

    @get:Rule val compose = createComposeRule()

    private var pin by mutableStateOf("")

    private fun showPinEntry() {
        compose.setContent {
            KryptTheme { PinEntry(pin = pin, onPinChange = { pin = it }, maxLength = 8) }
        }
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
    }

    private fun tap(key: String) =
        compose.onNodeWithText(key).performSemanticsAction(SemanticsActions.OnClick)

    private fun tapDelete() =
        compose.onNodeWithContentDescription("Delete").performSemanticsAction(SemanticsActions.OnClick)

    @Test
    fun digitsTappedBeforeTheNextFrame_allCount() {
        showPinEntry()
        tap("2")
        tap("4")
        tap("6")

        assertEquals("246", pin)
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithTag(PIN_ENTRY_TAG).assertContentDescriptionEquals("Guardian PIN, 3 digits entered")
    }

    @Test
    fun deletesTappedBeforeTheNextFrame_allCount() {
        pin = "2468"
        showPinEntry()
        tapDelete()
        tapDelete()

        assertEquals("24", pin)
    }

    @Test
    fun aPinTheCallerClears_isWhatTheNextKeyBuildsOn() {
        showPinEntry()
        tap("2")
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { pin = "" } // as after a submit
        compose.mainClock.advanceTimeByFrame()
        tap("5")

        assertEquals("5", pin)
    }

    @Test
    fun aFullPin_takesNoMoreDigits() {
        pin = "1234567"
        showPinEntry()
        tap("8")
        tap("9")

        assertEquals("12345678", pin)
    }
}
