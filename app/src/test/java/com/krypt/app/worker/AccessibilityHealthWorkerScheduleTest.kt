package com.krypt.app.worker

import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequest
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.google.common.util.concurrent.ListenableFuture
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.concurrent.TimeUnit

/** The health check is scheduled at every process start (Amendment 5). */
class AccessibilityHealthWorkerScheduleTest {

    private val policy = slot<ExistingPeriodicWorkPolicy>()
    private val request = slot<PeriodicWorkRequest>()

    /** Schedules over the checks WorkManager already has, and returns the policy used. */
    private fun scheduleOver(vararg scheduled: WorkInfo): ExistingPeriodicWorkPolicy {
        val infos = mockk<ListenableFuture<List<WorkInfo>>> { every { get() } returns scheduled.toList() }
        val workManager = mockk<WorkManager> {
            every { getWorkInfosForUniqueWork(AccessibilityHealthWorker.UNIQUE_NAME) } returns infos
            every {
                enqueueUniquePeriodicWork(AccessibilityHealthWorker.UNIQUE_NAME, capture(policy), capture(request))
            } returns mockk<Operation>()
        }
        AccessibilityHealthWorker.schedule(workManager)
        return policy.captured
    }

    private fun check(workState: WorkInfo.State, period: Long, unit: TimeUnit): WorkInfo = mockk {
        every { state } returns workState
        every { periodicityInfo } returns mockk { every { repeatIntervalMillis } returns unit.toMillis(period) }
    }

    @Test
    fun firstStart_schedulesTheCheckEvery6Hours() {
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, scheduleOver())
        assertEquals(TimeUnit.HOURS.toMillis(6), request.captured.workSpec.intervalDuration)
    }

    @Test
    fun laterStarts_leaveA6HourCheckAlone() {
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, scheduleOver(check(WorkInfo.State.ENQUEUED, 6, TimeUnit.HOURS)))
    }

    @Test
    fun anInstallWithThe15MinuteCheck_movesTo6Hours() {
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, scheduleOver(check(WorkInfo.State.ENQUEUED, 15, TimeUnit.MINUTES)))
        assertEquals(ExistingPeriodicWorkPolicy.UPDATE, scheduleOver(check(WorkInfo.State.RUNNING, 15, TimeUnit.MINUTES)))
    }

    @Test
    fun aCancelledOldCheck_isReplacedNotUpdated() {
        assertEquals(ExistingPeriodicWorkPolicy.KEEP, scheduleOver(check(WorkInfo.State.CANCELLED, 15, TimeUnit.MINUTES)))
    }
}
