package com.krypt.app.guardian

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.security.MasterKeyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Amendment 1 Guardian-side PIN validator.
 *
 * Derives `MasterKey = PBKDF2-HMAC-SHA-256(PIN, request.salt, iterations, 32)`
 * on-the-fly from the PIN the Guardian just typed and the setup salt
 * embedded in the incoming request URL. Computes `recomputedProof =
 * HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")` and constant-time compares
 * it to `request.pinProof`. On match, returns the derived `MasterKey` so
 * the caller can feed it into `ApprovalLinkBuilder.build(masterKey, ...)`.
 *
 * Purely deterministic / pure-JVM — no persistent state, no Android
 * dependencies. The Guardian's device is fully stateless between requests.
 */
@Singleton
class GuardianPinValidator @Inject constructor(
    private val kdf: KdfProvider,
    private val hmac: HmacProvider,
) {

    sealed interface ValidationError {
        /** Entered PIN did not match `request.pinProof`. */
        data object WrongPin : ValidationError
    }

    /**
     * @param pin             4-to-8 digit PIN typed by the Guardian.
     * @param request         parsed request (carries salt + pinProof).
     * @param iterations      PBKDF2 iteration count. Defaults to [KdfProvider.MIN_ITERATIONS];
     *                        callers may pass a higher, device-calibrated value.
     *
     * Zeroes [pin] after use (best-effort — Kotlin Strings aren't mutable,
     * but [CharArray] IS).
     */
    suspend fun validate(
        pin: CharArray,
        request: UnlockRequest,
        iterations: Int = KdfProvider.MIN_ITERATIONS,
    ): Outcome<ByteArray, ValidationError> = withContext(Dispatchers.Default) {
        require(pin.isNotEmpty()) { "pin must not be empty" }
        require(iterations >= KdfProvider.MIN_ITERATIONS) {
            "iterations must be >= ${KdfProvider.MIN_ITERATIONS}"
        }
        val masterKey = kdf.derive(
            pin = pin,
            salt = request.salt,
            iterations = iterations,
            outputBytes = MasterKeyStore.MASTER_KEY_BYTES,
        )
        try {
            val recomputed = hmac.sha256(
                masterKey,
                MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8),
            )
            val matches = MessageDigest.isEqual(recomputed, request.pinProof)
            if (matches) {
                Outcome.ok(masterKey.copyOf())
            } else {
                Outcome.err(ValidationError.WrongPin)
            }
        } finally {
            // Always wipe the local PBKDF2 output before returning. Caller
            // gets a fresh copy on success; on failure nothing leaks.
            masterKey.fill(0)
            pin.fill(' ')
        }
    }
}
