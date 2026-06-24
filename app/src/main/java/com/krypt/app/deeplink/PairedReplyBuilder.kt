package com.krypt.app.deeplink

import com.krypt.app.common.Clock
import com.krypt.app.crypto.HmacSha256
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.crypto.X25519KeyAgreement
import com.krypt.app.deeplink.DeepLinkScheme.Params
import java.security.KeyPair
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Builds `krypt://paired?...` URLs on the Guardian device.
 *
 * Two flavours:
 *  - [buildWithFreshAgreement] — first-time pairing. Computes K_pair from the
 *    Subject's ephemeral public key plus the Guardian's own ephemeral private
 *    key, then signs the canonical body under K_pair.
 *  - [buildWithExistingKPair] — PIN rotation. Re-uses the persisted K_pair
 *    (and the Guardian's previous ephPub, since Subject persists it too) but
 *    rotates pubSalt + kdfIterations and re-signs.
 */
@Singleton
class PairedReplyBuilder @Inject constructor(
    private val clock: Clock,
) {

    /**
     * First-time-pairing variant. Returns `(url, kPair)`; caller MUST persist
     * `kPair` via [com.krypt.app.crypto.KPairStore.save] immediately — once
     * this method returns, the Guardian's ephPrivate is no longer retrievable
     * and the only proof of K_pair is the stored bytes.
     */
    fun buildWithFreshAgreement(
        incoming: PairRequest,
        guardianDisplayName: String,
        guardianEphKeyPair: KeyPair,
        guardianPubSalt: ByteArray,
        guardianKdfIterations: Int,
        ttlSeconds: Long = PairedReply.DEFAULT_TTL_SECONDS,
    ): Pair<String, ByteArray> {
        requireValidParams(guardianDisplayName, guardianPubSalt, guardianKdfIterations, ttlSeconds)

        val guardianEphPub = X25519KeyAgreement.derivePublicKey(guardianEphKeyPair)
        val kPair = X25519KeyAgreement.agree(guardianEphKeyPair.private, incoming.subjectEphPub)

        val url = buildUrl(
            subjectId = incoming.subjectId,
            guardianDisplayName = guardianDisplayName,
            guardianPubSalt = guardianPubSalt,
            guardianEphPub = guardianEphPub,
            guardianKdfIterations = guardianKdfIterations,
            ttlSeconds = ttlSeconds,
            kPair = kPair,
        )
        return url to kPair
    }

    /**
     * PIN-rotation variant. Subject already has K_pair persisted; we just
     * emit a fresh `krypt://paired` with the new salt + iterations and re-sign
     * under the existing K_pair.
     */
    fun buildWithExistingKPair(
        subjectId: java.util.UUID,
        kPair: ByteArray,
        guardianDisplayName: String,
        guardianPubSalt: ByteArray,
        guardianEphPub: ByteArray,
        guardianKdfIterations: Int,
        ttlSeconds: Long = PairedReply.DEFAULT_TTL_SECONDS,
    ): String {
        require(kPair.size == KPAIR_BYTES) { "kPair must be $KPAIR_BYTES bytes" }
        require(guardianEphPub.size == X25519KeyAgreement.PUBLIC_KEY_LEN) {
            "guardianEphPub must be ${X25519KeyAgreement.PUBLIC_KEY_LEN} bytes"
        }
        requireValidParams(guardianDisplayName, guardianPubSalt, guardianKdfIterations, ttlSeconds)

        return buildUrl(
            subjectId = subjectId,
            guardianDisplayName = guardianDisplayName,
            guardianPubSalt = guardianPubSalt,
            guardianEphPub = guardianEphPub,
            guardianKdfIterations = guardianKdfIterations,
            ttlSeconds = ttlSeconds,
            kPair = kPair,
        )
    }

    private fun buildUrl(
        subjectId: java.util.UUID,
        guardianDisplayName: String,
        guardianPubSalt: ByteArray,
        guardianEphPub: ByteArray,
        guardianKdfIterations: Int,
        ttlSeconds: Long,
        kPair: ByteArray,
    ): String {
        val issuedAt = clock.nowSeconds()
        val canonical = canonicalMacInput(
            subjectId, guardianPubSalt, guardianEphPub,
            guardianKdfIterations, issuedAt, ttlSeconds,
        )
        val mac = HmacSha256.mac(kPair, canonical)

        return UrlCodec.build(
            authority = DeepLinkScheme.AUTHORITY_PAIRED,
            params = listOf(
                Params.VERSION        to DeepLinkScheme.PROTOCOL_VERSION,
                Params.SUBJECT_ID     to subjectId.toString(),
                Params.GUARDIAN_NAME  to guardianDisplayName,
                Params.PUB_SALT       to Base64Url.encode(guardianPubSalt),
                Params.EPH_PUB        to Base64Url.encode(guardianEphPub),
                Params.KDF_ITER       to guardianKdfIterations.toString(),
                Params.ISSUED_AT      to issuedAt.toString(),
                Params.TTL            to ttlSeconds.toString(),
                Params.MAC            to Base64Url.encode(mac),
            ),
        )
    }

    private fun requireValidParams(
        guardianDisplayName: String,
        guardianPubSalt: ByteArray,
        guardianKdfIterations: Int,
        ttlSeconds: Long,
    ) {
        require(guardianDisplayName.length in 1..MAX_NAME_LEN) {
            "guardianDisplayName length out of 1..$MAX_NAME_LEN"
        }
        require(guardianPubSalt.size == PUB_SALT_BYTES) {
            "guardianPubSalt must be $PUB_SALT_BYTES bytes (got ${guardianPubSalt.size})"
        }
        require(guardianKdfIterations >= KdfProvider.MIN_ITERATIONS) {
            "guardianKdfIterations ($guardianKdfIterations) must be >= ${KdfProvider.MIN_ITERATIONS}"
        }
        require(ttlSeconds in 1..MAX_TTL_SECONDS) {
            "ttlSeconds ($ttlSeconds) out of 1..$MAX_TTL_SECONDS"
        }
    }

    companion object {
        const val PUB_SALT_BYTES: Int = 32
        const val KPAIR_BYTES: Int = 32
        const val MAX_NAME_LEN: Int = 64
        const val MAX_TTL_SECONDS: Long = 24 * 3600L
    }
}

/**
 * Canonical MAC input layout per contracts/paired.md:
 *   pubSalt || ephPub || kdfIter(BE int64) || iat(BE int64) || ttl(BE int64) || subjectIdBytes(16)
 *
 * Shared between builder and verifier — both must produce identical bytes
 * for the MAC to verify. Any change here REQUIRES a protocol version bump.
 */
internal fun canonicalMacInput(
    subjectId: java.util.UUID,
    pubSalt: ByteArray,
    ephPub: ByteArray,
    kdfIterations: Int,
    issuedAt: Long,
    ttlSeconds: Long,
): ByteArray {
    val out = java.io.ByteArrayOutputStream(pubSalt.size + ephPub.size + 8 + 8 + 8 + 16)
    out.write(pubSalt)
    out.write(ephPub)
    out.write(longToBytesBe(kdfIterations.toLong()))
    out.write(longToBytesBe(issuedAt))
    out.write(longToBytesBe(ttlSeconds))
    out.write(uuidToBytes(subjectId))
    return out.toByteArray()
}

private fun longToBytesBe(v: Long): ByteArray = ByteArray(8).also { b ->
    for (i in 0 until 8) b[i] = (v ushr ((7 - i) * 8)).toByte()
}

private fun uuidToBytes(uuid: java.util.UUID): ByteArray = ByteArray(16).also { b ->
    val msb = uuid.mostSignificantBits
    val lsb = uuid.leastSignificantBits
    for (i in 0 until 8) b[i] = (msb ushr ((7 - i) * 8)).toByte()
    for (i in 0 until 8) b[i + 8] = (lsb ushr ((7 - i) * 8)).toByte()
}
