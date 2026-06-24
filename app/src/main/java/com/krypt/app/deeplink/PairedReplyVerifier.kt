package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.HmacSha256
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.crypto.X25519KeyAgreement
import com.krypt.app.deeplink.DeepLinkScheme.Params
import java.security.MessageDigest
import java.security.PrivateKey
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses `krypt://paired?...` and HMAC-verifies it against the computed
 * K_pair. On success returns the [PairedReply] DTO plus the derived K_pair
 * (which the caller must persist via [com.krypt.app.crypto.KPairStore.save]).
 *
 * MAC comparison uses [MessageDigest.isEqual] (constant-time).
 */
@Singleton
class PairedReplyVerifier @Inject constructor() {

    /**
     * Verify a `krypt://paired` URL received on the Subject device.
     *
     * @param url                 the received URL
     * @param nowSeconds          Subject wall-clock seconds
     * @param myEphPrivate        Subject's persisted ephemeral private key
     *                            (the same one whose public half was sent in
     *                            `krypt://pair`). For PIN rotation, this is
     *                            still the ORIGINAL private key — Guardian
     *                            re-uses the same K_pair.
     * @param myExpectedSubjectId the Subject's own ID, echoed back in the
     *                            reply as `sub`.
     */
    fun parseAndVerify(
        url: String,
        nowSeconds: Long,
        myEphPrivate: PrivateKey,
        myExpectedSubjectId: UUID,
    ): Outcome<Pair<PairedReply, ByteArray>, PairedParseError> {
        val parsed = UrlCodec.parse(url)
            ?: return Outcome.err(PairedParseError.BadScheme)
        if (parsed.authority != DeepLinkScheme.AUTHORITY_PAIRED) {
            return Outcome.err(PairedParseError.BadScheme)
        }

        val version = parsed.params[Params.VERSION]
            ?: return Outcome.err(PairedParseError.MissingParam(Params.VERSION))
        if (version != DeepLinkScheme.PROTOCOL_VERSION) {
            return Outcome.err(PairedParseError.WrongVersion)
        }

        val subIdString = parsed.params[Params.SUBJECT_ID]
            ?: return Outcome.err(PairedParseError.MissingParam(Params.SUBJECT_ID))
        val subjectId = try {
            UUID.fromString(subIdString)
        } catch (_: IllegalArgumentException) {
            return Outcome.err(PairedParseError.BadUuid)
        }
        if (subjectId.version() != 4) return Outcome.err(PairedParseError.BadUuid)
        if (subjectId != myExpectedSubjectId) return Outcome.err(PairedParseError.WrongSubjectId)

        val guardianDisplayName = parsed.params[Params.GUARDIAN_NAME]
            ?: return Outcome.err(PairedParseError.MissingParam(Params.GUARDIAN_NAME))
        if (guardianDisplayName.isEmpty()) {
            return Outcome.err(PairedParseError.BadTimestamp)
        }

        val pubSaltB64 = parsed.params[Params.PUB_SALT]
            ?: return Outcome.err(PairedParseError.MissingParam(Params.PUB_SALT))
        val pubSalt = Base64Url.tryDecode(pubSaltB64)
            ?: return Outcome.err(PairedParseError.BadBase64)
        if (pubSalt.size != PairedReplyBuilder.PUB_SALT_BYTES) {
            return Outcome.err(PairedParseError.BadSaltLength)
        }

        val ephPubB64 = parsed.params[Params.EPH_PUB]
            ?: return Outcome.err(PairedParseError.MissingParam(Params.EPH_PUB))
        val ephPub = Base64Url.tryDecode(ephPubB64)
            ?: return Outcome.err(PairedParseError.BadBase64)
        if (ephPub.size != X25519KeyAgreement.PUBLIC_KEY_LEN) {
            return Outcome.err(PairedParseError.BadKeyLength)
        }

        val kdfIterString = parsed.params[Params.KDF_ITER]
            ?: return Outcome.err(PairedParseError.MissingParam(Params.KDF_ITER))
        val kdfIterations = kdfIterString.toIntOrNull()
            ?: return Outcome.err(PairedParseError.BadIterations)
        if (kdfIterations < KdfProvider.MIN_ITERATIONS) {
            return Outcome.err(PairedParseError.BadIterations)
        }

        val issuedAtString = parsed.params[Params.ISSUED_AT]
            ?: return Outcome.err(PairedParseError.MissingParam(Params.ISSUED_AT))
        val issuedAt = issuedAtString.toLongOrNull()
            ?: return Outcome.err(PairedParseError.BadTimestamp)
        if (issuedAt <= 0) return Outcome.err(PairedParseError.BadTimestamp)

        val ttlString = parsed.params[Params.TTL]
            ?: return Outcome.err(PairedParseError.MissingParam(Params.TTL))
        val ttlSeconds = ttlString.toLongOrNull()
            ?: return Outcome.err(PairedParseError.BadTimestamp)
        if (ttlSeconds !in 1..PairedReplyBuilder.MAX_TTL_SECONDS) {
            return Outcome.err(PairedParseError.BadTimestamp)
        }

        if (issuedAt > nowSeconds + CLOCK_SKEW_SECONDS) {
            return Outcome.err(PairedParseError.BadTimestamp)
        }
        val expiry = issuedAt + ttlSeconds
        if (nowSeconds > expiry + CLOCK_SKEW_SECONDS) {
            return Outcome.err(PairedParseError.Expired)
        }

        val receivedMacB64 = parsed.params[Params.MAC]
            ?: return Outcome.err(PairedParseError.MissingParam(Params.MAC))
        val receivedMac = Base64Url.tryDecode(receivedMacB64)
            ?: return Outcome.err(PairedParseError.BadBase64)
        if (receivedMac.size != HmacSha256.TAG_BYTES) {
            return Outcome.err(PairedParseError.BadMac)
        }

        // --- Cryptographic gate: derive K_pair, recompute MAC, compare. ---
        val kPair = try {
            X25519KeyAgreement.agree(myEphPrivate, ephPub)
        } catch (_: Exception) {
            return Outcome.err(PairedParseError.BadMac)
        }
        val canonical = canonicalMacInput(
            subjectId = subjectId,
            pubSalt = pubSalt,
            ephPub = ephPub,
            kdfIterations = kdfIterations,
            issuedAt = issuedAt,
            ttlSeconds = ttlSeconds,
        )
        val expectedMac = HmacSha256.mac(kPair, canonical)
        val macOk = MessageDigest.isEqual(expectedMac, receivedMac)
        if (!macOk) {
            kPair.fill(0)
            return Outcome.err(PairedParseError.BadMac)
        }

        val reply = PairedReply(
            subjectId = subjectId,
            guardianDisplayName = guardianDisplayName,
            guardianPubSalt = pubSalt,
            guardianEphPub = ephPub,
            kdfIterations = kdfIterations,
            issuedAt = issuedAt,
            ttlSeconds = ttlSeconds,
            mac = receivedMac,
        )
        return Outcome.ok(reply to kPair)
    }

    companion object {
        const val CLOCK_SKEW_SECONDS = 60L
    }
}
