package com.krypt.app.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.krypt.app.deeplink.Base64Url
import com.krypt.app.security.MasterKeyStore.Companion.MASTER_KEY_BYTES
import com.krypt.app.security.MasterKeyStore.Companion.PIN_PROOF_BYTES
import com.krypt.app.security.MasterKeyStore.Companion.SALT_BYTES
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-Keystore-backed [MasterKeyStore].
 *
 * Stores the three fields as URL-safe Base64 strings under keys `salt`,
 * `master_key`, and `pin_proof` in an [EncryptedSharedPreferences] file named
 * `krypt_master.xml`. The file's master key is an AES-256-GCM key resident in
 * the Android Keystore, so even root on the device cannot extract the stored
 * bytes without breaking the TEE.
 *
 * Mirrors the pattern established by
 * [com.krypt.app.crypto.EncryptedKPairStore] for K_pair.
 */
@Singleton
class EncryptedPrefsMasterKeyStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : MasterKeyStore {

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    override suspend fun isConfigured(): Boolean = withContext(Dispatchers.IO) {
        prefs.contains(KEY_MASTER)
    }

    override suspend fun save(
        salt: ByteArray,
        masterKey: ByteArray,
        pinProof: ByteArray,
    ) = withContext(Dispatchers.IO) {
        require(salt.size == SALT_BYTES) { "salt must be $SALT_BYTES bytes" }
        require(masterKey.size == MASTER_KEY_BYTES) {
            "masterKey must be $MASTER_KEY_BYTES bytes"
        }
        require(pinProof.size == PIN_PROOF_BYTES) {
            "pinProof must be $PIN_PROOF_BYTES bytes"
        }
        // Single commit so callers cannot observe a partially-populated store.
        prefs.edit()
            .putString(KEY_SALT, Base64Url.encode(salt))
            .putString(KEY_MASTER, Base64Url.encode(masterKey))
            .putString(KEY_PROOF, Base64Url.encode(pinProof))
            .apply()
    }

    override suspend fun loadMasterKey(): ByteArray? =
        loadFixed(KEY_MASTER, MASTER_KEY_BYTES)

    override suspend fun loadSalt(): ByteArray? = loadFixed(KEY_SALT, SALT_BYTES)

    override suspend fun loadPinProof(): ByteArray? =
        loadFixed(KEY_PROOF, PIN_PROOF_BYTES)

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit()
            .remove(KEY_SALT)
            .remove(KEY_MASTER)
            .remove(KEY_PROOF)
            .apply()
    }

    private suspend fun loadFixed(key: String, expectedSize: Int): ByteArray? =
        withContext(Dispatchers.IO) {
            val encoded = prefs.getString(key, null) ?: return@withContext null
            Base64Url.tryDecode(encoded)?.takeIf { it.size == expectedSize }
        }

    private companion object {
        const val FILE_NAME = "krypt_master"
        const val KEY_SALT = "salt"
        const val KEY_MASTER = "master_key"
        const val KEY_PROOF = "pin_proof"
    }
}
