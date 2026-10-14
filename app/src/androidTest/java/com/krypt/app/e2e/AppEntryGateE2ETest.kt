package com.krypt.app.e2e

import android.app.Activity
import android.app.Instrumentation
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.subject.ApprovalTrampolineActivity
import com.krypt.app.ui.components.PIN_ENTRY_TAG
import com.krypt.app.ui.guardian.GuardianActivity
import com.krypt.app.ui.main.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anyOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/** The Guardian PIN gate (Krypt Admin, "Guardian only") in front of the Home Screen. */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AppEntryGateE2ETest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var state: TestState

    @Before
    fun setUp() {
        hilt.inject()
        state.reset(onboardingComplete = true, pinConfigured = true)
        Intents.init()
        val ok = Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        intending(
            anyOf(
                hasComponent(GuardianActivity::class.java.name),
                hasComponent(ApprovalTrampolineActivity::class.java.name),
            )
        ).respondWith(ok)
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun wrongPin_showsTheTriesLeft() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.enterPin("1111")
            compose.awaitText("That PIN isn't right. 2 tries left.")
        }
    }

    @Test
    fun threeWrongPins_lockTheScreenOut() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.enterPin("1111")
            compose.awaitText("That PIN isn't right. 2 tries left.")
            compose.enterPin("2222")
            compose.awaitText("That PIN isn't right. 1 try left.")
            compose.enterPin("3333")
            compose.awaitText("Too many wrong PINs", substring = true)
            compose.onNodeWithText("Continue").assertIsNotEnabled()
            // Continue is also disabled for an empty PIN, so check the PIN entry itself takes nothing.
            compose.onNodeWithTag(PIN_ENTRY_TAG).assertIsNotEnabled()
        }
    }

    @Test
    fun correctPin_opensHome_andLeavingKryptLocksItAgain() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.enterPin(TEST_PIN)
            compose.awaitText("Search apps")

            // Home button / screen off: the activity is stopped, then resumed.
            scenario.moveToState(Lifecycle.State.CREATED)
            scenario.moveToState(Lifecycle.State.RESUMED)

            compose.awaitText("Enter the Guardian PIN to manage apps")
        }
    }

    @Test
    fun pasteALink_opensACopiedRequestInTheGuardianScreen() {
        val request = "krypt://request?v=1&req=12345678-1234-4abc-8def-123456789abc&app=com.example.app"
        copyToClipboard(
            "Krypt access request for Example.\nTap the link to review it. If it doesn't open, " +
                "copy this whole message, open Krypt and tap Paste a link.\n$request"
        )
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitText("Paste a link")
            compose.onNodeWithText("Paste a link").performClick()
            compose.waitForIdle()
        }
        intended(allOf(hasComponent(GuardianActivity::class.java.name), hasData(request)))
    }

    @Test
    fun pasteALink_withNoKryptLinkOnTheClipboard_saysSo() {
        copyToClipboard("see you at 5")
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.awaitText("Paste a link")
            compose.onNodeWithText("Paste a link").performClick()
            compose.awaitText("No Krypt link found", substring = true)
        }
    }
}

/**
 * Type [pin] into the PIN entry and tap Continue. The entry is the row of
 * dots over Krypt's own keypad; it takes text input the way a field does.
 */
fun ComposeTestRule.enterPin(pin: String) {
    waitUntil(10_000) {
        onAllNodes(hasSetTextAction()).fetchSemanticsNodes().isNotEmpty() &&
            onAllNodes(hasText("Continue")).fetchSemanticsNodes().isNotEmpty()
    }
    onNode(hasSetTextAction()).performTextInput(pin)
    onNodeWithText("Continue").performClick()
}

/** Wait (PBKDF2 is slow on emulators) until [text] is on screen. */
fun ComposeTestRule.awaitText(text: String, substring: Boolean = false) {
    waitUntil(60_000) {
        onAllNodes(hasText(text, substring = substring)).fetchSemanticsNodes().isNotEmpty()
    }
}
