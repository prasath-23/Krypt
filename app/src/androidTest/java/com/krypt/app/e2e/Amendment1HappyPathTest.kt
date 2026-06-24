package com.krypt.app.e2e

import android.content.Intent
import android.net.Uri
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.common.Clock
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.crypto.SecureRandomSource
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.data.OutstandingRequest
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.deeplink.ApprovalLinkBuilder
import com.krypt.app.deeplink.UnlockRequestBuilder
import com.krypt.app.security.MasterKeyStore
import com.krypt.app.subject.ApprovalTrampolineActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import javax.inject.Inject

/**
 * T119 — Full Amendment 1 silent-unlock happy path on a single device.
 *
 * Scenario (in-process):
 *   1. Seed [MasterKeyStore] with a deterministic {salt, masterKey,
 *      pinProof}, mirroring what [com.krypt.app.ui.setup.PinSetupViewModel]
 *      would have produced at setup.
 *   2. Build a krypt://request URL via [UnlockRequestBuilder], persist the
 *      [OutstandingRequest] row to the real Room database.
 *   3. Build a krypt://approve URL via [ApprovalLinkBuilder] with the same
 *      masterKey. (Skips the Guardian-device UI — that path is covered by
 *      GuardianPinViewModelTest.)
 *   4. Launch [ApprovalTrampolineActivity] with the approval URL.
 *   5. Assert: [LockerSessionStore] shows the package unlocked, the
 *      OutstandingRequest row is consumed, and the grant exists.
 *
 * We skip the Compose UI hop on the Guardian side because its logic is
 * fully covered in JVM unit tests; the goal here is the real device-side
 * wiring end-to-end.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class Amendment1HappyPathTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var masterKeyStore: MasterKeyStore
    @Inject lateinit var outstandingRepo: OutstandingRequestRepository
    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var requestBuilder: UnlockRequestBuilder
    @Inject lateinit var approvalBuilder: ApprovalLinkBuilder
    @Inject lateinit var hmac: HmacProvider
    @Inject lateinit var kdf: KdfProvider
    @Inject lateinit var rng: SecureRandomSource
    @Inject lateinit var clock: Clock

    private val targetPackage = "com.example.target"
    private val setupPin = "1234"

    @Before
    fun setUp() {
        hilt.inject()
    }

    @Test
    fun endToEnd_seedSetupThenConsume_yieldsActiveGrant() = runBlocking {
        // 1. Seed setup equivalent of PinSetupViewModel.save("1234").
        val salt = ByteArray(MasterKeyStore.SALT_BYTES) { it.toByte() }
        val masterKey = kdf.derive(
            setupPin.toCharArray(),
            salt,
            KdfProvider.MIN_ITERATIONS,
            MasterKeyStore.MASTER_KEY_BYTES,
        )
        val pinProof = hmac.sha256(
            masterKey,
            MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8),
        )
        masterKeyStore.save(salt, masterKey, pinProof)

        // 2. Subject builds request + persists OutstandingRequest row.
        val (_, request) = requestBuilder.build(salt, pinProof, targetPackage)
        outstandingRepo.insert(
            OutstandingRequest(
                requestId = request.requestId,
                targetPackage = request.targetPackage,
                salt = request.salt,
                issuedAtMs = clock.nowMs(),
                expiresAtMs = clock.nowMs() + request.ttlSeconds * 1000L,
                consumed = false,
            )
        )

        // 3. Guardian (simulated) builds approval with same MasterKey.
        val approvalUrl = approvalBuilder.build(masterKey, request, grantDurationMinutes = 20)
        masterKey.fill(0)

        // 4. Subject taps approval → ApprovalTrampolineActivity.
        val intent = Intent(
            Intent.ACTION_VIEW,
            Uri.parse(approvalUrl),
            androidx.test.platform.app.InstrumentationRegistry
                .getInstrumentation().targetContext,
            ApprovalTrampolineActivity::class.java,
        )
        ActivityScenario.launch<ApprovalTrampolineActivity>(intent).use { scenario ->
            // Trampoline finishes itself after play(); wait for terminal state.
            scenario.onActivity {
                // No-op; existence of this Activity is what we care about.
            }
            // Give lifecycleScope time to consume + recordGrant.
            Thread.sleep(2000L)
        }

        // 5. Assertions.
        val storedRequest = outstandingRepo.findById(request.requestId)!!
        assertTrue("Request must be consumed", storedRequest.consumed)
        assertTrue(
            "SessionStore must report ${targetPackage} as unlocked",
            sessionStore.isUnlockedNow(targetPackage),
        )
    }
}
