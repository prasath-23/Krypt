package com.krypt.app.e2e

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.permission.PermissionKey
import com.krypt.app.permission.PermissionStateObserver
import com.krypt.app.permission.PermissionStatusProbe
import com.krypt.app.ui.main.MainActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import com.krypt.app.di.PermissionModule
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * e2e test for the onboarding mandatory-permission gate (FR-027, SC-009).
 *
 * Uses [FakeE2EPermissionStatusProbe] so permission state is driven
 * programmatically without actual OS-level grants.
 *
 * Reviewer: Run on an emulator API 33+ without any Krypt-specific permissions
 * pre-granted. The test verifies Finish is disabled at <3/3 and enabled at 3/3.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@UninstallModules(PermissionModule::class)
class OnboardingMandatoryGateE2ETest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject lateinit var permissionState: PermissionStateObserver
    @Inject lateinit var fakeProbe: FakeE2EPermissionStatusProbe

    @dagger.Module
    @dagger.hilt.InstallIn(dagger.hilt.components.SingletonComponent::class)
    abstract class FakePermissionModule {
        @dagger.Binds
        abstract fun bindProbe(fake: FakeE2EPermissionStatusProbe): PermissionStatusProbe
    }

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun finishDisabled_whenNoMandatoryPermissionsGranted() {
        fakeProbe.permissions = PermissionKey.values().associateWith { false }
        permissionState.refresh()

        // Navigate to the last step
        repeat(4) {
            composeRule.onNodeWithText("Next").performClick()
            composeRule.mainClock.advanceTimeBy(100)
        }

        composeRule.onNodeWithText("Finish").assertIsNotEnabled()
    }

    @Test
    fun finishEnabled_whenAllMandatoryGranted() {
        fakeProbe.permissions = mapOf(
            PermissionKey.ACCESSIBILITY to true,
            PermissionKey.OVERLAY to true,
            PermissionKey.BATTERY to true,
            PermissionKey.DEVICE_ADMIN to false,
            PermissionKey.NOTIFICATIONS to false,
        )
        permissionState.refresh()

        // Navigate to last step
        repeat(4) {
            composeRule.onNodeWithText("Next").performClick()
            composeRule.mainClock.advanceTimeBy(100)
        }

        composeRule.onNodeWithText("Finish").assertIsEnabled()
    }

    @Test
    fun skipButtonVisible_onOptionalStep() {
        // Navigate to DeviceAdmin (index 3, Optional)
        repeat(3) {
            composeRule.onNodeWithText("Next").performClick()
            composeRule.mainClock.advanceTimeBy(100)
        }
        composeRule.onNodeWithText("Skip").assertExists()
    }

    @Test
    fun permissionIndicator_showsGranted_whenAccessibilityGranted() {
        fakeProbe.permissions = mapOf(PermissionKey.ACCESSIBILITY to true) +
            PermissionKey.values().filter { it != PermissionKey.ACCESSIBILITY }
                .associateWith { false }
        permissionState.refresh()
        composeRule.mainClock.advanceTimeBy(500) // absorb cross-fade

        composeRule
            .onNodeWithContentDescription("Permission granted")
            .assertExists()
    }
}
