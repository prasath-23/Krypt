package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import com.krypt.app.deeplink.DeepLinkScheme.Params
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Parses an Amendment 1 `krypt://request?...` URL into an [UnlockRequest]
 * on the Guardian device. Performs shape and range validation only; PIN
 * verification happens in the Guardian PIN screen (WP21).
 *
 * Parser is tolerant of up to [CLOCK_SKEW_SECONDS] of clock skew between
 * the Subject (who stamps `iat`) and the Guardian (who evaluates expiry).
 */
@Singleton
class UnlockRequestParser @Inject constructor() {

    fun parse(url: String, nowSeconds: Long): Outcome<UnlockRequest, RequestParseError> {
        val parsed = UrlCodec.parse(url)
            ?: return Outcome.err(RequestParseError.BadScheme)

        if (parsed.authority != DeepLinkScheme.AUTHORITY_REQUEST) {
            return Outcome.err(RequestParseError.BadScheme)
        }

        val version = parsed.params[Params.VERSION]
            ?: return Outcome.err(RequestParseError.MissingParam(Params.VERSION))
        if (version != DeepLinkScheme.PROTOCOL_VERSION) {
            return Outcome.err(RequestParseError.WrongVersion)
        }

        val reqIdString = parsed.params[Params.REQUEST_ID]
            ?: return Outcome.err(RequestParseError.MissingParam(Params.REQUEST_ID))
        val requestId = try {
            UUID.fromString(reqIdString)
        } catch (_: IllegalArgumentException) {
            return Outcome.err(RequestParseError.BadUuid)
        }
        if (requestId.version() != 4) return Outcome.err(RequestParseError.BadUuid)

        val targetPackage = parsed.params[Params.APP_PACKAGE]
            ?: return Outcome.err(RequestParseError.MissingParam(Params.APP_PACKAGE))
        if (!targetPackage.matches(UnlockRequestBuilder.PACKAGE_REGEX)) {
            return Outcome.err(RequestParseError.BadPackageName)
        }

        val saltB64 = parsed.params[Params.SALT]
            ?: return Outcome.err(RequestParseError.MissingParam(Params.SALT))
        val salt = Base64Url.tryDecode(saltB64)
            ?: return Outcome.err(RequestParseError.BadBase64)
        if (salt.size != UnlockRequest.SALT_BYTES) {
            return Outcome.err(RequestParseError.BadSaltLength)
        }

        val pinProofB64 = parsed.params[Params.PIN_PROOF]
            ?: return Outcome.err(RequestParseError.MissingParam(Params.PIN_PROOF))
        val pinProof = Base64Url.tryDecode(pinProofB64)
            ?: return Outcome.err(RequestParseError.BadBase64)
        if (pinProof.size != UnlockRequest.PIN_PROOF_BYTES) {
            return Outcome.err(RequestParseError.BadPinProofLength)
        }

        val issuedAtString = parsed.params[Params.ISSUED_AT]
            ?: return Outcome.err(RequestParseError.MissingParam(Params.ISSUED_AT))
        val issuedAt = issuedAtString.toLongOrNull()
            ?: return Outcome.err(RequestParseError.BadTimestamp)
        if (issuedAt <= 0) return Outcome.err(RequestParseError.BadTimestamp)

        val ttlString = parsed.params[Params.TTL]
            ?: return Outcome.err(RequestParseError.MissingParam(Params.TTL))
        val ttlSeconds = ttlString.toLongOrNull()
            ?: return Outcome.err(RequestParseError.BadTimestamp)
        if (ttlSeconds !in 1..UnlockRequestBuilder.MAX_TTL_SECONDS) {
            return Outcome.err(RequestParseError.BadTimestamp)
        }

        // Expiry check with symmetric clock-skew tolerance.
        val expiry = issuedAt + ttlSeconds
        if (nowSeconds > expiry + CLOCK_SKEW_SECONDS) {
            return Outcome.err(RequestParseError.Expired)
        }
        // Also reject requests dated absurdly far in the future (Subject clock ahead).
        if (issuedAt > nowSeconds + CLOCK_SKEW_SECONDS) {
            return Outcome.err(RequestParseError.BadTimestamp)
        }

        return Outcome.ok(
            UnlockRequest(
                requestId = requestId,
                targetPackage = targetPackage,
                salt = salt,
                pinProof = pinProof,
                issuedAt = issuedAt,
                ttlSeconds = ttlSeconds,
            )
        )
    }

    companion object {
        /** 60 s wall-clock drift tolerated between Subject and Guardian. */
        const val CLOCK_SKEW_SECONDS = 60L
    }
}
