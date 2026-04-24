package com.krypt.app.crypto

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.krypt.app.deeplink.Base64Url
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android-Keystore-backed implementation of [KPairStore].
 *
 * The K_pair bytes are stored as URL-safe Base64 under the key [KEY_NAME] in
 * an [EncryptedSharedPreferences] file (`krypt_kpair.xml`). The master key
 * is an AES-256-GCM key resident in the Android Keystore — the OS will
 * unwrap it only within this app's sandbox, so even root access on the
 * device cannot extract K_pair without breaking the TEE.
 *
 * Binding to the [KPairStore] interface is in `DataModule`.
 */
@Singleton
class EncryptedKPairStore @Inject constructor(
    @ApplicationContext private val context: Context,
) : KPairStore {

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

    override suspend fun save(kPair: ByteArray) = withContext(Dispatchers.IO) {
        require(kPair.size == 32) { "kPair must be 32 bytes" }
        prefs.edit().putString(KEY_NAME, Base64Url.encode(kPair)).apply()
    }

    override suspend fun load(): ByteArray? = withContext(Dispatchers.IO) {
        val b64 = prefs.getString(KEY_NAME, null) ?: return@withContext null
        Base64Url.tryDecode(b64)?.takeIf { it.size == 32 }
    }

    override suspend fun clear() = withContext(Dispatchers.IO) {
        prefs.edit().remove(KEY_NAME).apply()
    }

    override fun isPaired(): Boolean = prefs.contains(KEY_NAME)

    private companion object {
        const val FILE_NAME = "krypt_kpair"
        const val KEY_NAME = "k_pair"
    }
}
