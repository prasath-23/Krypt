package com.krypt.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Single-row (id = [SINGLETON_ID]) pairing record for this device.
 * Pairing is 1-to-1, so only one row ever exists.
 */
@Entity(tableName = "guardian_pairing")
data class GuardianPairingEntity(
    @PrimaryKey val id: Int = SINGLETON_ID,
    val role: PairingRole,
    /** Other party's display name ("Dad's Phone" on Subject, "Alice's Pixel" on Guardian). */
    val remoteDisplayName: String,
    val pubSalt: ByteArray,
    val kdfIterations: Int,
    val pairedAt: Long,
) {
    companion object { const val SINGLETON_ID: Int = 1 }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GuardianPairingEntity) return false
        return id == other.id && role == other.role &&
            remoteDisplayName == other.remoteDisplayName &&
            pubSalt.contentEquals(other.pubSalt) &&
            kdfIterations == other.kdfIterations &&
            pairedAt == other.pairedAt
    }

    override fun hashCode(): Int {
        var h = id
        h = 31 * h + role.hashCode()
        h = 31 * h + remoteDisplayName.hashCode()
        h = 31 * h + pubSalt.contentHashCode()
        h = 31 * h + kdfIterations
        h = 31 * h + pairedAt.hashCode()
        return h
    }
}
