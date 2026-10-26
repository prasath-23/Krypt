package com.krypt.app.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.common.Outcome
import com.krypt.app.data.LockState
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.deeplink.ApprovalConsumer
import com.krypt.app.deeplink.ApprovalOutcome
import com.krypt.app.deeplink.UnlockRequestIssuer
import com.krypt.app.guardian.DAYS_FIELD_TAG
import com.krypt.app.guardian.MINUTES_FIELD_TAG
import com.krypt.app.guardian.PIN_FIELD_TAG
import com.krypt.app.ui.guardian.GuardianActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The Guardian approval screen opened from a `krypt://request` link, driven
 * through Compose UI. The request is issued on the same device, as it would
 * be on the Subject's phone.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class GuardianScreenE2ETest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var state: TestState
    @Inject lateinit var issuer: UnlockRequestIssuer
    @Inject lateinit var consumer: ApprovalConsumer
    @Inject lateinit var lockedApps: LockedAppsRepository

    @Before
    fun setUp() {
        hilt.inject()
        state.reset(onboardingComplete = true, pinConfigured = true)
        Intents.init()
        intending(hasAction(Intent.ACTION_CHOOSER))
            .respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    private fun issueRequest(): String =
        (runBlocking { issuer.issue("com.example.target") } as Outcome.Ok).value

    private fun open(url: String) = ActivityScenario.launch<GuardianActivity>(
        Intent(Intent.ACTION_VIEW, Uri.parse(url), targetContext, GuardianActivity::class.java)
    )

    /** Step 1's Continue opens the PIN sheet, unless it is still up (a wrong PIN leaves it open). */
    private fun ComposeTestRule.openPinSheet() {
        if (onAllNodesWithText("Approve and send").fetchSemanticsNodes().isEmpty()) {
            awaitText("Continue")
            onNodeWithText("Continue").performClick()
        }
        awaitText("Approve and send")
    }

    private fun ComposeTestRule.enterGuardianPin(pin: String) {
        openPinSheet()
        onNodeWithTag(PIN_FIELD_TAG).performTextInput(pin)
        onNodeWithText("Approve and send").performClick()
    }

    @Test
    fun correctPin_sharesAnApprovalLink() {
        open(issueRequest()).use {
            compose.awaitText("com.example.target")
            compose.enterGuardianPin(TEST_PIN)
            compose.awaitText("Sent.")
        }
        intended(
            allOf(
                hasAction(Intent.ACTION_CHOOSER),
                hasExtra(
                    equalTo(Intent.EXTRA_INTENT),
                    allOf(
                        hasExtra(equalTo(Intent.EXTRA_TEXT), containsString("Krypt reply for com.example.target")),
                        hasExtra(equalTo(Intent.EXTRA_TEXT), containsString("krypt://approve?")),
                    ),
                ),
            )
        )
    }

    @Test
    fun theDurationThePickedChip_isWhatTheApprovalUnlocks() {
        open(issueRequest()).use {
            compose.awaitText("30 min")
            compose.onNodeWithText("30 min").performClick()
            compose.awaitText("com.example.target, 30 minutes, once.")
            compose.enterGuardianPin(TEST_PIN)
            compose.awaitText("Sent.")
        }
        val shared = Intents.getIntents()
            .first { it.action == Intent.ACTION_CHOOSER }
            .let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_INTENT, Intent::class.java) }!!
            .getStringExtra(Intent.EXTRA_TEXT)!!
        assertEquals(true, shared.contains("Krypt reply for com.example.target: 30 minutes, once."))

        // The request was issued on this device, so it can consume its own approval.
        val url = Regex("""krypt://approve\?\S+""").find(shared)!!.value
        val grant = (runBlocking { consumer.consume(url) } as Outcome.Ok).value as ApprovalOutcome.OneTime
        assertEquals(30 * 60_000L, grant.grantExpiresAtMs - grant.grantedAtMs)
    }

    @Test
    fun daily_withCustomMinutesAndDays_isWhatTheApprovalAllows() {
        open(issueRequest()).use {
            compose.awaitText("Daily")
            compose.onNodeWithText("Daily").performClick()
            // With Daily there are two Custom chips: minutes first, then days.
            compose.onAllNodesWithText("Custom")[0].performClick()
            compose.onNodeWithTag(MINUTES_FIELD_TAG).performTextInput("1")
            compose.onAllNodesWithText("Custom")[1].performScrollTo().performClick()
            compose.onNodeWithTag(DAYS_FIELD_TAG).performScrollTo().performTextInput("2")
            compose.awaitText("com.example.target, 1 minute a day for 2 days.")
            compose.enterGuardianPin(TEST_PIN)
            compose.awaitText("Sent.")
        }
        val shared = Intents.getIntents()
            .first { it.action == Intent.ACTION_CHOOSER }
            .let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_INTENT, Intent::class.java) }!!
            .getStringExtra(Intent.EXTRA_TEXT)!!
        assertEquals(true, shared.contains("Krypt reply for com.example.target: 1 minute a day for 2 days."))

        val url = Regex("""krypt://approve\?\S+""").find(shared)!!.value
        val daily = (runBlocking { consumer.consume(url) } as Outcome.Ok).value as ApprovalOutcome.EveryDay
        assertEquals(1, daily.allowance.minutesPerDay)
        assertEquals(1L, daily.allowance.lastDay.toEpochDay() - daily.allowance.firstDay.toEpochDay())
    }

    @Test
    fun aRequestFromAnOlderPhone_offersOneTimeOnly() {
        open(withCaps(null)).use {
            compose.awaitText("30 min")
            compose.onAllNodes(hasText("Daily")).assertCountEquals(0)
            compose.onAllNodes(hasText("For good")).assertCountEquals(0)
        }
    }

    @Test
    fun aPhoneThatTakesOnlyEveryDayApprovals_isOfferedNoForGood() {
        open(withCaps("daily")).use {
            compose.awaitText("Daily")
            compose.onAllNodes(hasText("For good")).assertCountEquals(0)
        }
    }

    @Test
    fun forGood_removesTheAppsLock() {
        runBlocking { lockedApps.lock("com.example.target", "Target") }
        open(issueRequest()).use {
            compose.awaitText("For good")
            compose.onNodeWithText("For good").performClick()
            // No duration to choose, and the sentence says what it does.
            compose.awaitText("com.example.target, unlocked for good.")
            compose.onAllNodes(hasText("For how long")).assertCountEquals(0)
            compose.enterGuardianPin(TEST_PIN)
            compose.awaitText("Sent.")
        }
        val shared = Intents.getIntents()
            .first { it.action == Intent.ACTION_CHOOSER }
            .let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_INTENT, Intent::class.java) }!!
            .getStringExtra(Intent.EXTRA_TEXT)!!
        assertEquals(true, shared.contains("Krypt reply for com.example.target: unlocked for good."))

        val url = Regex("""krypt://approve\?\S+""").find(shared)!!.value
        val outcome = (runBlocking { consumer.consume(url) } as Outcome.Ok).value
        assertEquals(true, outcome is ApprovalOutcome.ForGood)
        assertEquals(LockState.UNLOCKED, runBlocking { lockedApps.findByPackage("com.example.target") }?.lockState)
    }

    /** A request as an older phone sends it: [caps] in place of today's, or none at all. */
    private fun withCaps(caps: String?): String =
        Regex("&caps=[^&]*").replace(issueRequest(), if (caps == null) "" else "&caps=$caps")

    @Test
    fun unusableTypedMinutes_keepContinueOff() {
        open(issueRequest()).use {
            compose.awaitText("Custom")
            compose.onAllNodesWithText("Custom")[0].performClick()
            compose.onNodeWithTag(MINUTES_FIELD_TAG).performTextInput("0")
            compose.awaitText("Choose 1 to 1440 minutes.")
            compose.onNodeWithText("Continue").assertIsNotEnabled()
            // No PIN sheet, so nothing to approve with.
            compose.onAllNodesWithText("Approve and send").assertCountEquals(0)
        }
        intended(hasAction(Intent.ACTION_CHOOSER), times(0))
    }

    @Test
    fun dismissingThePinSheet_keepsTheChoice_andClearsThePin() {
        open(issueRequest()).use {
            compose.awaitText("30 min")
            compose.onNodeWithText("30 min").performClick()
            compose.openPinSheet()
            compose.onNodeWithTag(PIN_FIELD_TAG).performTextInput("12")
            Espresso.pressBack()
            compose.waitUntil(10_000) {
                compose.onAllNodesWithText("Approve and send").fetchSemanticsNodes().isEmpty()
            }
            compose.awaitText("com.example.target, 30 minutes, once.")

            compose.openPinSheet()
            compose.onNodeWithTag(PIN_FIELD_TAG).assertContentDescriptionEquals("Guardian PIN, 0 digits entered")
        }
        intended(hasAction(Intent.ACTION_CHOOSER), times(0))
    }

    @Test
    fun wrongPins_countDownThenLockOut_andNoApprovalIsShared() {
        open(issueRequest()).use {
            compose.enterGuardianPin("0000")
            compose.awaitText("That PIN isn't right. 2 tries left.")
            compose.enterGuardianPin("1111")
            compose.awaitText("That PIN isn't right. 1 try left.")
            compose.enterGuardianPin("2222")
            compose.awaitText("Too many wrong PINs", substring = true)
            compose.onNodeWithText("Approve and send").assertIsNotEnabled()
            // The button is also disabled for an empty PIN, so check the PIN entry itself takes nothing.
            compose.onNodeWithTag(PIN_FIELD_TAG).assertIsNotEnabled()
        }
        intended(hasAction(Intent.ACTION_CHOOSER), times(0))
    }

    @Test
    fun expiredRequest_isRejectedBeforeAnyPin() {
        val url = issueRequest()
        val iat = Regex("iat=(\\d+)").find(url)!!.groupValues[1].toLong()
        open(url.replace("iat=$iat", "iat=${iat - 3_600}")).use {
            compose.awaitText("This request expired", substring = true)
        }
    }

    @Test
    fun garbledRequest_isUnreadable() {
        open("krypt://request?v=1&req=not-a-uuid").use {
            compose.awaitText("Couldn't read this request.")
        }
    }

    @Test
    fun legacyPairingLink_isUnknown() {
        open("krypt://pair?v=1&sub=12345678-1234-4abc-8def-123456789abc").use {
            compose.awaitText("Not a Krypt request")
        }
    }
}
