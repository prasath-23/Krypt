package com.krypt.app.e2e

import android.content.Intent
import android.net.Uri
import android.widget.EditText
import androidx.core.view.children
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.subject.ApprovalTrampolineActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import android.view.View
import android.view.ViewGroup

/**
 * T122 — FR-018 hard gate.
 *
 * The Subject-side [ApprovalTrampolineActivity] MUST NOT expose ANY input
 * surface (no EditText, no keypad, no PIN field). Two sub-scenarios:
 *   - Valid-looking approval URL (that will fail lookup because the fake
 *     MasterKeyStore is empty → toast + finish).
 *   - Invalid/malformed URL.
 *
 * Both scenarios are asserted: at no point does an [EditText] appear in
 * the view hierarchy. If a future refactor accidentally reintroduces a
 * PIN prompt on the Subject side (e.g. by reusing a legacy Compose
 * screen), this test fails.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class Amendment1NoKeypadTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Test
    fun validLookingApprovalUrl_hasNoEditText() {
        val url = "krypt://approve?v=1&req=" +
            "12345678-1234-4abc-8def-123456789abc" +
            "&data=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA" +
            "&iat=1700000000"
        assertActivityHasNoEditText(url)
    }

    @Test
    fun malformedUrl_hasNoEditText() {
        assertActivityHasNoEditText("krypt://approve?garbage")
    }

    @Test
    fun emptyUrl_hasNoEditText() {
        assertActivityHasNoEditText("")
    }

    private fun assertActivityHasNoEditText(url: String) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(url),
            androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext,
            ApprovalTrampolineActivity::class.java,
        )
        ActivityScenario.launch<ApprovalTrampolineActivity>(intent).use { scenario ->
            scenario.onActivity { activity ->
                val root = activity.window?.decorView as? ViewGroup
                    ?: return@onActivity
                val edits = collectEditTexts(root)
                assertTrue(
                    "FR-018 violated: ApprovalTrampolineActivity exposes " +
                        "${edits.size} EditText instance(s)",
                    edits.isEmpty(),
                )
            }
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
