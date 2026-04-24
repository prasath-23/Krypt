package com.krypt.app.ui.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.krypt.app.common.Outcome
import com.krypt.app.crypto.HmacProvider
import com.krypt.app.crypto.KdfProvider
import com.krypt.app.crypto.SecureRandomSource
import com.krypt.app.data.settings.SettingsRepository
import com.krypt.app.security.MasterKeyStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/**
 * Drives the Amendment 1 "Guardian sets PIN on Subject device" flow.
 *
 *  1. Validate the typed PIN shape (4-6 digits, both entries match).
 *  2. Generate a 16-byte random salt via [SecureRandomSource].
 *  3. Calibrate [KdfProvider] iterations once, persist the count, and derive
 *     `MasterKey = PBKDF2-HMAC-SHA-256(pin, salt, iterations, 32 bytes)`.
 *  4. Compute `pinProof = HMAC-SHA-256(MasterKey, "krypt/v1/pin-proof")`.
 *  5. Atomically persist `{salt, MasterKey, pinProof}` to [MasterKeyStore].
 *  6. Zero the caller-owned [CharArray] (belt-and-suspenders — the Compose
 *     screen also zeroes via a DisposableEffect).
 */
@HiltViewModel
class PinSetupViewModel @Inject constructor(
    private val kdf: KdfProvider,
    private val rng: SecureRandomSource,
    private val hmac: HmacProvider,
    private val store: MasterKeyStore,
    private val settings: SettingsRepository,
) : ViewModel() {

    private val _state = MutableStateFlow<PinSetupState>(PinSetupState.Idle)
    val state: StateFlow<PinSetupState> = _state.asStateFlow()

    /**
     * Validate and persist the PIN. Returns via [state] rather than a
     * suspending return so the Compose layer can observe progress (spinner
     * during the ~300 ms KDF call).
     *
     * Callers retain ownership of [pin]; this method overwrites it with
     * spaces after successful derivation but still expects the screen to
     * clear its own buffer on dispose.
     */
    fun save(pin: CharArray, confirm: CharArray) {
        if (_state.value is PinSetupState.Saving) return
        when (val err = validateShape(pin, confirm)) {
            null -> Unit
            else -> {
                _state.value = PinSetupState.Error(err)
                return
            }
        }
        _state.value = PinSetupState.Saving
        viewModelScope.launch {
            val outcome = try {
                deriveAndPersist(pin)
            } catch (t: Throwable) {
                Outcome.Err(PinSetupError.SaveFailed)
            }
            _state.value = when (outcome) {
                is Outcome.Ok -> PinSetupState.Done
                is Outcome.Err -> PinSetupState.Error(outcome.error)
            }
        }
    }

    /**
     * Reset the ViewModel state back to [PinSetupState.Idle] so the UI can
     * recover after an error without re-creating the whole screen.
     */
    fun acknowledgeError() {
        if (_state.value is PinSetupState.Error) {
            _state.value = PinSetupState.Idle
        }
    }

    private fun validateShape(pin: CharArray, confirm: CharArray): PinSetupError? {
        if (pin.size < MIN_PIN_LEN || pin.size > MAX_PIN_LEN) return PinSetupError.TooShort
        if (!pin.all { it.isDigit() }) return PinSetupError.NotNumeric
        if (pin.size != confirm.size || !pin.contentEquals(confirm)) return PinSetupError.Mismatch
        return null
    }

    private suspend fun deriveAndPersist(pin: CharArray): Outcome<Unit, PinSetupError> {
        // All crypto runs on Dispatchers.Default so the calibration + 300 ms
        // PBKDF2 call doesn't block the main thread.
        return withContext(Dispatchers.Default) {
            val iterations = resolveIterations()
            val salt = rng.nextBytes(MasterKeyStore.SALT_BYTES)
            val masterKey = kdf.derive(pin, salt, iterations, MasterKeyStore.MASTER_KEY_BYTES)
            try {
                val pinProof = hmac.sha256(
                    masterKey,
                    MasterKeyStore.PIN_PROOF_LABEL.toByteArray(Charsets.UTF_8),
                )
                store.save(salt, masterKey, pinProof)
                Outcome.Ok(Unit)
            } finally {
                // Zero caller-owned PIN + derived secrets. Salt + proof are
                // persisted so don't get zeroed here.
                pin.fill(' ')
                masterKey.fill(0)
            }
        }
    }

    /**
     * Return a calibrated PBKDF2 iteration count. On first call, measure
     * actual cost on this device and persist via [SettingsRepository]; later
     * calls re-use the persisted number.
     *
     * We treat any persisted value greater than the floor as "already
     * calibrated". The shipped default is exactly the floor, so a
     * freshly-installed device always triggers calibration once.
     */
    private suspend fun resolveIterations(): Int {
        val current = try {
            settings.settings.first().kdfIterations
        } catch (t: Throwable) {
            KdfProvider.MIN_ITERATIONS
        }

        return if (current > KdfProvider.MIN_ITERATIONS) {
            current
        } else {
            val calibrated = kdf.calibrateIterationsForDevice(
                targetMillis = CALIBRATION_TARGET_MS,
                floor = KdfProvider.MIN_ITERATIONS,
            )
            try {
                settings.setKdfIterations(calibrated)
            } catch (_: Throwable) {
                // Persistence failure is non-fatal — next call simply
                // re-calibrates. PIN setup must still succeed.
            }
            calibrated
        }
    }

    companion object {
        const val MIN_PIN_LEN = 4
        const val MAX_PIN_LEN = 6
        const val CALIBRATION_TARGET_MS = 300L
    }
}

/** UI-consumable state machine for [PinSetupViewModel]. */
sealed interface PinSetupState {
    data object Idle : PinSetupState
    data object Saving : PinSetupState
    data object Done : PinSetupState
    data class Error(val reason: PinSetupError) : PinSetupState
}

/** Exhaustive error set surfaced by [PinSetupViewModel.save]. */
sealed interface PinSetupError {
    data object TooShort : PinSetupError
    data object NotNumeric : PinSetupError
    data object Mismatch : PinSetupError
    data object SaveFailed : PinSetupError
}
