package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.X25519KeyAgreement
import com.krypt.app.deeplink.DeepLinkScheme.Params
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses `krypt://pair?...` URLs on the Guardian device.
 * Tolerates up to [CLOCK_SKEW_SECONDS] of wall-clock drift.
 */
@Singleton
class PairRequestParser @Inject constructor() {

    fun parse(url: String, nowSeconds: Long): Outcome<PairRequest, PairParseError> {
        val parsed = UrlCodec.parse(url)
            ?: return Outcome.err(PairParseError.BadScheme)

        if (parsed.authority != DeepLinkScheme.AUTHORITY_PAIR) {
            return Outcome.err(PairParseError.BadScheme)
        }

        val version = parsed.params[Params.VERSION]
            ?: return Outcome.err(PairParseError.MissingParam(Params.VERSION))
        if (version != DeepLinkScheme.PROTOCOL_VERSION) {
            return Outcome.err(PairParseError.WrongVersion)
        }

        val subIdString = parsed.params[Params.SUBJECT_ID]
            ?: return Outcome.err(PairParseError.MissingParam(Params.SUBJECT_ID))
        val subjectId = try {
            UUID.fromString(subIdString)
        } catch (_: IllegalArgumentException) {
            return Outcome.err(PairParseError.BadUuid)
        }
        if (subjectId.version() != 4) return Outcome.err(PairParseError.BadUuid)

        val subjectDisplayName = parsed.params[Params.SUBJECT_NAME]
            ?: return Outcome.err(PairParseError.MissingParam(Params.SUBJECT_NAME))
        if (subjectDisplayName.isEmpty() || subjectDisplayName.length > PairRequestBuilder.MAX_NAME_LEN) {
            return Outcome.err(PairParseError.BadTimestamp)
        }

        val ephPubB64 = parsed.params[Params.EPH_PUB]
            ?: return Outcome.err(PairParseError.MissingParam(Params.EPH_PUB))
        val ephPub = Base64Url.tryDecode(ephPubB64)
            ?: return Outcome.err(PairParseError.BadBase64)
        if (ephPub.size != X25519KeyAgreement.PUBLIC_KEY_LEN) {
            return Outcome.err(PairParseError.BadKeyLength)
        }

        val issuedAtString = parsed.params[Params.ISSUED_AT]
            ?: return Outcome.err(PairParseError.MissingParam(Params.ISSUED_AT))
        val issuedAt = issuedAtString.toLongOrNull() ?: return Outcome.err(PairParseError.BadTimestamp)
        if (issuedAt <= 0) return Outcome.err(PairParseError.BadTimestamp)

        val ttlString = parsed.params[Params.TTL]
            ?: return Outcome.err(PairParseError.MissingParam(Params.TTL))
        val ttlSeconds = ttlString.toLongOrNull() ?: return Outcome.err(PairParseError.BadTimestamp)
        if (ttlSeconds !in 1..PairRequestBuilder.MAX_TTL_SECONDS) {
            return Outcome.err(PairParseError.BadTimestamp)
        }

        if (issuedAt > nowSeconds + CLOCK_SKEW_SECONDS) {
            return Outcome.err(PairParseError.BadTimestamp)
        }
        val expiry = issuedAt + ttlSeconds
        if (nowSeconds > expiry + CLOCK_SKEW_SECONDS) {
            return Outcome.err(PairParseError.Expired)
        }

        return Outcome.ok(
            PairRequest(
                subjectId = subjectId,
                subjectDisplayName = subjectDisplayName,
                subjectEphPub = ephPub,
                issuedAt = issuedAt,
                ttlSeconds = ttlSeconds,
            )
        )
    }

    companion object {
        const val CLOCK_SKEW_SECONDS = 60L
    }
}
