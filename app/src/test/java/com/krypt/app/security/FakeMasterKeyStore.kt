package com.krypt.app.security

/**
 * In-memory fake [MasterKeyStore] for unit tests.
 *
 * Mirrors the contract of [EncryptedPrefsMasterKeyStore] without touching
 * Android's EncryptedSharedPreferences (which requires Robolectric or an
 * instrumented environment).
 *
 * Thread-safety is sufficient for single-threaded test coroutines. If a test
 * drives this from multiple threads, wrap calls in a mutex.
 */
class FakeMasterKeyStore : MasterKeyStore {

    @Volatile var savedSalt: ByteArray? = null
        private set
    @Volatile var savedMasterKey: ByteArray? = null
        private set
    @Volatile var savedPinProof: ByteArray? = null
        private set

    var saveCallCount: Int = 0
        private set

    override suspend fun isConfigured(): Boolean = savedMasterKey != null

    override suspend fun save(
        salt: ByteArray,
        masterKey: ByteArray,
        pinProof: ByteArray,
    ) {
        require(salt.size == MasterKeyStore.SALT_BYTES)
        require(masterKey.size == MasterKeyStore.MASTER_KEY_BYTES)
        require(pinProof.size == MasterKeyStore.PIN_PROOF_BYTES)
        savedSalt = salt.copyOf()
        savedMasterKey = masterKey.copyOf()
        savedPinProof = pinProof.copyOf()
        saveCallCount++
    }

    override suspend fun loadMasterKey(): ByteArray? = savedMasterKey?.copyOf()
    override suspend fun loadSalt(): ByteArray? = savedSalt?.copyOf()
    override suspend fun loadPinProof(): ByteArray? = savedPinProof?.copyOf()

    override suspend fun clear() {
        savedSalt = null
        savedMasterKey = null
        savedPinProof = null
    }
}
