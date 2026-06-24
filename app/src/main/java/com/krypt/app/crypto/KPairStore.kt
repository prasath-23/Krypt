package com.krypt.app.crypto

/**
 * Persisted store for `K_pair` — the 32-byte shared secret established at
 * pairing time via X25519 Diffie-Hellman.
 *
 * **Superseded by [com.krypt.app.security.MasterKeyStore] in Amendment 1.**
 * The live flow no longer uses X25519 pairing; MasterKey (PBKDF2-derived)
 * replaces K_pair. This interface and [EncryptedKPairStore] remain in the
 * tree only so the shipped WP04 pairing deep-link classes compile. No live
 * consumer reads from this store post-Amendment 1.
 */
@Deprecated(
    message = "Amendment 1: X25519 K_pair removed from the live path. Use " +
        "com.krypt.app.security.MasterKeyStore.",
    level = DeprecationLevel.WARNING,
)
interface KPairStore {

    suspend fun save(kPair: ByteArray)

    suspend fun load(): ByteArray?

    suspend fun clear()

    /**
     * Fast synchronous existence check, for UI gates that can't suspend.
     * Implementations MUST NOT perform any decryption here — just check
     * whether the backing blob is present.
     */
    fun isPaired(): Boolean
}
