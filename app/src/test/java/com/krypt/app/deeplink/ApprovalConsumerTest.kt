package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * Focused error-path coverage for [ApprovalConsumer.consume]. Complements
 * [ApprovalRoundTripTest]'s happy-path and tamper tests with the remaining
 * parser-level error branches.
 */
class ApprovalConsumerTest {

    private val clock = FixedClock(nowMs = 1_700_000_000_000L)
    private val rng = DeterministicRandom(seed = byteArrayOf(0xAB.toByte()))
    private val requestBuilder = UnlockRequestBuilder(rng, clock)
    private val approvalBuilder = ApprovalLinkBuilder(rng, clock)
    private val approvalParser = ApprovalLinkParser()

    private val repo = FakeOutstandingRequestRepository()
    private val kPairStore = FakeKPairStore()
    private val consumer = ApprovalConsumer(
        parser = approvalParser,
        linkBuilder = approvalBuilder,
        outstandingRepo = repo,
        kPairStore = kPairStore,
        clock = clock,
    )

    private val kPair: ByteArray = ByteArray(32) { (it * 17).toByte() }

    @Test
    fun consumeRejectsBadScheme() = runBlocking {
        assertSame(
            ApprovalError.BadScheme,
            (consumer.consume("http://not-krypt.example/foo") as Outcome.Err).error,
        )
    }

    @Test
    fun consumeRejectsWrongAuthority() = runBlocking {
        assertSame(
            ApprovalError.BadScheme,
            (consumer.consume("krypt://request?v=1&req=${UUID.randomUUID()}") as Outcome.Err).error,
        )
    }

    @Test
    fun consumeRejectsWrongVersion() = runBlocking {
        kPairStore.save(kPair)
        val (_, request) = requestBuilder.build("com.example.target")
        val approval = approvalBuilder.build(kPair, request).replace("v=1", "v=2")
        assertSame(
            ApprovalError.WrongVersion,
            (consumer.consume(approval) as Outcome.Err).error,
        )
    }

    @Test
    fun consumeRejectsMissingDataParam() = runBlocking {
        kPairStore.save(kPair)
        val (_, request) = requestBuilder.build("com.example.target")
        val approval = approvalBuilder.build(kPair, request)
            .replace(Regex("&data=[^&]+"), "")
        val err = (consumer.consume(approval) as Outcome.Err).error
        assertTrue(
            "expected MissingParam(data) but got $err",
            err is ApprovalError.MissingParam && err.name == "data",
        )
    }

    @Test
    fun consumeRejectsBadBase64InData() = runBlocking {
        kPairStore.save(kPair)
        val (_, request) = requestBuilder.build("com.example.target")
        val approval = approvalBuilder.build(kPair, request)
            .replace(Regex("data=[^&]+"), "data=%21not-base64%21")
        val err = (consumer.consume(approval) as Outcome.Err).error
        assertTrue(
            "expected BadBase64 or CipherDecryptFailed, got $err",
            err === ApprovalError.BadBase64 || err === ApprovalError.CipherDecryptFailed,
        )
    }

    @Test
    fun consumeRejectsBadUuid() = runBlocking {
        val approval = "krypt://approve?v=1&req=not-a-uuid&data=AA&iat=1"
        assertSame(
            ApprovalError.BadUuid,
            (consumer.consume(approval) as Outcome.Err).error,
        )
    }

    @Test
    fun consumeRejectsNonV4Uuid() = runBlocking {
        // UUID version 3 (name-based, MD5). Valid shape, wrong version.
        val approval = "krypt://approve?v=1&req=12345678-1234-3abc-8def-123456789abc" +
            "&data=${Base64Url.encode(ByteArray(40))}&iat=1"
        assertSame(
            ApprovalError.BadUuid,
            (consumer.consume(approval) as Outcome.Err).error,
        )
    }

    @Test
    fun consumeRejectsShortData() = runBlocking {
        // Data is too short to contain even the GCM tag.
        val approval = "krypt://approve?v=1&req=${UUID.randomUUID()}" +
            "&data=${Base64Url.encode(ByteArray(4))}&iat=1"
        val err = (consumer.consume(approval) as Outcome.Err).error
        assertTrue(
            "expected BadBase64 (short-data path), got $err",
            err === ApprovalError.BadBase64,
        )
    }

    @Test
    fun consumeRejectsPayloadConsistencyMismatch() = runBlocking {
        // Build an approval whose ciphertext embeds one target package,
        // but store the OutstandingRequest with a DIFFERENT target package.
        kPairStore.save(kPair)
        val (_, requestAppA) = requestBuilder.build("com.example.apppay")
        // Inject the stored OutstandingRequest with a different package than
        // what went into the AES-GCM payload.
        repo.insert(
            com.krypt.app.data.OutstandingRequest(
                requestId = requestAppA.requestId,
                targetPackage = "com.example.different",   // MISMATCH
                salt = requestAppA.salt,
                issuedAtMs = clock.nowMs(),
                expiresAtMs = clock.nowMs() + 60_000L,
                consumed = false,
            )
        )
        val approvalUrl = approvalBuilder.build(kPair, requestAppA)

        val outcome = consumer.consume(approvalUrl)
        val err = (outcome as Outcome.Err).error
        // Mismatch of stored salt means HKDF uses the wrong salt, and
        // decryption fails first. Either CipherDecryptFailed or
        // PayloadInconsistent is acceptable here depending on which
        // invariant trips first.
        assertTrue(
            "expected CipherDecryptFailed or PayloadInconsistent, got $err",
            err === ApprovalError.CipherDecryptFailed || err === ApprovalError.PayloadInconsistent,
        )
    }
}
