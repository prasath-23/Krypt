package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.security.FakeMasterKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Amendment 1 replay-defense scenarios:
 *
 *   - 5-minute TTL (FR-019): a request built with `iat = now - 400 s` fails
 *     consumption with [ApprovalError.RequestExpired].
 *   - Single-use (FR-020): a valid approval tapped twice yields `Ok` then
 *     [ApprovalError.UnmatchedRequest].
 *   - Race: two parallel `consume` calls on the same approval URL yield
 *     exactly one `Ok` and one `UnmatchedRequest`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class Amendment1ReplayDefenseTest {

    private val clock = FixedClock(nowMs = 1_700_000_000_000L)
    private val rng = DeterministicRandom(seed = byteArrayOf(0x5A))

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

    private fun seedMasterKeyStore() = runBlocking {
        masterKeyStore.save(setupSalt, masterKey, pinProof)
    }

    @Test
    fun defaultTtlOf300Seconds_expiredRequestIsRejected() = runTest {
        seedMasterKeyStore()
        // Build a request at `iat = t0`, persist OutstandingRequest with the
        // same expiresAt, then advance clock past 300 + skew seconds.
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        clock.nowMs += (UnlockRequest.DEFAULT_TTL_SECONDS + UnlockRequestParser.CLOCK_SKEW_SECONDS + 30) * 1000L

        assertSame(
            ApprovalError.RequestExpired,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun sequentialDoubleConsumeYieldsUnmatchedSecondTime() = runTest {
        seedMasterKeyStore()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        assertTrue(consumer.consume(approvalUrl) is Outcome.Ok)
        assertSame(
            ApprovalError.UnmatchedRequest,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun parallelConsume_exactlyOneOkOneUnmatched() = runTest {
        seedMasterKeyStore()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        // Dispatch two consumes concurrently; the atomic consumeAndInsertGrant
        // contract + repo's @Synchronized flip must yield one winner and one
        // loser.
        val outcomes = withContext(Dispatchers.Default) {
            (1..2).map { async { consumer.consume(approvalUrl) } }.awaitAll()
        }
        val okCount = outcomes.count { it is Outcome.Ok }
        val unmatchedCount = outcomes.count {
            it is Outcome.Err && it.error === ApprovalError.UnmatchedRequest
        }
        assertEquals("one Ok expected", 1, okCount)
        assertEquals("one UnmatchedRequest expected", 1, unmatchedCount)
    }

    @Test
    fun fiveParallelConsumes_oneWinsRestFail() = runTest {
        seedMasterKeyStore()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        val outcomes = withContext(Dispatchers.Default) {
            (1..5).map { async { consumer.consume(approvalUrl) } }.awaitAll()
        }
        val okCount = outcomes.count { it is Outcome.Ok }
        val failCount = outcomes.count {
            it is Outcome.Err && it.error === ApprovalError.UnmatchedRequest
        }
        assertEquals(1, okCount)
        assertEquals(4, failCount)
    }

    @Test
    fun consumedRequestRejectsNewApprovalBuildToo() = runTest {
        seedMasterKeyStore()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))

        val firstApproval = approvalBuilder.build(masterKey, request)
        assertTrue(consumer.consume(firstApproval) is Outcome.Ok)

        // A fresh approval for the same request (but with a fresh per-approval
        // nonceForHkdf) should still fail because the OR row is flagged consumed.
        val secondApproval = approvalBuilder.build(masterKey, request)
        assertSame(
            ApprovalError.UnmatchedRequest,
            (consumer.consume(secondApproval) as Outcome.Err).error,
        )
    }
}
