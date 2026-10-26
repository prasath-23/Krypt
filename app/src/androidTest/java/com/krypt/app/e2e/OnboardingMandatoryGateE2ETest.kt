package com.krypt.app.e2e

import android.content.ComponentName
import android.content.Context
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
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
 * First run opens on "who is this phone for?"; protecting it leads to one
 * checklist whose Start protecting stays disabled until the two required
 * permissions are allowed (Accessibility and Overlay; the battery exemption
 * is optional since Amendment 5).
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

    /** First run: "I'm protecting this phone" opens the permission checklist. */
    private fun openChecklist() {
        composeRule.awaitText("I'm protecting this phone")
        composeRule.onNodeWithText("I'm protecting this phone").performClick()
        composeRule.awaitText("Start protecting")
    }

    @Test
    fun startProtectingDisabled_whenNoMandatoryPermissionsGranted() {
        grant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openChecklist()
            composeRule.onNodeWithText("Start protecting").assertIsNotEnabled()
        }
    }

    @Test
    fun startProtectingDisabled_withOneOfTwoMandatoryPermissions() {
        // The battery exemption and notifications are optional: they don't count.
        grant(PermissionKey.ACCESSIBILITY, PermissionKey.BATTERY, PermissionKey.NOTIFICATIONS)
        ActivityScenario.launch(MainActivity::class.java).use {
            openChecklist()
            composeRule.onNodeWithText("1 of 2 required").assertExists()
            composeRule.onNodeWithText("Start protecting").assertIsNotEnabled()
        }
    }

    @Test
    fun startProtectingEnabled_whenAllMandatoryGranted() {
        grant(PermissionKey.ACCESSIBILITY, PermissionKey.OVERLAY)
        ActivityScenario.launch(MainActivity::class.java).use {
            openChecklist()
            composeRule.onNodeWithText("2 of 2 required · ready").assertExists()
            composeRule.onNodeWithText("Start protecting").assertIsEnabled()
        }
    }

    /** There is no Skip any more: an optional item is skipped by leaving it alone. */
    @Test
    fun optionalItems_needNoSkip_andNeverBlockStartProtecting() {
        grant(PermissionKey.ACCESSIBILITY, PermissionKey.OVERLAY)
        ActivityScenario.launch(MainActivity::class.java).use {
            openChecklist()
            composeRule.onNodeWithText("OPTIONAL").assertExists()
            // Keep running in the background, Device Admin and Notifications, left alone.
            composeRule.onAllNodesWithText("Not allowed").assertCountEquals(3)
            composeRule.onNodeWithText("Start protecting").assertIsEnabled()
        }
    }

    @Test
    fun permissionIndicator_showsAllowed_whenAccessibilityGranted() {
        grant(PermissionKey.ACCESSIBILITY)
        ActivityScenario.launch(MainActivity::class.java).use {
            openChecklist()
            composeRule.onNodeWithText("Allowed").assertExists()
            // The other required item.
            composeRule.onAllNodesWithText("Not allowed yet").assertCountEquals(1)
        }
    }

    @Test
    fun firstRunOffersPasteALink_forAGuardianOnlyPhone() {
        grant()
        ActivityScenario.launch(MainActivity::class.java).use {
            composeRule.awaitText("I'm a Guardian")
            composeRule.onNodeWithText("I'm a Guardian").performClick()
            composeRule.awaitText("Ready to approve.")
            composeRule.onNodeWithText("Paste a link").assertExists()
        }
    }

    @Test
    fun checklistOffersPasteALink_too() {
        grant()
        ActivityScenario.launch(MainActivity::class.java).use {
            openChecklist()
            composeRule.onNodeWithText("Paste a link").assertExists()
        }
    }
}
