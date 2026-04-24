package com.krypt.app.crypto

/**
 * In-memory fake [KPairStore] for unit tests. Thread-safe via @Volatile.
 *
 * A similar class exists inline in WP03's ApprovalRoundTripTest; this
 * standalone copy is available for WP04+ tests that import the crypto
 * layer directly.
 */
class FakeKPairStore : KPairStore {
    @Volatile private var value: ByteArray? = null

    override suspend fun save(kPair: ByteArray) { value = kPair.copyOf() }
    override suspend fun load(): ByteArray? = value?.copyOf()
    override suspend fun clear() { value = null }
    override fun isPaired(): Boolean = value != null
}
