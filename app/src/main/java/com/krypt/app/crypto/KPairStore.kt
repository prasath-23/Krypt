package com.krypt.app.crypto

/**
 * Persisted store for `K_pair` — the 32-byte shared secret established at
 * pairing time via X25519 Diffie-Hellman (see WP04 and
 * polaris-specs/001-krypt-app-locker/contracts/paired.md).
 *
 * Interface defined here (WP03) because [com.krypt.app.deeplink.ApprovalConsumer]
 * needs it. Concrete Android-Keystore-backed implementation lands in WP06 via
 * `EncryptedSharedPreferences`. The interface signature is shared with WP04
 * T020 — identical by design; the file is authored in the earlier-landing WP
 * and both branches see the same definition after merge.
 *
 * Security contract:
 *  - [save] MUST persist the 32-byte K_pair under an AES-256-backed key that
 *    the OS can only unwrap within this app's sandbox. On consumer Android
 *    that is `androidx.security.crypto.EncryptedSharedPreferences` with a
 *    Keystore-protected master key.
 *  - [load] returns the bytes as a fresh array; callers SHOULD zero their
 *    reference when done.
 *  - [clear] wipes the store (used on unpair, PIN rotation, or forced reset).
 */
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
