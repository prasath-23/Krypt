package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.crypto.KPairStore
import com.krypt.app.data.OutstandingRequest
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.data.UnlockGrant
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * End-to-end consumer of `krypt://approve` URLs on the Subject device.
 *
 * Pipeline (see polaris-specs/001-krypt-app-locker/contracts/approve.md §
 * "Parsing and consumption"):
 *
 *   1. Shape-parse the URL via [ApprovalLinkParser].
 *   2. Look up the matched [OutstandingRequest] by requestId.
 *      * null -> UnmatchedRequest
 *   3. Check the request's own TTL.
 *      * expired -> RequestExpired
 *   4. Load K_pair from [KPairStore].
 *      * absent -> NotPaired
 *   5. Derive K_req via [ApprovalLinkBuilder.deriveKReq].
 *   6. AES-GCM decrypt the ciphertext.
 *      * [AEADBadTagException] -> CipherDecryptFailed
 *   7. CBOR-decode the plaintext.
 *      * shape error -> PayloadInconsistent
 *   8. Verify payload.req == URL.requestId and payload.app == request.targetPackage.
 *      * mismatch -> PayloadInconsistent
 *   9. In a single DB transaction: mark the request consumed + insert the grant.
 *      * row-update = 0 (already-consumed race) -> UnmatchedRequest
 *  10. Return [ApprovalOutcome] with the grant's row id + expiry.
 *
 * No Android-platform types appear in this class; it's pure business logic
 * bound to the JVM, which is why tests can exercise it with fakes.
 */
@Singleton
class ApprovalConsumer @Inject constructor(
    private val parser: ApprovalLinkParser,
    private val linkBuilder: ApprovalLinkBuilder,
    private val outstandingRepo: OutstandingRequestRepository,
    private val kPairStore: KPairStore,
    private val clock: Clock,
) {

    suspend fun consume(url: String): Outcome<ApprovalOutcome, ApprovalError> =
        withContext(Dispatchers.Default) {
            // 1. Shape-parse.
            val envelope = when (val parsed = parser.parse(url)) {
                is Outcome.Ok  -> parsed.value
                is Outcome.Err -> return@withContext Outcome.err(parsed.error)
            }

            // 2. Look up matching OutstandingRequest.
            val request = outstandingRepo.findById(envelope.requestId)
                ?: return@withContext Outcome.err(ApprovalError.UnmatchedRequest)

            val nowMs = clock.nowMs()

            // 3. Refuse already-consumed requests eagerly (race still caught in step 9).
            if (request.consumed) {
                return@withContext Outcome.err(ApprovalError.UnmatchedRequest)
            }

            // 4. TTL on the request itself.
            if (nowMs > request.expiresAtMs) {
                return@withContext Outcome.err(ApprovalError.RequestExpired)
            }

            // 5. K_pair.
            val kPair = kPairStore.load()
                ?: return@withContext Outcome.err(ApprovalError.NotPaired)

            // 6. Derive K_req + AES-GCM decrypt.
            val kReq = linkBuilder.deriveKReq(kPair, request.toDomain(envelope))
            val plaintext = try {
                AesGcmCipher.decrypt(
                    key = kReq,
                    nonce = envelope.nonce,
                    ciphertextAndTag = envelope.ciphertextAndTag,
                )
            } catch (_: AEADBadTagException) {
                kReq.fill(0)
                kPair.fill(0)
                return@withContext Outcome.err(ApprovalError.CipherDecryptFailed)
            } finally {
                kReq.fill(0)
                kPair.fill(0)
            }

            // 7. CBOR decode.
            val payload = try {
                ApprovalPayloadCodec.decode(plaintext)
            } catch (_: IllegalArgumentException) {
                return@withContext Outcome.err(ApprovalError.PayloadInconsistent)
            }

            // 8. Cross-consistency: payload.req/app must match envelope/request.
            if (payload.v != DeepLinkScheme.PROTOCOL_VERSION) {
                return@withContext Outcome.err(ApprovalError.WrongVersion)
            }
            if (payload.req != envelope.requestId.toString()) {
                return@withContext Outcome.err(ApprovalError.PayloadInconsistent)
            }
            if (payload.app != request.targetPackage) {
                return@withContext Outcome.err(ApprovalError.PayloadInconsistent)
            }

            // 9. Atomic consume + insert grant.
            val grantedAtMs = nowMs
            val grantExpiresAtMs = grantedAtMs + payload.durMin * 60_000L
            val grant = UnlockGrant(
                requestId = request.requestId,
                targetPackage = request.targetPackage,
                grantedAtMs = grantedAtMs,
                expiresAtMs = grantExpiresAtMs,
            )
            val grantId = outstandingRepo.consumeAndInsertGrant(
                requestId = request.requestId,
                nowMs = nowMs,
                grant = grant,
            ) ?: return@withContext Outcome.err(ApprovalError.UnmatchedRequest)

            // 10.
            Outcome.ok(
                ApprovalOutcome(
                    requestId = request.requestId,
                    targetPackage = request.targetPackage,
                    grantedAtMs = grantedAtMs,
                    grantExpiresAtMs = grantExpiresAtMs,
                    grantId = grantId,
                )
            )
        }

    /**
     * Build the `UnlockRequest` domain object that [ApprovalLinkBuilder.deriveKReq]
     * expects from the stored OutstandingRequest shape. Only the request-id
     * and salt fields are used by the KDF; target-package / timestamps are
     * ignored in key derivation but filled in from the stored record for
     * completeness.
     */
    private fun OutstandingRequest.toDomain(envelope: ApprovalEnvelope): UnlockRequest {
        // Mirror the issuedAtMs → issuedAt seconds conversion used at build time.
        return UnlockRequest(
            requestId = requestId,
            targetPackage = targetPackage,
            salt = salt,
            issuedAt = issuedAtMs / 1000L,
            ttlSeconds = ((expiresAtMs - issuedAtMs) / 1000L).coerceAtLeast(1L),
        ).also {
            // `envelope` parameter kept to document the data flow (request-id from envelope
            // was already used for lookup; included here for future consistency audits).
            @Suppress("UNUSED_VARIABLE") val _req = envelope.requestId
        }
    }
}
