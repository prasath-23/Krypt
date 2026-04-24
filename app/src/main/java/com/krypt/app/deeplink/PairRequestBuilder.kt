package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.crypto.X25519KeyAgreement
import com.krypt.app.deeplink.DeepLinkScheme.Params
import java.security.KeyPair
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds the `krypt://pair?...` URL on the Subject device.
 *
 * The caller owns [ephPrivate] and is responsible for retaining it in
 * memory (or briefly-encrypted at-rest per WP13's risk note) until the
 * Guardian's `krypt://paired` reply is received and consumed via
 * [PairedReplyVerifier].
 */
@Singleton
class PairRequestBuilder @Inject constructor(
    private val clock: Clock,
) {

    fun build(
        subjectId: UUID,
        subjectDisplayName: String,
        ephKeyPair: KeyPair,
        ttlSeconds: Long = PairRequest.DEFAULT_TTL_SECONDS,
    ): Pair<String, PairRequest> {
        require(subjectDisplayName.length in 1..MAX_NAME_LEN) {
            "subjectDisplayName length ${subjectDisplayName.length} out of 1..$MAX_NAME_LEN"
        }
        require(ttlSeconds in 1..MAX_TTL_SECONDS) {
            "ttlSeconds ($ttlSeconds) out of 1..$MAX_TTL_SECONDS"
        }

        val ephPub = X25519KeyAgreement.derivePublicKey(ephKeyPair)
        val issuedAt = clock.nowSeconds()

        val request = PairRequest(
            subjectId = subjectId,
            subjectDisplayName = subjectDisplayName,
            subjectEphPub = ephPub,
            issuedAt = issuedAt,
            ttlSeconds = ttlSeconds,
        )

        val url = UrlCodec.build(
            authority = DeepLinkScheme.AUTHORITY_PAIR,
            params = listOf(
                Params.VERSION      to DeepLinkScheme.PROTOCOL_VERSION,
                Params.SUBJECT_ID   to subjectId.toString(),
                Params.SUBJECT_NAME to subjectDisplayName,
                Params.EPH_PUB      to Base64Url.encode(ephPub),
                Params.ISSUED_AT    to issuedAt.toString(),
                Params.TTL          to ttlSeconds.toString(),
            ),
        )
        return url to request
    }

    companion object {
        const val MAX_NAME_LEN: Int = 64
        const val MAX_TTL_SECONDS: Long = 24 * 3600L
    }
}
