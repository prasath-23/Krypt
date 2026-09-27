package com.krypt.app.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.ComposeTestRule
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.core.content.IntentCompat
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.common.Outcome
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

    private fun ComposeTestRule.enterGuardianPin(pin: String) {
        awaitText("Verify & send approval")
        onNodeWithTag(PIN_FIELD_TAG).performScrollTo().performTextInput(pin)
        onNodeWithText("Verify & send approval").performScrollTo().performClick()
    }

    @Test
    fun correctPin_sharesAnApprovalLink() {
        open(issueRequest()).use {
            compose.awaitText("com.example.target")
            compose.enterGuardianPin(TEST_PIN)
            compose.awaitText("Approval sent", substring = true)
        }
        intended(
            allOf(
                hasAction(Intent.ACTION_CHOOSER),
                hasExtra(
                    equalTo(Intent.EXTRA_INTENT),
                    allOf(
                        hasExtra(equalTo(Intent.EXTRA_TEXT), containsString("Krypt approval for com.example.target")),
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
            compose.awaitText("Allows: 30 minutes, one time")
            compose.enterGuardianPin(TEST_PIN)
            compose.awaitText("Approval sent", substring = true)
        }
        val shared = Intents.getIntents()
            .first { it.action == Intent.ACTION_CHOOSER }
            .let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_INTENT, Intent::class.java) }!!
            .getStringExtra(Intent.EXTRA_TEXT)!!
        assertEquals(true, shared.contains("Krypt approval for com.example.target: 30 minutes, one time."))

        // The request was issued on this device, so it can consume its own approval.
        val url = Regex("""krypt://approve\?\S+""").find(shared)!!.value
        val grant = (runBlocking { consumer.consume(url) } as Outcome.Ok).value as ApprovalOutcome.OneTime
        assertEquals(30 * 60_000L, grant.grantExpiresAtMs - grant.grantedAtMs)
    }

    @Test
    fun everyDay_withTypedMinutesAndDays_isWhatTheApprovalAllows() {
        open(issueRequest()).use {
            compose.awaitText("Every day")
            compose.onNodeWithText("Every day").performClick()
            compose.onNodeWithText("Set minutes").performClick()
            compose.onNodeWithTag(MINUTES_FIELD_TAG).performTextInput("1")
            compose.onNodeWithText("Set days").performScrollTo().performClick()
            compose.onNodeWithTag(DAYS_FIELD_TAG).performScrollTo().performTextInput("2")
            compose.awaitText("Allows: 1 minute a day for 2 days")
            compose.enterGuardianPin(TEST_PIN)
            compose.awaitText("Approval sent", substring = true)
        }
        val shared = Intents.getIntents()
            .first { it.action == Intent.ACTION_CHOOSER }
            .let { IntentCompat.getParcelableExtra(it, Intent.EXTRA_INTENT, Intent::class.java) }!!
            .getStringExtra(Intent.EXTRA_TEXT)!!
        assertEquals(true, shared.contains("Krypt approval for com.example.target: 1 minute a day for 2 days."))

        val url = Regex("""krypt://approve\?\S+""").find(shared)!!.value
        val daily = (runBlocking { consumer.consume(url) } as Outcome.Ok).value as ApprovalOutcome.EveryDay
        assertEquals(1, daily.allowance.minutesPerDay)
        assertEquals(1L, daily.allowance.lastDay.toEpochDay() - daily.allowance.firstDay.toEpochDay())
    }

    @Test
    fun aRequestFromAnOlderPhone_offersOneTimeOnly() {
        open(issueRequest().replace("&caps=daily", "")).use {
            compose.awaitText("30 min")
            compose.onAllNodes(hasText("Every day")).assertCountEquals(0)
        }
    }

    @Test
    fun unusableTypedMinutes_blockTheApproval() {
        open(issueRequest()).use {
            compose.awaitText("Set minutes")
            compose.onNodeWithText("Set minutes").performClick()
            compose.onNodeWithTag(MINUTES_FIELD_TAG).performTextInput("0")
            compose.awaitText("Enter a number of minutes from 1 to 1440.")
            compose.onNodeWithTag(PIN_FIELD_TAG).performTextInput(TEST_PIN)
            compose.onNodeWithText("Verify & send approval").assertIsNotEnabled()
        }
        intended(hasAction(Intent.ACTION_CHOOSER), times(0))
    }

    @Test
    fun wrongPins_countDownThenLockOut_andNoApprovalIsShared() {
        open(issueRequest()).use {
            compose.enterGuardianPin("0000")
            compose.awaitText("Wrong PIN. 2 attempts left.")
            compose.enterGuardianPin("1111")
            compose.awaitText("Wrong PIN. 1 attempt left.")
            compose.enterGuardianPin("2222")
            compose.awaitText("Too many wrong PINs", substring = true)
            compose.onNodeWithText("Verify & send approval").assertIsNotEnabled()
        }
        intended(hasAction(Intent.ACTION_CHOOSER), times(0))
    }

    @Test
    fun expiredRequest_isRejectedBeforeAnyPin() {
        val url = issueRequest()
        val iat = Regex("iat=(\\d+)").find(url)!!.groupValues[1].toLong()
        open(url.replace("iat=$iat", "iat=${iat - 3_600}")).use {
            compose.awaitText("This unlock request has expired", substring = true)
        }
    }

    @Test
    fun garbledRequest_isUnreadable() {
        open("krypt://request?v=1&req=not-a-uuid").use {
            compose.awaitText("Could not read this unlock request.")
        }
    }

    @Test
    fun legacyPairingLink_isUnknown() {
        open("krypt://pair?v=1&sub=12345678-1234-4abc-8def-123456789abc").use {
            compose.awaitText("Unknown Krypt link")
        }
    }
}
