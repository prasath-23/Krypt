package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.AesGcmCipher
import com.krypt.app.data.OutstandingRequest
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.data.UnlockGrant
import com.krypt.app.security.MasterKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.crypto.AEADBadTagException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Silent Subject-side consumer of Amendment 1 `krypt://approve` URLs.
 *
 * **No PIN is ever requested from the Subject** (FR-018). The Subject's
 * device simply reads the `MasterKey` persisted at setup from
 * [MasterKeyStore] and decrypts.
 *
 * Pipeline (see polaris-specs/001-krypt-app-locker/contracts/approve.md §
 * "Parsing and consumption", post-Amendment 1):
 *
 *   1. Shape-parse the URL via [ApprovalLinkParser]. Yields an envelope with
 *      the 16-byte `nonceForHkdf`, 12-byte `aesNonce`, and ciphertext+tag.
 *   2. Look up the matched [OutstandingRequest] by requestId.
 *      * null -> UnmatchedRequest
 *   3. Refuse already-consumed rows (race still caught in step 9).
 *   4. Check request TTL.
 *      * expired -> RequestExpired
 *   5. Load MasterKey from [MasterKeyStore].
 *      * absent -> NotPaired (nomenclature kept for error-enum compat)
 *   6. Derive K_req = HKDF(MasterKey, "krypt/v1/approve", req || nonceForHkdf).
 *   7. AES-GCM decrypt.
 *      * [AEADBadTagException] -> CipherDecryptFailed
 *   8. CBOR decode + cross-consistency check payload vs URL/OR.
 *   9. Atomic consume+insert grant.
 *      * 0 rows affected -> UnmatchedRequest
 *  10. Return [ApprovalOutcome].
 */
@Singleton
class ApprovalConsumer @Inject constructor(
    private val parser: ApprovalLinkParser,
    private val linkBuilder: ApprovalLinkBuilder,
    private val outstandingRepo: OutstandingRequestRepository,
    private val masterKeyStore: MasterKeyStore,
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

            // 5. MasterKey (Amendment 1 replacement for K_pair).
            val masterKey = masterKeyStore.loadMasterKey()
                ?: return@withContext Outcome.err(ApprovalError.NotPaired)

            // 6. Derive K_req + AES-GCM decrypt.
            val kReq = linkBuilder.deriveKReq(
                masterKey = masterKey,
                requestIdUtf8 = envelope.requestId.toString(),
                nonceForHkdf = envelope.nonceForHkdf,
            )
            val plaintext = try {
                AesGcmCipher.decrypt(
                    key = kReq,
                    nonce = envelope.aesNonce,
                    ciphertextAndTag = envelope.ciphertextAndTag,
                )
            } catch (_: AEADBadTagException) {
                kReq.fill(0)
                masterKey.fill(0)
                return@withContext Outcome.err(ApprovalError.CipherDecryptFailed)
            } finally {
                kReq.fill(0)
                masterKey.fill(0)
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
}
