package com.krypt.app.data.daily

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Every-day rules and the time used under them. Rules are written only by
 * consuming a Guardian approval
 * ([com.krypt.app.data.OutstandingRequestRepository.consumeAndUpsertDailyAllowance]);
 * this is the read side, plus usage, ending and pruning.
 */
interface DailyAllowanceRepository {
    suspend fun rules(): List<DailyAllowance>

    /** (package name, epoch day) -> milliseconds used. */
    suspend fun usage(): Map<Pair<String, Long>, Long>

    /** Save [usedMs] for [pkg] on [epochDay]. Never lowers a saved value. */
    suspend fun saveUsage(pkg: String, epochDay: Long, usedMs: Long)

    suspend fun end(pkg: String)

    /**
     * Drop rules that ended before [rulesEndedBefore], and usage from before
     * [usageBefore] on days no rule covers (epoch days).
     */
    suspend fun prune(rulesEndedBefore: Long, usageBefore: Long)
}

@Singleton
class RoomDailyAllowanceRepository @Inject constructor(
    private val rulesDao: DailyAllowanceDao,
    private val usageDao: DailyUsageDao,
) : DailyAllowanceRepository {

    private val usageWrites = Mutex()

    override suspend fun rules(): List<DailyAllowance> = rulesDao.all().map { it.toDomain() }

    override suspend fun usage(): Map<Pair<String, Long>, Long> =
        usageDao.all().associate { (it.packageName to it.epochDay) to it.usedMs }

    override suspend fun saveUsage(pkg: String, epochDay: Long, usedMs: Long) {
        usageWrites.withLock {
            val saved = usageDao.usedMs(pkg, epochDay) ?: 0L
            if (usedMs > saved) usageDao.upsert(DailyUsageEntity(pkg, epochDay, usedMs))
        }
    }

    override suspend fun end(pkg: String) {
        rulesDao.delete(pkg)
    }

    override suspend fun prune(rulesEndedBefore: Long, usageBefore: Long) {
        rulesDao.pruneEndedBefore(rulesEndedBefore)
        usageDao.pruneBefore(usageBefore)
    }
}
