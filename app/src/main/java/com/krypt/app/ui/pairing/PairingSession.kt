package com.krypt.app.ui.pairing

import java.security.KeyPair
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Short-lived, in-memory holder for the Subject's X25519 ephemeral key +
 * subjectId during a first-time pairing flow.
 *
 * NOTE: process death loses the ephPrivate — if the pairing is interrupted
 * and later resumed after a restart, the user must regenerate the
 * krypt://pair link. Documented in WP13 risks.
 */
@Singleton
class PairingSession @Inject constructor() {
    @Volatile private var keyPair: KeyPair? = null
    @Volatile private var subjectId: UUID? = null

    fun store(subjectId: UUID, keyPair: KeyPair) {
        this.subjectId = subjectId
        this.keyPair = keyPair
    }

    fun subjectId(): UUID? = subjectId
    fun keyPair(): KeyPair? = keyPair

    fun clear() {
        subjectId = null
        keyPair = null
    }
}
