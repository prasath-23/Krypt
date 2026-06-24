package com.krypt.app.crypto

import java.security.SecureRandom
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable source of cryptographically-secure random bytes.
 *
 * Abstracted behind an interface so unit and instrumented tests can substitute
 * deterministic fakes without touching call sites that depend on randomness
 * (nonce generation in [AesGcmCipher] callers, salt generation in
 * UnlockRequestBuilder, ephemeral-key generation in [X25519KeyAgreement]).
 *
 * The production implementation is [RealSecureRandomSource]; Hilt binds it as
 * the `@Singleton` [SecureRandomSource] via `CryptoModule` (see di/ folder).
 */
interface SecureRandomSource {

    /**
     * Return a freshly-allocated byte array of [size] filled with secure
     * random bytes.
     *
     * @throws IllegalArgumentException if size is negative.
     */
    fun nextBytes(size: Int): ByteArray
}

/**
 * Production implementation of [SecureRandomSource].
 *
 * Backed by a single shared [SecureRandom] instance (`@Singleton`). On Android
 * API 26+ the default `SecureRandom` uses `/dev/urandom` via the `AndroidOpenSSL`
 * (Conscrypt) provider, which is the correct primary entropy source per the
 * Android Developer Documentation.
 */
@Singleton
class RealSecureRandomSource @Inject constructor() : SecureRandomSource {

    private val rng = SecureRandom()

    override fun nextBytes(size: Int): ByteArray {
        require(size >= 0) { "size ($size) must be non-negative" }
        if (size == 0) return ByteArray(0)
        val buffer = ByteArray(size)
        rng.nextBytes(buffer)
        return buffer
    }
}
