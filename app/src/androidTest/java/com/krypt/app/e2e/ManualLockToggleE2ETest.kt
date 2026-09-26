package com.krypt.app.e2e

import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.data.LockSource
import com.krypt.app.data.LockedAppsRepository
import com.krypt.app.ui.main.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * e2e test for the Home Screen lock toggle (FR-032). The Home Screen is
 * behind the Guardian PIN, so both directions write straight through.
 *
 * Installed apps come from [FakeInstalledAppsRepository]: Alpha, Beta, Gamma.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class ManualLockToggleE2ETest {

    @get:Rule(order = 0) val hiltRule = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createEmptyComposeRule()

    @Inject lateinit var state: TestState
    @Inject lateinit var lockedAppsRepo: LockedAppsRepository

    @Before
    fun setUp() {
        hiltRule.inject()
        state.reset(onboardingComplete = true, pinConfigured = true)
    }

    private fun openHome() {
        compose.enterPin(TEST_PIN)
        compose.awaitText("Search apps")
        compose.awaitText("Alpha")
    }

    private fun isLocked(pkg: String) = runBlocking { lockedAppsRepo.checkIfAppIsLocked(pkg) }

    private fun waitForLockState(pkg: String, locked: Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (isLocked(pkg) != locked && System.currentTimeMillis() < deadline) Thread.sleep(50)
    }

    @Test
    fun togglingAnUnlockedApp_locksIt() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openHome()
            compose.onAllNodes(isToggleable())[0].performClick() // Alpha

            waitForLockState("com.example.alpha", locked = true)
            assertTrue("Alpha should be locked after toggle", isLocked("com.example.alpha"))
            assertFalse(isLocked("com.example.beta"))
        }
    }

    @Test
    fun togglingALockedApp_unlocksIt() {
        runBlocking { lockedAppsRepo.lock("com.example.beta", "Beta", LockSource.MANUAL) }
        ActivityScenario.launch(MainActivity::class.java).use {
            openHome()
            compose.awaitText("Locked")
            compose.onAllNodes(isToggleable())[1].performClick() // Beta

            waitForLockState("com.example.beta", locked = false)
            assertFalse("Beta should be unlocked after toggle", isLocked("com.example.beta"))
        }
    }

    @Test
    fun search_filtersTheList() {
        ActivityScenario.launch(MainActivity::class.java).use {
            openHome()
            compose.onNode(hasSetTextAction()).performTextInput("gam")
            compose.awaitText("Gamma")

            assertEquals(1, compose.onAllNodes(isToggleable()).fetchSemanticsNodes().size)
        }
    }
}
