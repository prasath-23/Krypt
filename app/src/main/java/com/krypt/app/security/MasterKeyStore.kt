package com.krypt.app.security

/**
 * Persisted store for the Subject device's `MasterKey` triple established at
 * first-time Guardian PIN setup (Amendment 1).
 *
 * At setup, the Subject device derives:
 *   MasterKey = PBKDF2-HMAC-SHA-256(PIN, salt, iterations >= 300_000, 32 bytes)
 *   pinProof  = HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")
 * and persists `{salt, MasterKey, pinProof}` here. The typed PIN chars are
 * zeroed immediately after derivation and NEVER reach this store.
 *
 * Security contract:
 *  - [save] MUST persist all three fields atomically (same commit) under an
 *    AES-256-backed key the OS unwraps only inside this app's sandbox. On
 *    consumer Android that is [androidx.security.crypto.EncryptedSharedPreferences]
 *    with a Keystore-protected master key.
 *  - [loadMasterKey] returns a fresh byte array on each call; callers SHOULD
 *    zero their reference when done handling it.
 *  - [clear] wipes the whole triple. Used on "re-setup PIN" flow or app-data
 *    clear.
 *
 * Supersedes [com.krypt.app.crypto.KPairStore] in the live path per
 * Amendment 1 (FR-017, FR-018).
 */
interface MasterKeyStore {

    /**
     * Return `true` if [save] has been called at least once without an
     * intervening [clear]. Fast — just checks for the backing blob's
     * presence, does not decrypt.
     */
    suspend fun isConfigured(): Boolean

    /**
     * Persist the 16-byte [salt], 32-byte [masterKey], and 32-byte [pinProof]
     * in a single atomic write.
     *
     * @throws IllegalArgumentException on unexpected sizes.
     */
    suspend fun save(salt: ByteArray, masterKey: ByteArray, pinProof: ByteArray)

    /**
     * Load the 32-byte MasterKey. Returns `null` if the store is not configured
     * (no prior successful [save]).
     */
    suspend fun loadMasterKey(): ByteArray?

    /** Load the 16-byte salt used to derive the MasterKey. */
    suspend fun loadSalt(): ByteArray?

    /**
     * Load the 32-byte `pinProof` (HMAC-SHA-256 of MasterKey under label
     * `"krypt/v1/pin-proof"`). Embedded in outgoing `krypt://request` URLs so
     * the Guardian device can validate a typed PIN before composing an approval.
     */
    suspend fun loadPinProof(): ByteArray?

    /** Wipe all three stored fields. */
    suspend fun clear()

    companion object {
        /** Salt length in bytes. */
        const val SALT_BYTES = 16

        /** MasterKey length in bytes (AES-256). */
        const val MASTER_KEY_BYTES = 32

        /** `pinProof` length in bytes (HMAC-SHA-256 tag). */
        const val PIN_PROOF_BYTES = 32

        /** HMAC label used to derive `pinProof` from MasterKey. */
        const val PIN_PROOF_LABEL = "krypt/v1/pin-proof"
    }
}
