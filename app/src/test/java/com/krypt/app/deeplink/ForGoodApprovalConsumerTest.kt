package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.common.TestClock
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.security.FakeMasterKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** For-good approvals on the child's phone, through real crypto: the app's lock is removed. */
class ForGoodApprovalConsumerTest {

    private val clock = TestClock()
    private val requestBuilder = UnlockRequestBuilder(clock)
    private val approvalBuilder = ApprovalLinkBuilder(DeterministicRandom(byteArrayOf(0x42)), clock)
    private val repo = FakeOutstandingRequestRepository()
    private val masterKeyStore = FakeMasterKeyStore()
    private val consumer = ApprovalConsumer(
        parser = ApprovalLinkParser(),
        linkBuilder = approvalBuilder,
        outstandingRepo = repo,
        masterKeyStore = masterKeyStore,
        clock = clock,
        dayClock = testDayClock(clock) { true },
    )

    private val setupSalt = ByteArray(UnlockRequest.SALT_BYTES) { it.toByte() }
    private val masterKey = ByteArray(AesGcmCipher.KEY_BYTES) { (it * 7).toByte() }
    private val pinProof = ByteArray(UnlockRequest.PIN_PROOF_BYTES) { (it * 11).toByte() }

    private suspend fun issue(): UnlockRequest {
        masterKeyStore.save(setupSalt, masterKey, pinProof)
        val (_, request) = requestBuilder.build(setupSalt, pinProof, APP)
        repo.insert(request.toOutstanding(clock.nowMs()))
        return request
    }

    @Test
    fun aForGoodApproval_removesTheLock_andSavesNothingElse() = runTest {
        val request = issue()
        val url = approvalBuilder.build(masterKey, request, AccessChoice.ForGood)

        val outcome = (consumer.consume(url) as Outcome.Ok).value

        assertEquals(ApprovalOutcome.ForGood(request.requestId, APP), outcome)
        assertEquals(listOf(APP), repo.removedLocks)
        assertTrue("no one-time grant", repo.grants.isEmpty())
        assertTrue("no every-day rule", repo.dailyRules.isEmpty())
        assertTrue(repo.findById(request.requestId)!!.consumed)
    }

    @Test
    fun replayingIt_changesNothing() = runTest {
        val request = issue()
        val url = approvalBuilder.build(masterKey, request, AccessChoice.ForGood)
        consumer.consume(url)

        clock.advance(60_000)
        assertEquals(Outcome.err(ApprovalError.UnmatchedRequest), consumer.consume(url))
        assertEquals(listOf(APP), repo.removedLocks)
    }

    @Test
    fun afterItsRequestExpired_itIsRefused_andTheLockStays() = runTest {
        val request = issue()
        val url = approvalBuilder.build(masterKey, request, AccessChoice.ForGood)
        clock.advance((request.ttlSeconds + 1) * 1_000)

        assertEquals(Outcome.err(ApprovalError.RequestExpired), consumer.consume(url))
        assertTrue(repo.removedLocks.isEmpty())
    }

    private companion object {
        const val APP = "com.example.target"
    }
}
