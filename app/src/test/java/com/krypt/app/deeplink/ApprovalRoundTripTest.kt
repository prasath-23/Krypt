package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.KPairStore
import com.krypt.app.data.OutstandingRequest
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.data.UnlockGrant
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.UUID

/**
 * End-to-end round-trip through the whole deep-link stack:
 *
 *   Subject builds request -> Guardian parses + builds approval
 *   -> Subject ApprovalConsumer consumes -> UnlockGrant inserted.
 *
 * Exercises the real [UnlockRequestBuilder], [UnlockRequestParser],
 * [ApprovalLinkBuilder], [ApprovalLinkParser], and [ApprovalConsumer].
 * Persistence is an in-memory fake (`FakeOutstandingRequestRepository`),
 * `KPairStore` is an in-memory fake, RNG + Clock are deterministic.
 */
class ApprovalRoundTripTest {

    private val clock = FixedClock(nowMs = 1_700_000_000_000L)
    private val rng = DeterministicRandom(seed = byteArrayOf(0x42))

    private val requestBuilder = UnlockRequestBuilder(rng, clock)
    private val requestParser = UnlockRequestParser()
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

    private val kPair: ByteArray = ByteArray(32) { it.toByte() }

    @Test
    fun endToEndUnlockFlow() = runBlocking {
        kPairStore.save(kPair)

        // Phase A: Subject builds a request URL + stores OutstandingRequest.
        val (_, request) = requestBuilder.build("com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))

        // Phase B: Guardian (simulated) builds the approval URL.
        val approvalUrl = approvalBuilder.build(kPair, request, grantDurationMinutes = 20)
        assertTrue("approval URL length = ${approvalUrl.length}", approvalUrl.length <= 520)

        // Phase C: Subject consumes.
        val outcome = (consumer.consume(approvalUrl) as Outcome.Ok).value

        assertEquals(request.requestId, outcome.requestId)
        assertEquals("com.example.target", outcome.targetPackage)
        val expectedExpiry = clock.nowMs() + 20 * 60_000L
        assertEquals(expectedExpiry, outcome.grantExpiresAtMs)

        // Side-effect checks.
        val stored = repo.findById(request.requestId)!!
        assertTrue("OutstandingRequest must be flagged consumed after approval", stored.consumed)
        assertEquals(1, repo.grants.size)
        assertEquals(request.targetPackage, repo.grants.first().targetPackage)
    }

    @Test
    fun replayAfterSuccessIsRejected() = runBlocking {
        kPairStore.save(kPair)
        val (_, request) = requestBuilder.build("com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(kPair, request)

        assertTrue(consumer.consume(approvalUrl) is Outcome.Ok)
        // Same URL a second time -> already consumed.
        assertSame(
            ApprovalError.UnmatchedRequest,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun tamperedCiphertextYieldsCipherDecryptFailed() = runBlocking {
        kPairStore.save(kPair)
        val (_, request) = requestBuilder.build("com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(kPair, request)

        // Flip a bit inside the base64 `data=` value.
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
    fun unmatchedRequestIdYieldsUnmatchedRequest() = runBlocking {
        kPairStore.save(kPair)
        // Build an approval for a request that is NOT stored in the repo.
        val (_, request) = requestBuilder.build("com.example.target")
        val approvalUrl = approvalBuilder.build(kPair, request)

        assertSame(
            ApprovalError.UnmatchedRequest,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun unpairedYieldsNotPaired() = runBlocking {
        // Do NOT save kPair.
        val (_, request) = requestBuilder.build("com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(kPair, request)

        assertSame(
            ApprovalError.NotPaired,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun mismatchedKPairYieldsCipherDecryptFailed() = runBlocking {
        kPairStore.save(ByteArray(32) { 0x77 }) // wrong K_pair
        val (_, request) = requestBuilder.build("com.example.target")
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(kPair, request) // built with correct K_pair

        assertSame(
            ApprovalError.CipherDecryptFailed,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun expiredRequestYieldsRequestExpired() = runBlocking {
        kPairStore.save(kPair)
        val (_, request) = requestBuilder.build("com.example.target", ttlSeconds = 60)
        repo.insert(request.toOutstanding(clock.nowMs()))
        val approvalUrl = approvalBuilder.build(kPair, request)

        // Advance Subject's clock past the request expiry.
        clock.nowMs = clock.nowMs + 120_000L // 2 min
        assertSame(
            ApprovalError.RequestExpired,
            (consumer.consume(approvalUrl) as Outcome.Err).error,
        )
    }

    @Test
    fun wellFormedApprovalUrlFitsSizeBudget() {
        val (_, request) = requestBuilder.build(
            "com.example.verylongpackagename.segment",
        )
        val approvalUrl = approvalBuilder.build(kPair, request, grantDurationMinutes = 60)
        assertTrue("approval url length is ${approvalUrl.length}", approvalUrl.length <= 520)
    }

    @Test
    fun envelopeParserExposesBasicFields() {
        val (_, request) = requestBuilder.build("com.example.target")
        val approvalUrl = approvalBuilder.build(kPair, request)
        val env = (approvalParser.parse(approvalUrl) as Outcome.Ok).value
        assertEquals(request.requestId, env.requestId)
        assertEquals(12, env.nonce.size)
        assertNotNull(env.ciphertextAndTag)
        assertTrue(env.ciphertextAndTag.size >= 16)
    }

    // -------- helpers --------

    private fun UnlockRequest.toOutstanding(nowMs: Long): OutstandingRequest =
        OutstandingRequest(
            requestId = requestId,
            targetPackage = targetPackage,
            salt = salt,
            issuedAtMs = nowMs,
            expiresAtMs = nowMs + ttlSeconds * 1000,
            consumed = false,
        )
}

// -----------------------------------------------------------------------------
// Minimal in-memory fakes.
// -----------------------------------------------------------------------------

class FakeOutstandingRequestRepository : OutstandingRequestRepository {
    val requests: MutableMap<UUID, OutstandingRequest> = mutableMapOf()
    val grants: MutableList<UnlockGrant> = mutableListOf()
    private var nextGrantId: Long = 1L

    override suspend fun insert(request: OutstandingRequest) {
        requests[request.requestId] = request
    }

    override suspend fun findById(id: UUID): OutstandingRequest? = requests[id]

    override suspend fun consumeAndInsertGrant(
        requestId: UUID,
        nowMs: Long,
        grant: UnlockGrant,
    ): Long? {
        val existing = requests[requestId] ?: return null
        if (existing.consumed) return null
        if (nowMs > existing.expiresAtMs) return null
        requests[requestId] = existing.copy(consumed = true)
        val id = nextGrantId++
        grants += grant.copy(id = id)
        return id
    }
}

class FakeKPairStore : KPairStore {
    @Volatile private var value: ByteArray? = null
    override suspend fun save(kPair: ByteArray) { value = kPair.copyOf() }
    override suspend fun load(): ByteArray? = value?.copyOf()
    override suspend fun clear() { value = null }
    override fun isPaired(): Boolean = value != null
}
