package com.krypt.app.data

/** Domain-layer projection of [GuardianPairingEntity]. */
data class GuardianPairing(
    val role: PairingRole,
    val remoteDisplayName: String,
    val pubSalt: ByteArray,
    val kdfIterations: Int,
    val pairedAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is GuardianPairing) return false
        return role == other.role &&
            remoteDisplayName == other.remoteDisplayName &&
            pubSalt.contentEquals(other.pubSalt) &&
            kdfIterations == other.kdfIterations &&
            pairedAt == other.pairedAt
    }
    override fun hashCode(): Int {
        var h = role.hashCode()
        h = 31 * h + remoteDisplayName.hashCode()
        h = 31 * h + pubSalt.contentHashCode()
        h = 31 * h + kdfIterations
        h = 31 * h + pairedAt.hashCode()
        return h
    }
}

internal fun GuardianPairingEntity.toDomain(): GuardianPairing =
    GuardianPairing(role, remoteDisplayName, pubSalt, kdfIterations, pairedAt)

internal fun GuardianPairing.toEntity(): GuardianPairingEntity =
    GuardianPairingEntity(
        id = GuardianPairingEntity.SINGLETON_ID,
        role = role, remoteDisplayName = remoteDisplayName,
        pubSalt = pubSalt, kdfIterations = kdfIterations, pairedAt = pairedAt,
    )
