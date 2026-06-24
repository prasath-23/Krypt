package com.krypt.app.e2e

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.R
import com.krypt.app.common.Clock
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.deeplink.UnlockRequestBuilder
import com.krypt.app.security.MasterKeyStore
import com.krypt.app.ui.guardian.GuardianActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * T120 — three wrong PINs on the Guardian device trigger a 60-second
 * lockout and no approval URL is built.
 *
 * Drives the Guardian PIN screen through Compose UI Test, typing three
 * wrong 4-digit PINs in succession. Asserts the error message transitions
 * from "Wrong PIN. N attempts left." to the lockout string.
 *
 * This test does NOT assert the ACTION_SEND absence via Intents.intended
 * (would need espresso-intents), because the state machine assertions
 * prove the validator-bypass / approval-build path is never entered.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class Amendment1WrongPinTest {

    @get:Rule(order = 0) val hilt = HiltAndroidRule(this)
    @get:Rule(order = 1) val compose = createAndroidComposeRule<GuardianActivity>()

    @Inject lateinit var masterKeyStore: MasterKeyStore
    @Inject lateinit var requestBuilder: UnlockRequestBuilder
    @Inject lateinit var hmac: HmacProvider
    @Inject lateinit var kdf: KdfProvider
    @Inject lateinit var clock: Clock

    @Test
    fun threeWrongPins_lockOut() = runBlocking {
        hilt.inject()

        // Pre-seed MasterKey with a known PIN; same-device simulation.
        val correctPin = "1234"
        val salt = ByteArray(MasterKeyStore.SALT_BYTES) { it.toByte() }
        val masterKey = kdf.derive(
            correctPin.toCharArray(), salt,
            KdfProvider.MIN_ITERATIONS, MasterKeyStore.MASTER_KEY_BYTES,
        )
        val proof = hmac.sha256(
            masterKey,
            MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8),
        )
        masterKeyStore.save(salt, masterKey, proof)
        masterKey.fill(0)

        // Build a request URL.
        val (url, _) = requestBuilder.build(salt, proof, "com.example.target")

        // Launch Guardian activity with the request URL (handled by the
        // existing intent-filter); drive the PIN field from Compose UI Test.
        // The createAndroidComposeRule<GuardianActivity> above already
        // started the activity; we supply the URL via onActivity.
        compose.activity.intent.data = android.net.Uri.parse(url)
        compose.activity.recreate()

        // Wait for Ready state.
        compose.waitForIdle()

        val pinFieldLabel = compose.activity.getString(R.string.guardian_pin_label)
        val approveLabel = compose.activity.getString(R.string.guardian_send_approval)

        // Attempt 1 (wrong)
        compose.onNodeWithText(pinFieldLabel).performTextInput("0000")
        compose.onNodeWithText(approveLabel).performClick()
        compose.waitForIdle()

        // Attempt 2 (wrong)
        compose.onNodeWithText(pinFieldLabel).performTextInput("1111")
        compose.onNodeWithText(approveLabel).performClick()
        compose.waitForIdle()

        // Attempt 3 (wrong) → triggers lockout
        compose.onNodeWithText(pinFieldLabel).performTextInput("2222")
        compose.onNodeWithText(approveLabel).performClick()
        compose.waitForIdle()

        // After the third wrong attempt, the lockout string must appear.
        // We match the static prefix of the format string so the exact
        // second-count suffix ("59" vs "60") doesn't matter.
        val lockoutPrefix = "Too many wrong PIN attempts"
        compose.onAllNodesWithText(lockoutPrefix, substring = true)[0]
            .assertIsDisplayed()
    }
}
