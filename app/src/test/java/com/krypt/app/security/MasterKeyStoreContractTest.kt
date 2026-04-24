package com.krypt.app.security

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for any [MasterKeyStore] implementation that does not
 * require Android framework classes. Exercises [FakeMasterKeyStore] as the
 * reference; [EncryptedPrefsMasterKeyStore] is covered by instrumented tests
 * in WP23 since it needs a real [android.content.Context] and the Keystore.
 */
class MasterKeyStoreContractTest {

    @Test
    fun freshStoreIsNotConfigured() = runTest {
        val store = FakeMasterKeyStore()
        assertFalse(store.isConfigured())
        assertNull(store.loadSalt())
        assertNull(store.loadMasterKey())
        assertNull(store.loadPinProof())
    }

    @Test
    fun saveThenLoad_roundTripsAllThreeFields() = runTest {
        val store = FakeMasterKeyStore()
        val salt = ByteArray(16) { i -> (i * 7).toByte() }
        val masterKey = ByteArray(32) { i -> (i + 1).toByte() }
        val pinProof = ByteArray(32) { i -> (i * 3 + 5).toByte() }

        store.save(salt, masterKey, pinProof)

        assertTrue(store.isConfigured())
        assertArrayEquals(salt, store.loadSalt())
        assertArrayEquals(masterKey, store.loadMasterKey())
        assertArrayEquals(pinProof, store.loadPinProof())
    }

    @Test
    fun loadReturnsFreshCopyEachTime() = runTest {
        val store = FakeMasterKeyStore()
        val salt = ByteArray(16) { 1 }
        val masterKey = ByteArray(32) { 2 }
        val pinProof = ByteArray(32) { 3 }
        store.save(salt, masterKey, pinProof)

        val first = store.loadMasterKey()!!
        first[0] = 0xFF.toByte()
        val second = store.loadMasterKey()!!

        assertEquals(
            "mutating a returned ByteArray must not poison the store",
            2.toByte(),
            second[0],
        )
    }

    @Test
    fun clearWipesAllThreeFields() = runTest {
        val store = FakeMasterKeyStore()
        store.save(
            ByteArray(16) { 9 },
            ByteArray(32) { 9 },
            ByteArray(32) { 9 },
        )
        assertTrue(store.isConfigured())

        store.clear()

        assertFalse(store.isConfigured())
        assertNull(store.loadSalt())
        assertNull(store.loadMasterKey())
        assertNull(store.loadPinProof())
    }

    @Test
    fun saveRejectsBadSizes() = runTest {
        val store = FakeMasterKeyStore()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.save(
                    salt = ByteArray(15),
                    masterKey = ByteArray(32),
                    pinProof = ByteArray(32),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.save(
                    salt = ByteArray(16),
                    masterKey = ByteArray(31),
                    pinProof = ByteArray(32),
                )
            }
        }
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking {
                store.save(
                    salt = ByteArray(16),
                    masterKey = ByteArray(32),
                    pinProof = ByteArray(31),
                )
            }
        }
    }

    @Test
    fun resaveOverwritesPreviousValues() = runTest {
        val store = FakeMasterKeyStore()
        store.save(
            ByteArray(16) { 1 },
            ByteArray(32) { 1 },
            ByteArray(32) { 1 },
        )
        store.save(
            ByteArray(16) { 2 },
            ByteArray(32) { 2 },
            ByteArray(32) { 2 },
        )
        assertNotNull(store.loadMasterKey())
        val masterKey = store.loadMasterKey()!!
        assertEquals(2.toByte(), masterKey[0])
    }
}
