package com.krypt.app.e2e

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import androidx.test.espresso.intent.Intents
import androidx.test.espresso.intent.Intents.intended
import androidx.test.espresso.intent.Intents.intending
import androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent
import androidx.test.espresso.intent.matcher.IntentMatchers.hasData
import androidx.test.espresso.intent.VerificationModes.times
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.subject.ApprovalTrampolineActivity
import com.krypt.app.ui.guardian.GuardianActivity
import com.krypt.app.ui.link.LinkReceiverActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.anyOf
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Open in Krypt" for shared or selected text: the way a Krypt link gets
 * opened from a messaging app that doesn't make `krypt://` links tappable.
 */
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class LinkReceiverE2ETest {

    @get:Rule val hilt = HiltAndroidRule(this)

    private val request = "krypt://request?v=1&req=12345678-1234-4abc-8def-123456789abc&app=com.example.app"
    private val approval = "krypt://approve?v=1&req=12345678-1234-4abc-8def-123456789abc&data=AAAA&iat=1700000000"

    @Before
    fun setUp() {
        hilt.inject()
        Intents.init()
        intending(
            anyOf(
                hasComponent(GuardianActivity::class.java.name),
                hasComponent(ApprovalTrampolineActivity::class.java.name),
            )
        ).respondWith(Instrumentation.ActivityResult(Activity.RESULT_OK, null))
    }

    @After
    fun tearDown() {
        Intents.release()
    }

    /** LinkReceiverActivity finishes at once, so start it directly and give it a moment. */
    private fun send(intent: Intent) {
        targetContext.startActivity(
            intent.setClass(targetContext, LinkReceiverActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        Thread.sleep(1_500)
    }

    @Test
    fun sharedMessageWithARequest_opensTheGuardianScreen() {
        send(
            Intent(Intent.ACTION_SEND).setType("text/plain")
                .putExtra(Intent.EXTRA_TEXT, "Krypt unlock request for Example.\nTap the link.\n$request")
        )
        intended(allOf(hasComponent(GuardianActivity::class.java.name), hasData(request)))
    }

    @Test
    fun selectedTextWithAnApproval_opensTheApprovalSilently() {
        send(
            Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, "Krypt approval for Example.\n$approval")
        )
        intended(allOf(hasComponent(ApprovalTrampolineActivity::class.java.name), hasData(approval)))
    }

    @Test
    fun textWithoutAKryptLink_opensNothing() {
        send(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "lunch at 1?"))

        intended(hasComponent(GuardianActivity::class.java.name), times(0))
        intended(hasComponent(ApprovalTrampolineActivity::class.java.name), times(0))
    }
}
