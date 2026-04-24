package com.krypt.app.e2e

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.common.Clock
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.data.OutstandingRequest
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.deeplink.ApprovalConsumer
import com.krypt.app.deeplink.ApprovalError
import com.krypt.app.deeplink.ApprovalLinkBuilder
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.deeplink.UnlockRequestBuilder
import com.krypt.app.security.MasterKeyStore
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * T121 — replay / TTL / single-use scenarios on-device (Amendment 1 FR-019,
 * FR-020). Complements the JVM-level coverage in
 * `Amendment1ReplayDefenseTest` and `Amendment1FiveMinuteTtlTest` with
 * real Room persistence.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class Amendment1ReplayTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var masterKeyStore: MasterKeyStore
    @Inject lateinit var outstandingRepo: OutstandingRequestRepository
    @Inject lateinit var consumer: ApprovalConsumer
    @Inject lateinit var requestBuilder: UnlockRequestBuilder
    @Inject lateinit var approvalBuilder: ApprovalLinkBuilder
    @Inject lateinit var kdf: KdfProvider
    @Inject lateinit var hmac: HmacProvider
    @Inject lateinit var clock: Clock

    private val pin = "1234"
    private val targetPackage = "com.example.target"

    @Before
    fun setUp() {
        hilt.inject()
    }

    private suspend fun seedAndBuildApproval(ttlSeconds: Long = 300L): Pair<String, UnlockRequest> {
        val salt = ByteArray(MasterKeyStore.SALT_BYTES) { it.toByte() }
        val masterKey = kdf.derive(
            pin.toCharArray(), salt, KdfProvider.MIN_ITERATIONS,
            MasterKeyStore.MASTER_KEY_BYTES,
        )
        val proof = hmac.sha256(
            masterKey,
            MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8),
        )
        masterKeyStore.save(salt, masterKey, proof)
        val (_, req) = requestBuilder.build(salt, proof, targetPackage, ttlSeconds = ttlSeconds)
        outstandingRepo.insert(
            OutstandingRequest(
                requestId = req.requestId,
                targetPackage = req.targetPackage,
                salt = req.salt,
                issuedAtMs = clock.nowMs(),
                expiresAtMs = clock.nowMs() + req.ttlSeconds * 1000L,
                consumed = false,
            )
        )
        val url = approvalBuilder.build(masterKey, req)
        masterKey.fill(0)
        return url to req
    }

    @Test
    fun sequentialDoubleConsume_secondFailsUnmatchedRequest() = runBlocking {
        val (url, _) = seedAndBuildApproval()
        assertTrue(consumer.consume(url) is Outcome.Ok)
        val second = consumer.consume(url)
        assertTrue("expected Err, got $second", second is Outcome.Err)
        assertSame(
            ApprovalError.UnmatchedRequest,
            (second as Outcome.Err).error,
        )
    }

    @Test
    fun parallelConsume_exactlyOneWins() = runBlocking {
        val (url, _) = seedAndBuildApproval()
        val outcomes = withContext(Dispatchers.Default) {
            (1..4).map { async { consumer.consume(url) } }.awaitAll()
        }
        val ok = outcomes.count { it is Outcome.Ok }
        val unmatched = outcomes.count {
            it is Outcome.Err && it.error === ApprovalError.UnmatchedRequest
        }
        assertEquals("exactly one Ok", 1, ok)
        assertEquals("rest UnmatchedRequest", 3, unmatched)
    }

    @Test
    fun veryShortTtlExpiry_yieldsRequestExpired() = runBlocking {
        // Build with a 1-second TTL; immediately wait past expiry then consume.
        val (url, _) = seedAndBuildApproval(ttlSeconds = 1L)
        Thread.sleep(2_000L)
        val outcome = consumer.consume(url)
        assertTrue("expected Err, got $outcome", outcome is Outcome.Err)
        assertSame(
            ApprovalError.RequestExpired,
            (outcome as Outcome.Err).error,
        )
    }
}
