package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.common.TestClock
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.security.FakeMasterKeyStore
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** Every-day approvals on the child's phone, through real crypto. */
class DailyApprovalConsumerTest {

    private val clock = TestClock(
        wallMs = ZonedDateTime.of(2026, 3, 1, 12, 0, 0, 0, UTC).toInstant().toEpochMilli(),
        zone = UTC,
    )
    private var autoTime = true

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
        dayClock = testDayClock(clock) { autoTime },
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
    fun anEveryDayApproval_savesTheRule_andMakesNoGrant() = runTest {
        val request = issue()
        val url = approvalBuilder.build(masterKey, request, AccessChoice.EveryDay(minutes = 60, days = 7))

        val outcome = (consumer.consume(url) as Outcome.Ok).value as ApprovalOutcome.EveryDay

        val rule = repo.dailyRules.getValue(APP)
        assertEquals(outcome.allowance, rule)
        assertEquals(60, rule.minutesPerDay)
        assertEquals(TODAY, rule.firstDay)
        assertEquals("seven days, today included", TODAY.plusDays(6), rule.lastDay)
        assertEquals(UTC, rule.zone)
        assertEquals(request.requestId, rule.requestId)
        assertTrue("no one-time grant", repo.grants.isEmpty())
        assertTrue(repo.findById(request.requestId)!!.consumed)
    }

    @Test
    fun replayingIt_changesNothing() = runTest {
        val request = issue()
        val url = approvalBuilder.build(masterKey, request, AccessChoice.EveryDay(60, 7))
        consumer.consume(url)
        val saved = repo.dailyRules.getValue(APP)

        clock.advance(60_000)
        assertEquals(Outcome.err(ApprovalError.UnmatchedRequest), consumer.consume(url))
        assertEquals(saved, repo.dailyRules.getValue(APP))
    }

    @Test
    fun withAutomaticTimeOff_itIsRefused_andTheSameApprovalWorksOnceItIsOn() = runTest {
        val request = issue()
        val url = approvalBuilder.build(masterKey, request, AccessChoice.EveryDay(60, 7))

        autoTime = false
        assertEquals(Outcome.err(ApprovalError.ClockNotTrusted), consumer.consume(url))
        assertFalse("request left open", repo.findById(request.requestId)!!.consumed)
        assertTrue(repo.dailyRules.isEmpty())

        autoTime = true
        assertTrue(consumer.consume(url) is Outcome.Ok)
    }

    @Test
    fun theLongestEveryDayApproval_fitsTheUrlBudget() = runTest {
        val request = issue()
        val url = approvalBuilder.build(masterKey, request, AccessChoice.EveryDay(24 * 60, 365))
        assertTrue("approval url length is ${url.length}", url.length <= 520)
    }

    @Test
    fun aOneTimeApproval_stillMakesAGrant() = runTest {
        val request = issue()
        val url = approvalBuilder.build(masterKey, request, AccessChoice.OneTime(30))

        val outcome = (consumer.consume(url) as Outcome.Ok).value as ApprovalOutcome.OneTime

        assertEquals(30 * 60_000L, outcome.grantExpiresAtMs - outcome.grantedAtMs)
        assertTrue(repo.dailyRules.isEmpty())
    }

    private companion object {
        const val APP = "com.example.target"
        val UTC: ZoneId = ZoneId.of("UTC")
        val TODAY: LocalDate = LocalDate.of(2026, 3, 1)
    }
}
