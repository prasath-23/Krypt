package com.krypt.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "outstanding_requests")
data class OutstandingRequestEntity(
    @PrimaryKey val requestId: String,
    val targetPackage: String,
    val salt: ByteArray,
    val issuedAt: Long,
    val expiresAt: Long,
    /** 0 = open, 1 = consumed. */
    val consumed: Int,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is OutstandingRequestEntity) return false
        return requestId == other.requestId &&
            targetPackage == other.targetPackage &&
            salt.contentEquals(other.salt) &&
            issuedAt == other.issuedAt &&
            expiresAt == other.expiresAt &&
            consumed == other.consumed
    }

    override fun hashCode(): Int {
        var h = requestId.hashCode()
        h = 31 * h + targetPackage.hashCode()
        h = 31 * h + salt.contentHashCode()
        h = 31 * h + issuedAt.hashCode()
        h = 31 * h + expiresAt.hashCode()
        h = 31 * h + consumed
        return h
    }
}
