package com.krypt.app.e2e

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.data.KryptDatabase
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.security.MasterKeyStore
import com.krypt.app.security.PinAttemptLimiter
import kotlinx.coroutines.runBlocking
import javax.inject.Inject

/** The Guardian PIN every instrumented test sets up. */
const val TEST_PIN = "2468"

/**
 * Puts the app in a known state before a test. The Room database, settings
 * and PIN-attempt counter are real and outlive a single test, so every test
 * starts by resetting them.
 */
class TestState @Inject constructor(
    private val db: KryptDatabase,
    private val settings: SettingsRepository,
    private val masterKeyStore: MasterKeyStore,
    private val limiter: PinAttemptLimiter,
    private val sessionStore: LockerSessionStore,
    private val kdf: KdfProvider,
    private val hmac: HmacProvider,
) {

    fun reset(onboardingComplete: Boolean, pinConfigured: Boolean) = runBlocking {
        db.clearAllTables()
        settings.setOnboardingComplete(onboardingComplete)
        settings.setKdfIterations(KdfProvider.MIN_ITERATIONS)
        limiter.recordSuccess()
        sessionStore.expireAll()
        masterKeyStore.clear()
        if (pinConfigured) setUpPin()
    }

    /** Store what PinSetupViewModel stores for [TEST_PIN]; returns the MasterKey. */
    suspend fun setUpPin(): ByteArray {
        val salt = ByteArray(MasterKeyStore.SALT_BYTES) { (it * 3 + 1).toByte() }
        val masterKey = kdf.derive(
            TEST_PIN.toCharArray(), salt, KdfProvider.MIN_ITERATIONS, MasterKeyStore.MASTER_KEY_BYTES,
        )
        val pinProof = hmac.sha256(masterKey, MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8))
        masterKeyStore.save(salt, masterKey, pinProof)
        return masterKey
    }
}

val targetContext: Context get() = ApplicationProvider.getApplicationContext()

/** Put [text] on the clipboard, as if copied from a chat. */
fun copyToClipboard(text: String) {
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
        val clipboard = targetContext.getSystemService(ClipboardManager::class.java)
        clipboard.setPrimaryClip(ClipData.newPlainText("message", text))
    }
}
