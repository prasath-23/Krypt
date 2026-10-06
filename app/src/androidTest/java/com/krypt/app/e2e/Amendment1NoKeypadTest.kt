package com.krypt.app.e2e

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import androidx.core.view.children
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.subject.ApprovalTrampolineActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * T122 — FR-018 hard gate.
 *
 * The Subject-side [ApprovalTrampolineActivity] MUST NOT expose ANY input
 * surface (no EditText, no keypad, no PIN field), whatever URL it gets:
 *   - a valid-looking approval URL (fails lookup: no pending request),
 *   - a malformed URL,
 *   - an empty URL.
 *
 * The trampoline finishes within moments, so its window is inspected from
 * lifecycle callbacks (after onCreate and on every resume) rather than after
 * launch. If a future refactor reintroduces a PIN prompt on the Subject side
 * (e.g. by reusing a legacy Compose screen), this test fails.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class Amendment1NoKeypadTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun validLookingApprovalUrl_hasNoEditText() {
        assertTrampolineShowsNoEditText(
            "krypt://approve?v=1&req=12345678-1234-4abc-8def-123456789abc" +
                "&data=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA&iat=1700000000"
        )
    }

    @Test
    fun malformedUrl_hasNoEditText() {
        assertTrampolineShowsNoEditText("krypt://approve?garbage")
    }

    @Test
    fun emptyUrl_hasNoEditText() {
        assertTrampolineShowsNoEditText("")
    }

    private fun assertTrampolineShowsNoEditText(url: String) {
        val app = targetContext.applicationContext as Application
        val inspected = CountDownLatch(1)
        val editTexts = AtomicInteger(0)
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            private fun inspect(activity: Activity) {
                if (activity !is ApprovalTrampolineActivity) return
                val root = activity.window?.decorView ?: return
                editTexts.addAndGet(collectEditTexts(root).size)
                inspected.countDown()
            }
            override fun onActivityPostCreated(activity: Activity, savedInstanceState: Bundle?) = inspect(activity)
            override fun onActivityResumed(activity: Activity) = inspect(activity)
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
            override fun onActivityStarted(activity: Activity) = Unit
            override fun onActivityPaused(activity: Activity) = Unit
            override fun onActivityStopped(activity: Activity) = Unit
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
            override fun onActivityDestroyed(activity: Activity) = Unit
        }
        app.registerActivityLifecycleCallbacks(callbacks)
        try {
            targetContext.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(url), targetContext, ApprovalTrampolineActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            assertTrue("the trampoline never started", inspected.await(10, TimeUnit.SECONDS))
            Thread.sleep(1_500) // let it try the approval and finish
            assertEquals("FR-018 violated: EditText on the Subject's approval screen", 0, editTexts.get())
        } finally {
            app.unregisterActivityLifecycleCallbacks(callbacks)
        }
    }

    private fun collectEditTexts(root: View): List<EditText> {
        val out = mutableListOf<EditText>()
        walk(root) { if (it is EditText) out += it }
        return out
    }

    private fun walk(v: View, visit: (View) -> Unit) {
        visit(v)
        if (v is ViewGroup) {
            v.children.forEach { walk(it, visit) }
        }
    }
}
