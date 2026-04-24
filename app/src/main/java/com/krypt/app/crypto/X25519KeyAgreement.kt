package com.krypt.app.crypto

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.PublicKey
import java.security.interfaces.XECPublicKey
import java.security.spec.NamedParameterSpec
import java.security.spec.XECPublicKeySpec

/**
 * X25519 Diffie-Hellman key agreement for Krypt's Subject-Guardian pairing.
 *
 * Pairing flow (see polaris-specs/001-krypt-app-locker/contracts/paired.md):
 *   1. Subject generates an ephemeral keypair (Priv_s, Pub_s) on first launch.
 *   2. Guardian, on receiving krypt://pair, generates its own (Priv_g, Pub_g)
 *      and computes K_pair = X25519(Priv_g, Pub_s).
 *   3. Subject, on receiving krypt://paired, computes K_pair = X25519(Priv_s, Pub_g).
 *   4. Both sides now hold the same 32-byte K_pair which is used to derive
 *      per-request AES-256 keys via [KeyDeriver].
 *
 * ## API-29/30 caveat (research.md R4)
 *
 * `KeyPairGenerator.getInstance("XDH")` is only guaranteed on API 31+
 * (Conscrypt 2.5+). On API 29/30, this class will throw
 * [java.security.NoSuchAlgorithmException] at first use. If that surfaces on
 * a real device, pick one of:
 *   - Raise `minSdk` to 31 (plan: acceptable if devices << 3% market share).
 *   - Add `org.conscrypt:conscrypt-android` as a first-party Google dependency
 *     that backports XDH to API 23+.
 *   - (Rejected) ship a pure-Java X25519 implementation — requires an external
 *     library, violates the "no third-party crypto" rule.
 *
 * The JVM unit tests run under JDK 11+ where `XDH` is always available, so
 * the tests do NOT depend on the Android-side fallback path.
 */
object X25519KeyAgreement {

    private const val ALGORITHM = "XDH"
    private const val CURVE = "X25519"
    private val PARAMS = NamedParameterSpec(CURVE)

    const val PUBLIC_KEY_LEN = 32
    const val SHARED_SECRET_LEN = 32

    /**
     * Generate a fresh X25519 ephemeral keypair.
     *
     * The private key stays in the provider's opaque key store (no raw access);
     * the public key is extracted via [derivePublicKey] for transmission.
     */
    fun generateEphemeralKeyPair(): KeyPair =
        KeyPairGenerator.getInstance(ALGORITHM)
            .apply { initialize(PARAMS) }
            .generateKeyPair()

    /**
     * Extract the 32-byte raw public key (little-endian u-coordinate) from a
     * JCE-format X25519 public key. The result is suitable for wire transport.
     */
    fun derivePublicKey(publicKey: PublicKey): ByteArray {
        val xec = publicKey as? XECPublicKey
            ?: error("public key is not XECPublicKey (got ${publicKey.javaClass.name})")

        // `getU()` returns the u-coordinate as an unsigned BigInteger.
        // BigInteger.toByteArray() emits big-endian bytes with a leading sign
        // byte; X25519 expects 32-byte little-endian. Reverse + zero-pad.
        val uBigEndian = xec.u.toByteArray()
        val le = ByteArray(PUBLIC_KEY_LEN)
        val copyLen = minOf(uBigEndian.size, PUBLIC_KEY_LEN)
        for (i in 0 until copyLen) {
            // Write LSB first in `le`; peel LSB-to-MSB from the big-endian source.
            le[i] = uBigEndian[uBigEndian.size - 1 - i]
        }
        return le
    }

    fun derivePublicKey(keyPair: KeyPair): ByteArray = derivePublicKey(keyPair.public)

    /**
     * Reconstruct a JCE [PublicKey] from a 32-byte little-endian u-coordinate.
     *
     * Per RFC 7748 §5, the high bit of byte 31 is masked off before interpreting
     * the scalar (it's reserved for future extension).
     */
    fun publicKeyFromBytes(theirPublicRaw: ByteArray): PublicKey {
        require(theirPublicRaw.size == PUBLIC_KEY_LEN) {
            "public key must be $PUBLIC_KEY_LEN bytes (got ${theirPublicRaw.size})"
        }

        // Mask off the unused high bit per RFC 7748 §5.
        val masked = theirPublicRaw.copyOf()
        masked[PUBLIC_KEY_LEN - 1] = (masked[PUBLIC_KEY_LEN - 1].toInt() and 0x7F).toByte()

        // Convert LE bytes -> unsigned BigInteger. Prepend a zero sign byte so
        // BigInteger interprets the value as positive regardless of top bit.
        val beWithSign = ByteArray(PUBLIC_KEY_LEN + 1)
        for (i in 0 until PUBLIC_KEY_LEN) {
            beWithSign[PUBLIC_KEY_LEN - i] = masked[i]
        }
        val u = BigInteger(beWithSign)

        val spec = XECPublicKeySpec(PARAMS, u)
        return KeyFactory.getInstance(ALGORITHM).generatePublic(spec)
    }

    /**
     * Derive the 32-byte X25519 shared secret between our private key and the
     * peer's raw public key bytes.
     *
     * @throws IllegalStateException if the provider returns a shared secret of
     *   unexpected length (should never happen for X25519).
     */
    fun agree(myPrivate: PrivateKey, theirPublicRaw: ByteArray): ByteArray {
        val theirPublic = publicKeyFromBytes(theirPublicRaw)
        val agreement = javax.crypto.KeyAgreement.getInstance(ALGORITHM).apply {
            init(myPrivate)
            doPhase(theirPublic, true)
        }
        val secret = agreement.generateSecret()
        check(secret.size == SHARED_SECRET_LEN) {
            "X25519 shared secret length is ${secret.size}, expected $SHARED_SECRET_LEN"
        }
        return secret
    }
}
