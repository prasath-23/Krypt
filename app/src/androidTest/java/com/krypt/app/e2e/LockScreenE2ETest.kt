package com.krypt.app.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.ComponentName
import android.content.Intent
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasCategories
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtra
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.data.KryptDatabase
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.data.daily.DailyAllowance
import com.krypt.app.data.daily.DailyAllowanceEntity
import com.krypt.app.data.daily.DailyAllowanceMeter
import com.krypt.app.data.daily.DailyUsageEntity
import com.krypt.app.subject.ApprovalTrampolineActivity
import com.krypt.app.ui.link.LinkReceiverActivity
import com.krypt.app.ui.lock.LockScreenActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.arrayContaining
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.equalTo
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

/**
 * The lock screen that replaces a locked app (Amendment 2). Uses the
 * Settings app as the "locked" app because every image has it; the test
 * never launches it.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LockScreenE2ETest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var state: TestState
    @Inject lateinit var db: KryptDatabase
    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var dailyMeter: DailyAllowanceMeter
    @Inject lateinit var autoTime: FakeAutoTimeSetting

    private val lockedPackage = "com.android.settings"

    @Before
    fun setUp() {
        hilt.inject()
        state.reset(onboardingComplete = true, pinConfigured = true)
        Intents.init()
        // Keep the share sheet, the launcher and the approval screen from really
        // opening. Stub only these: a catch-all would also swallow androidx.test's
        // own helper activities.
        val ok = Instrumentation.ActivityResult(Activity.RESULT_OK, null)
        intending(hasAction(Intent.ACTION_CHOOSER)).respondWith(ok)
        intending(hasCategories(setOf(Intent.CATEGORY_HOME))).respondWith(ok)
        intending(hasComponent(ApprovalTrampolineActivity::class.java.name)).respondWith(ok)
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    private fun launch() =
        ActivityScenario.launch<LockScreenActivity>(LockScreenActivity.intentFor(targetContext, lockedPackage))

    private fun waitForText(text: String, substring: Boolean = false) {
        compose.waitUntil(10_000) {
            compose.onAllNodes(androidx.compose.ui.test.hasText(text, substring = substring))
                .fetchSemanticsNodes().isNotEmpty()
        }
    }

    private val homeIntent = allOf(hasAction(Intent.ACTION_MAIN), hasCategories(setOf(Intent.CATEGORY_HOME)))

    @Test
    fun showsTheLockedAppAndItsOptions() {
        launch().use {
            compose.onNodeWithText("Settings").assertIsDisplayed()
            compose.onNodeWithText("This app is locked").assertIsDisplayed()
            compose.onNodeWithText("Ask Guardian").assertIsDisplayed()
            compose.onNodeWithText("Paste approval link").assertIsDisplayed()
            compose.onNodeWithText("Go to home screen").assertIsDisplayed()
        }
    }

    @Test
    fun askGuardian_sharesARequestAndSavesThePendingRequest() {
        launch().use {
            compose.onNodeWithText("Ask Guardian").performClick()
            waitForText("Request sent", substring = true)
        }

        intended(
            allOf(
                hasAction(Intent.ACTION_CHOOSER),
                hasExtra(
                    equalTo(Intent.EXTRA_INTENT),
                    allOf(
                        hasAction(Intent.ACTION_SEND),
                        hasExtra(equalTo(Intent.EXTRA_TEXT), containsString("Krypt unlock request for Settings")),
                        hasExtra(equalTo(Intent.EXTRA_TEXT), containsString("krypt://request?")),
                    ),
                ),
                // Krypt's own "Open in Krypt" target is left out of the sheet.
                hasExtra(
                    equalTo(Intent.EXTRA_EXCLUDE_COMPONENTS),
                    arrayContaining(ComponentName(targetContext, LinkReceiverActivity::class.java)),
                ),
            )
        )
        val open = runBlocking { db.outstandingRequestDao().observeOpenAt(System.currentTimeMillis()).first() }
        assertEquals(1, open.size)
        assertEquals(lockedPackage, open.single().targetPackage)
    }

    @Test
    fun askGuardian_withoutPinSetup_explainsAndSharesNothing() {
        state.reset(onboardingComplete = true, pinConfigured = false)
        launch().use {
            compose.onNodeWithText("Ask Guardian").performClick()
            waitForText("Krypt isn't set up yet", substring = true)
        }
        intended(hasAction(Intent.ACTION_CHOOSER), times(0))
    }

    @Test
    fun goHome_leavesForTheLauncher() {
        launch().use { scenario ->
            compose.onNodeWithText("Go to home screen").performClick()
            waitUntilDestroyed(scenario)
        }
        intended(homeIntent)
    }

    @Test
    fun back_leavesForTheLauncher_notTheLockedApp() {
        launch().use { scenario ->
            compose.waitForIdle()
            Espresso.pressBackUnconditionally()
            waitUntilDestroyed(scenario)
        }
        intended(homeIntent)
    }

    @Test
    fun pasteApprovalLink_opensTheCopiedApproval() {
        val approval = "krypt://approve?v=1&req=12345678-1234-4abc-8def-123456789abc&data=AAAA&iat=1700000000"
        copyToClipboard("Krypt approval for Settings.\nTap the link to unlock it.\n$approval")
        launch().use {
            compose.onNodeWithText("Paste approval link").performClick()
            compose.waitForIdle()
        }
        intended(allOf(hasComponent(ApprovalTrampolineActivity::class.java.name), hasData(approval)))
    }

    @Test
    fun pasteApprovalLink_withoutAnApprovalOnTheClipboard_saysSo() {
        copyToClipboard("krypt://request?v=1&req=12345678-1234-4abc-8def-123456789abc")
        launch().use {
            compose.onNodeWithText("Paste approval link").performClick()
            waitForText("No approval link on the clipboard", substring = true)
        }
        intended(hasComponent(ApprovalTrampolineActivity::class.java.name), times(0))
    }

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun dailyRule(minutes: Int): DailyAllowance {
        val today = LocalDate.now(zone)
        return DailyAllowance(lockedPackage, minutes, today, today.plusDays(1), zone, UUID.randomUUID(), 0L)
    }

    @Test
    fun usedUpDailyTime_saysTimesUp_andStillOffersAskGuardian() {
        runBlocking {
            val today = LocalDate.now(zone).toEpochDay()
            db.dailyAllowanceDao().upsert(
                DailyAllowanceEntity(lockedPackage, 1, today, today + 1, zone.id, UUID.randomUUID().toString(), 0L)
            )
            db.dailyUsageDao().upsert(DailyUsageEntity(lockedPackage, today, 60_000))
            dailyMeter.reloadForTest()
        }
        launch().use {
            waitForText("Time's up for today")
            waitForText("Today's 1 minute is used up", substring = true)
            compose.onNodeWithText("Ask Guardian").assertIsDisplayed()
        }
    }

    @Test
    fun withAutomaticTimeOff_dailyTimeIsPaused() {
        dailyMeter.onRuleSaved(dailyRule(60))
        autoTime.on = false
        dailyMeter.reevaluate()
        launch().use {
            waitForText("Daily time is paused")
        }
    }

    @Test
    fun anEveryDayApprovalArriving_closesTheLockScreen() {
        launch().use { scenario ->
            compose.waitForIdle()
            dailyMeter.onRuleSaved(dailyRule(60))
            waitUntilDestroyed(scenario)
        }
    }

    @Test
    fun grantForTheApp_closesTheLockScreen() {
        launch().use { scenario ->
            compose.waitForIdle()
            sessionStore.recordGrant(lockedPackage, System.currentTimeMillis() + 60_000)
            waitUntilDestroyed(scenario)
        }
    }

    @Test
    fun grantForAnotherApp_leavesTheLockScreenUp() {
        launch().use { scenario ->
            compose.waitForIdle()
            sessionStore.recordGrant("com.example.other", System.currentTimeMillis() + 60_000)
            Thread.sleep(500)
            assertEquals(Lifecycle.State.RESUMED, scenario.state)
        }
    }

    private fun waitUntilDestroyed(scenario: ActivityScenario<*>) {
        val deadline = System.currentTimeMillis() + 5_000
        while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
            Thread.sleep(50)
        }
        assertEquals(Lifecycle.State.DESTROYED, scenario.state)
    }
}
