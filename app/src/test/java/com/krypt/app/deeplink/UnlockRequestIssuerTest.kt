package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.data.settings.KryptSettings
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.guardian.GuardianPinValidator
import com.krypt.app.security.FakeMasterKeyStore
import com.krypt.app.security.MasterKeyStore
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [UnlockRequestIssuer]: every issued `krypt://request` URL must be backed by
 * an OutstandingRequest row and carry the setup PBKDF2 iteration count;
 * without either, the Guardian's approval can never be consumed.
 */
class UnlockRequestIssuerTest {

    private val clock = FixedClock(nowMs = 1_700_000_000_000L)
    private val repo = FakeOutstandingRequestRepository()
    private val masterKeyStore = FakeMasterKeyStore()
    private val requestParser = UnlockRequestParser()

    private fun issuer(setupKdfIterations: Int = KdfProvider.MIN_ITERATIONS): UnlockRequestIssuer {
        val settings = mockk<SettingsRepository> {
            every { settings } returns flowOf(
                KryptSettings(
                    kdfIterations = setupKdfIterations,
                    defaultGrantMinutes = 15,
                    onboardingComplete = true,
                    lastGuardianPairAtMs = 0L,
                )
            )
        }
        return UnlockRequestIssuer(
            masterKeyStore = masterKeyStore,
            settings = settings,
            builder = UnlockRequestBuilder(clock),
            outstandingRepo = repo,
        )
    }

    @Test
    fun issuePersistsAnOpenRequestMatchingTheUrl() = runTest {
        masterKeyStore.save(
            salt = ByteArray(MasterKeyStore.SALT_BYTES) { it.toByte() },
            masterKey = ByteArray(MasterKeyStore.MASTER_KEY_BYTES) { 7 },
            pinProof = ByteArray(MasterKeyStore.PIN_PROOF_BYTES) { 9 },
        )

        val url = (issuer(setupKdfIterations = 650_000).issue("com.example.target") as Outcome.Ok).value
        val request = (requestParser.parse(url, clock.nowSeconds()) as Outcome.Ok).value

        assertEquals(650_000, request.kdfIterations)
        val stored = repo.findById(request.requestId)
        assertNotNull("issued request must be persisted", stored)
        assertEquals("com.example.target", stored!!.targetPackage)
        assertFalse(stored.consumed)
        assertEquals(request.issuedAt * 1000, stored.issuedAtMs)
        assertEquals(request.expiresAtSeconds * 1000, stored.expiresAtMs)
        assertEquals("stale requests pruned first", 1, repo.pruneCalls)
    }

    @Test
    fun issueWithoutPinSetupFailsAndPersistsNothing() = runTest {
        val outcome = issuer().issue("com.example.target")

        assertSame(UnlockRequestIssuer.IssueError.NotConfigured, (outcome as Outcome.Err).error)
        assertTrue(repo.requests.isEmpty())
    }

    /**
     * Full round trip with real crypto at an iteration count above the floor,
     * as calibration produces on faster phones: PIN setup -> issue -> Guardian
     * validates the PIN and approves -> Subject consumes the approval.
     */
    @Test
    fun issuedRequestCanBeApprovedWithTheRightPin() = runTest {
        val kdf = KdfProvider()
        val hmac = HmacProvider()
        val setupIterations = KdfProvider.MIN_ITERATIONS + 20_000
        val salt = ByteArray(MasterKeyStore.SALT_BYTES) { (it * 5).toByte() }

        // What PinSetupViewModel persists on the Subject device.
        val masterKey = kdf.derive(
            "2468".toCharArray(), salt, setupIterations, MasterKeyStore.MASTER_KEY_BYTES,
        )
        val pinProof = hmac.sha256(
            masterKey, MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8),
        )
        masterKeyStore.save(salt, masterKey, pinProof)

        val requestUrl =
            (issuer(setupIterations).issue("com.example.target") as Outcome.Ok).value

        // Guardian device.
        val request = (requestParser.parse(requestUrl, clock.nowSeconds()) as Outcome.Ok).value
        val validation = GuardianPinValidator(kdf, hmac)
            .validate("2468".toCharArray(), request, request.kdfIterations)
        assertTrue("Guardian rejected the correct PIN: $validation", validation is Outcome.Ok)
        val approvalBuilder = ApprovalLinkBuilder(DeterministicRandom(byteArrayOf(0x42)), clock)
        val approvalUrl = approvalBuilder.build((validation as Outcome.Ok).value, request)

        // Subject device consumes the approval silently.
        val consumer = ApprovalConsumer(
            parser = ApprovalLinkParser(),
            linkBuilder = approvalBuilder,
            outstandingRepo = repo,
            masterKeyStore = masterKeyStore,
            clock = clock,
        )
        val outcome = consumer.consume(approvalUrl)

        assertTrue("expected Ok, got $outcome", outcome is Outcome.Ok)
        assertEquals("com.example.target", (outcome as Outcome.Ok).value.targetPackage)
        assertTrue(repo.findById(request.requestId)!!.consumed)
    }
}
