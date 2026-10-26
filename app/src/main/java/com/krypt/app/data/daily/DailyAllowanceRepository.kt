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

    /** (package name, [LocalMinutes] minute) -> milliseconds used, for the minutes from [fromMinute] on. */
    suspend fun usage(fromMinute: Long): Map<Pair<String, Long>, Long>

    /** Save [usedMs] for [pkg] in [minute]. Never lowers a saved value. */
    suspend fun saveUsage(pkg: String, minute: Long, usedMs: Long)

    suspend fun end(pkg: String)

    /**
     * Drop rules that ended before [rulesEndedBefore] (an epoch day), and
     * usage from before [usageBeforeMinute] (a [LocalMinutes] minute) on days
     * no rule covers.
     */
    suspend fun prune(rulesEndedBefore: Long, usageBeforeMinute: Long)
}

@Singleton
class RoomDailyAllowanceRepository @Inject constructor(
    private val rulesDao: DailyAllowanceDao,
    private val usageDao: MinuteUsageDao,
) : DailyAllowanceRepository {

    private val usageWrites = Mutex()

    override suspend fun rules(): List<DailyAllowance> = rulesDao.all().map { it.toDomain() }

    override suspend fun usage(fromMinute: Long): Map<Pair<String, Long>, Long> =
        usageDao.from(fromMinute).associate { (it.packageName to it.minute) to it.usedMs }

    override suspend fun saveUsage(pkg: String, minute: Long, usedMs: Long) {
        usageWrites.withLock {
            val saved = usageDao.usedMs(pkg, minute) ?: 0L
            if (usedMs > saved) usageDao.upsert(MinuteUsageEntity(pkg, minute, usedMs))
        }
    }

    override suspend fun end(pkg: String) {
        rulesDao.delete(pkg)
    }

    override suspend fun prune(rulesEndedBefore: Long, usageBeforeMinute: Long) {
        rulesDao.pruneEndedBefore(rulesEndedBefore)
        usageDao.pruneBefore(usageBeforeMinute)
    }
}
