package com.krypt.app.security

import com.krypt.app.common.Outcome
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class LocalPinValidator @Inject constructor(
    private val masterKeyStore: MasterKeyStore,
    private val kdf: KdfProvider,
    private val hmac: HmacProvider,
    private val settings: SettingsRepository,
) {
    sealed interface ValidationError {
        data object WrongPin : ValidationError
        data object NotConfigured : ValidationError
    }

    suspend fun validate(pin: CharArray): Outcome<Unit, ValidationError> = withContext(Dispatchers.Default) {
        require(pin.isNotEmpty()) { "pin must not be empty" }
        
        val salt = masterKeyStore.loadSalt()
        val expectedProof = masterKeyStore.loadPinProof()
        if (salt == null || expectedProof == null) {
            return@withContext Outcome.err(ValidationError.NotConfigured)
        }

        val iterations = try {
            settings.settings.first().kdfIterations
        } catch (t: Throwable) {
            KdfProvider.MIN_ITERATIONS
        }

        val masterKey = kdf.derive(
            pin = pin,
            salt = salt,
            iterations = iterations,
            outputBytes = MasterKeyStore.MASTER_KEY_BYTES,
        )
        
        try {
            val recomputed = hmac.sha256(
                masterKey,
                MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8),
            )
            val matches = MessageDigest.isEqual(recomputed, expectedProof)
            if (matches) {
                Outcome.ok(Unit)
            } else {
                Outcome.err(ValidationError.WrongPin)
            }
        } finally {
            masterKey.fill(0.toByte())
            pin.fill(' ')
        }
    }
}
