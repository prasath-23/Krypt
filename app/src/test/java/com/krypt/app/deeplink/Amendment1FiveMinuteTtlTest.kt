package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.security.FakeMasterKeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Explicit FR-019 assertions: 5-minute default request TTL.
 *
 * Complements [Amendment1ReplayDefenseTest] which tests the
 * already-expired path; this test nails down the exact 300-second
 * default, the pass-through window, and the exact expiry cliff.
 */
class Amendment1FiveMinuteTtlTest {

    private val clock = FixedClock(nowMs = 1_700_000_000_000L)
    private val rng = DeterministicRandom(seed = byteArrayOf(0x19))

    private val requestBuilder = UnlockRequestBuilder(clock)
    private val approvalBuilder = ApprovalLinkBuilder(rng, clock)
    private val approvalParser = ApprovalLinkParser()

    private val repo = FakeOutstandingRequestRepository()
    private val masterKeyStore = FakeMasterKeyStore()
    private val consumer = ApprovalConsumer(
        parser = approvalParser,
        linkBuilder = approvalBuilder,
        outstandingRepo = repo,
        masterKeyStore = masterKeyStore,
        clock = clock,
    )

    private val setupSalt = ByteArray(UnlockRequest.SALT_BYTES) { it.toByte() }
    private val masterKey = ByteArray(AesGcmCipher.KEY_BYTES) { (it * 5).toByte() }
    private val pinProof = ByteArray(UnlockRequest.PIN_PROOF_BYTES) { (it * 7).toByte() }

    private fun seed() = runBlocking {
        masterKeyStore.save(setupSalt, masterKey, pinProof)
    }

    @Test
    fun defaultTtlSecondsConstantIs300() {
        // FR-019: Amendment 1 tightens the request validity window from
        // 1800 s (WP03 original) to 300 s. Pinned here as a regression gate.
        assertEquals(300L, UnlockRequest.DEFAULT_TTL_SECONDS)
    }

    @Test
    fun requestBuiltRightNowConsumesSuccessfully() = runTest {
        seed()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        // Not advanced: should succeed.
        assertTrue(consumer.consume(approvalUrl) is Outcome.Ok)
    }

    @Test
    fun atExactly299SecondsStillConsumesFine() = runTest {
        seed()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        clock.nowMs += 299_000L
        assertTrue(consumer.consume(approvalUrl) is Outcome.Ok)
    }

    @Test
    fun atExactly301SecondsRejectsWithExpired() = runTest {
        seed()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        // ApprovalConsumer reads request.expiresAtMs = issuedAt + 300_000
        // (no skew on this layer - parser skew is for the Guardian-side
        // URL parse). So 301 s MUST be Expired.
        clock.nowMs += 301_000L
        assertSame(
            ApprovalError.RequestExpired,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }
}
