package com.krypt.app.e2e

import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.security.MasterKeyStore
import com.krypt.app.ui.main.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * e2e test for the manual lock toggle (FR-032, SC-011).
 *
 * Preconditions:
 *   - PIN configured via [InMemoryMasterKeyStore]
 *   - Onboarding marked complete via [SettingsRepository]
 *   - Installed apps provided by [FakeInstalledAppsRepository] (3 apps)
 *   - [LockedAppsRepository] starts empty
 *
 * Verifies that tapping an unlocked toggle persists the lock state.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ManualLockToggleE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var masterKeyStore: MasterKeyStore
    @Inject lateinit var lockedAppsRepo: LockedAppsRepository
    @Inject lateinit var settingsRepository: com.krypt.app.data.settings.SettingsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        runBlocking {
            // Configure PIN so MainRoute routes to HomeScreen
            masterKeyStore.save(
                salt = ByteArray(16) { it.toByte() },
                masterKey = ByteArray(32) { it.toByte() },
                pinProof = ByteArray(32) { it.toByte() },
            )
            // Mark onboarding complete so MainRoute doesn't show OnboardingScreen
            settingsRepository.setOnboardingComplete(true)
        }
    }

    @Test
    fun toggleUnlockedApp_persistsLock() {
        // Wait for HomeScreen to load
        composeRule.waitForIdle()

        // Tap the toggle on "Alpha" (first unlocked row)
        composeRule
            .onNodeWithText("Alpha")
            .performClick()
        composeRule.mainClock.advanceTimeBy(200)

        val isLocked = runBlocking { lockedAppsRepo.checkIfAppIsLocked("com.example.alpha") }
        assertTrue("Alpha should be locked after toggle", isLocked)
    }
}
