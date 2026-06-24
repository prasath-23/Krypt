package com.krypt.app.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

interface GuardianRepository {
    suspend fun getPairing(): GuardianPairing?
    fun observePairing(): Flow<GuardianPairing?>
    suspend fun savePairing(p: GuardianPairing)
    suspend fun clearPairing()
}

@Singleton
class RoomGuardianRepository @Inject constructor(
    private val dao: GuardianPairingDao,
) : GuardianRepository {

    override suspend fun getPairing(): GuardianPairing? = dao.getPairing()?.toDomain()

    override fun observePairing(): Flow<GuardianPairing?> =
        dao.observePairing().map { it?.toDomain() }

    override suspend fun savePairing(p: GuardianPairing) {
        dao.upsert(p.toEntity())
    }

    override suspend fun clearPairing() {
        dao.clear()
    }
}
