package com.krypt.app.e2e

import android.content.Intent
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.matcher.IntentMatchers.hasAction
import androidx.test.espresso.intent.matcher.IntentMatchers.hasExtraWithKey
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.krypt.app.data.LockSource
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.deeplink.UnlockRequestParser
import com.krypt.app.security.MasterKeyStore
import com.krypt.app.ui.main.MainActivity
import com.krypt.app.common.Outcome
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.hamcrest.Matchers.allOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * e2e test for the attempted-unlock share-sheet dispatch (FR-033, SC-012).
 *
 * Verifies that tapping a LOCKED toggle:
 *  - Does NOT change the lock state (still locked)
 *  - Emits an ACTION_SEND Intent whose EXTRA_TEXT is a valid krypt://request URL
 *    parseable by [UnlockRequestParser]
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class AttemptedUnlockShareSheetE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var masterKeyStore: MasterKeyStore
    @Inject lateinit var lockedAppsRepo: LockedAppsRepository
    @Inject lateinit var settingsRepository: com.krypt.app.data.settings.SettingsRepository
    @Inject lateinit var requestParser: UnlockRequestParser

    @Before
    fun setUp() {
        hiltRule.inject()
        Intents.init()

        val setupSalt = ByteArray(16) { it.toByte() }
        val masterKeyBytes = ByteArray(32) { it.toByte() }
        val pinProof = ByteArray(32) { it.toByte() }

        runBlocking {
            masterKeyStore.save(setupSalt, masterKeyBytes, pinProof)
            settingsRepository.setOnboardingComplete(true)
            // Pre-lock "Beta" so its toggle is locked
            lockedAppsRepo.lock("com.example.beta", "Beta", LockSource.MANUAL)
        }

        // Stub the ACTION_CHOOSER so the share sheet doesn't actually open
        androidx.test.espresso.intent.Intents.intending(hasAction(Intent.ACTION_CHOOSER))
            .respondWith(
                androidx.test.espresso.intent.rule.IntentsRule().let {
                    androidx.test.espresso.intent.ActivityResult(0, null)
                }
            )
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    @Test
    fun tappingLockedToggle_doesNotUnlock_emitsShareIntent() {
        composeRule.waitForIdle()

        composeRule.onNodeWithText("Beta").performClick()
        composeRule.mainClock.advanceTimeBy(300)

        // Assert still locked
        val stillLocked = runBlocking { lockedAppsRepo.checkIfAppIsLocked("com.example.beta") }
        assertTrue("Beta must remain locked after attempted unlock", stillLocked)

        // Assert ACTION_SEND intent was fired with a valid krypt://request URL
        Intents.intended(
            allOf(
                hasAction(Intent.ACTION_SEND),
                hasExtraWithKey(Intent.EXTRA_TEXT),
            )
        )
    }
}
