package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.security.FakeMasterKeyStore
import com.krypt.app.security.MasterKeyStore
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end Amendment 1 flow:
 *
 *   Subject builds request URL from MasterKeyStore
 *   -> Guardian (same-process) recovers MasterKey, builds approval URL
 *   -> Subject ApprovalConsumer decrypts silently via MasterKeyStore
 *   -> OutstandingRequest.consumed flipped, UnlockGrant inserted.
 */
class Amendment1ApprovalRoundTripTest {

    private val clock = FixedClock(nowMs = 1_700_000_000_000L)
    private val rng = DeterministicRandom(seed = byteArrayOf(0x42))

    private val requestBuilder = UnlockRequestBuilder(clock)
    private val requestParser = UnlockRequestParser()
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
    private val masterKey = ByteArray(AesGcmCipher.KEY_BYTES) { (it * 7).toByte() }
    private val pinProof = ByteArray(UnlockRequest.PIN_PROOF_BYTES) { (it * 11).toByte() }

    private fun seedMasterKeyStore() = runBlocking {
        masterKeyStore.save(setupSalt, masterKey, pinProof)
    }

    @Test
    fun endToEndUnlockFlow() = runTest {
        seedMasterKeyStore()

        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))

        val approvalUrl = approvalBuilder.build(masterKey, request, grantDurationMinutes = 20)
        assertTrue("approval URL length = ${approvalUrl.length}", approvalUrl.length <= 520)

        val outcome = (consumer.consume(approvalUrl) as Outcome.Ok).value

        assertEquals(request.requestId, outcome.requestId)
        assertEquals("com.example.target", outcome.targetPackage)
        val expectedExpiry = clock.nowMs() + 20 * 60_000L
        assertEquals(expectedExpiry, outcome.grantExpiresAtMs)

        val stored = repo.findById(request.requestId)!!
        assertTrue(stored.consumed)
        assertEquals(1, repo.grants.size)
        assertEquals(request.targetPackage, repo.grants.first().targetPackage)
    }

    @Test
    fun tamperedCiphertextYieldsCipherDecryptFailed() = runTest {
        seedMasterKeyStore()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        val tampered = approvalUrl.replace(Regex("data=[^&]+")) { match ->
            val b64 = match.value.substringAfter("data=")
            val bytes = Base64Url.decode(b64)
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
            "data=${Base64Url.encode(bytes)}"
        }

        assertSame(
            ApprovalError.CipherDecryptFailed,
            (consumer.consume(tampered) as Outcome.Err).error,
        )
    }

    @Test
    fun tamperedNonceForHkdfYieldsCipherDecryptFailed() = runTest {
        seedMasterKeyStore()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        // Flip a bit INSIDE the nonceForHkdf (first 16 bytes of the data blob).
        val tampered = approvalUrl.replace(Regex("data=[^&]+")) { match ->
            val b64 = match.value.substringAfter("data=")
            val bytes = Base64Url.decode(b64)
            bytes[0] = (bytes[0].toInt() xor 0x01).toByte()
            "data=${Base64Url.encode(bytes)}"
        }
        // Different nonceForHkdf -> different kReq -> decrypt fails.
        assertSame(
            ApprovalError.CipherDecryptFailed,
            (consumer.consume(tampered) as Outcome.Err).error,
        )
    }

    @Test
    fun unmatchedRequestIdYieldsUnmatchedRequest() = runTest {
        seedMasterKeyStore()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        // Do NOT insert into repo.
        val approvalUrl = approvalBuilder.build(masterKey, request)

        assertSame(
            ApprovalError.UnmatchedRequest,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun unpairedYieldsNotPaired() = runTest {
        // No seedMasterKeyStore call.
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        assertSame(
            ApprovalError.NotPaired,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun mismatchedMasterKeyYieldsCipherDecryptFailed() = runTest {
        // Store a different MasterKey than the one used to build.
        masterKeyStore.save(
            setupSalt,
            ByteArray(AesGcmCipher.KEY_BYTES) { 0x77 },
            pinProof,
        )
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request) // correct key

        assertSame(
            ApprovalError.CipherDecryptFailed,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun expiredRequestYieldsRequestExpired() = runTest {
        seedMasterKeyStore()
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target", ttlSeconds = 60)
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(masterKey, request)

        clock.nowMs += 120_000L
        assertSame(
            ApprovalError.RequestExpired,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun envelopeParserExposesSplitNonces() {
        val (_, request) = requestBuilder.build(setupSalt, pinProof, "com.example.target")
        val approvalUrl = approvalBuilder.build(masterKey, request)
        val env = (approvalParser.parse(approvalUrl) as Outcome.Ok).value
        assertEquals(request.requestId, env.requestId)
        assertEquals(ApprovalLinkBuilder.NONCE_FOR_HKDF_BYTES, env.nonceForHkdf.size)
        assertEquals(AesGcmCipher.NONCE_BYTES, env.aesNonce.size)
        assertNotNull(env.ciphertextAndTag)
        assertTrue(env.ciphertextAndTag.size >= 16)
    }

    @Test
    fun approvalUrlFitsSizeBudget() {
        val (_, request) = requestBuilder.build(
            setupSalt, pinProof, "com.example.verylongpackagename.segment",
        )
        val approvalUrl = approvalBuilder.build(masterKey, request, grantDurationMinutes = 60)
        assertTrue("approval url length is ${approvalUrl.length}", approvalUrl.length <= 520)
    }

    @Test
    fun multipleConcurrentRequestsAllRoundTripCleanly() = runTest {
        seedMasterKeyStore()
        // 50 distinct outstanding requests. Each gets its own approval.
        // All share the same MasterKey (setup happened once). Exercises
        // HKDF info-material uniqueness via the 16-byte per-approval
        // nonceForHkdf prepended to each data blob.
        val requests = (0 until 50).map { i ->
            val (_, req) = requestBuilder.build(
                setupSalt, pinProof, "com.example.app$i",
            )
            repo.insert(req.toOutstanding(clock.nowMs()))
            req
        }

        for (req in requests) {
            val url = approvalBuilder.build(masterKey, req)
            val outcome = consumer.consume(url)
            assertTrue(
                "requestId ${req.requestId}: expected Ok, got $outcome",
                outcome is Outcome.Ok,
            )
        }

        assertEquals(50, repo.grants.size)
    }
}
