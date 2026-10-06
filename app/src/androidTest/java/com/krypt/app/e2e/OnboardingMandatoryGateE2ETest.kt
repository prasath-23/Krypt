package com.krypt.app.e2e

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.di.PermissionModule
import com.krypt.app.permission.PermissionKey
import com.krypt.app.permission.PermissionStateObserver
import com.krypt.app.permission.PermissionStatusProbe
import com.krypt.app.service.KryptDeviceAdminReceiver
import com.krypt.app.ui.main.MainActivity
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import dagger.hilt.components.SingletonComponent
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
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
@UninstallModules(PermissionModule::class)
class OnboardingMandatoryGateE2ETest {

    @Module
    @InstallIn(SingletonComponent::class)
    abstract class FakePermissionModule {
        @Binds
        abstract fun bindProbe(fake: FakeE2EPermissionStatusProbe): PermissionStatusProbe

        companion object {
            @Provides
            fun deviceAdminComponent(@ApplicationContext context: Context): ComponentName =
                ComponentName(context, KryptDeviceAdminReceiver::class.java)
        }
    }

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var state: TestState
    @Inject lateinit var permissionState: PermissionStateObserver
    @Inject lateinit var fakeProbe: FakeE2EPermissionStatusProbe

    @Before
    fun setUp() {
        hiltRule.inject()
        state.reset(onboardingComplete = false, pinConfigured = false)
    }

    private fun grant(vararg keys: PermissionKey) {
        fakeProbe.permissions = PermissionKey.values().associateWith { it in keys }
        permissionState.refresh()
    }

    private fun goToLastStep() {
        repeat(4) {
            composeRule.onNodeWithText("Next").performClick()
            composeRule.waitForIdle()
        }
    }

    @Test
    fun finishDisabled_whenNoMandatoryPermissionsGranted() {
        grant()
        ActivityScenario.launch(MainActivity::class.java).use {
            goToLastStep()
            composeRule.onNodeWithText("Finish").assertIsNotEnabled()
        }
    }

    @Test
    fun finishDisabled_withTwoOfThreeMandatoryPermissions() {
        grant(PermissionKey.ACCESSIBILITY, PermissionKey.OVERLAY, PermissionKey.NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use {
            goToLastStep()
            composeRule.onNodeWithText("Required permissions: 2 of 3").assertExists()
            composeRule.onNodeWithText("Finish").assertIsNotEnabled()
        }
    }

    @Test
    fun finishEnabled_whenAllMandatoryGranted() {
        grant(PermissionKey.ACCESSIBILITY, PermissionKey.OVERLAY, PermissionKey.BATTERY)
        ActivityScenario.launch(MainActivity::class.java).use {
            goToLastStep()
            composeRule.onNodeWithText("Finish").assertIsEnabled()
        }
    }

    @Test
    fun skipButtonVisible_onOptionalStep() {
        grant()
        ActivityScenario.launch(MainActivity::class.java).use {
            repeat(3) {
                composeRule.onNodeWithText("Next").performClick()
                composeRule.waitForIdle()
            }
            composeRule.onNodeWithText("Skip").assertExists()
        }
    }

    @Test
    fun permissionIndicator_showsGranted_whenAccessibilityGranted() {
        grant(PermissionKey.ACCESSIBILITY)
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.waitForIdle()
            composeRule.onNodeWithContentDescription("Permission granted").assertExists()
        }
    }

    @Test
    fun onboardingOffersPasteLink_forAGuardianOnlyPhone() {
        grant()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.onNodeWithText("Paste link").assertExists()
        }
    }
}
