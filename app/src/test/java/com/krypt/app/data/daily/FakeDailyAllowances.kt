package com.krypt.app.data.daily

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** [DailyAllowances] whose answers a test sets; [ended] records [end] calls. */
class FakeDailyAllowances : DailyAllowances {
    private val access = HashMap<String, DailyAccess>()
    val ended = mutableListOf<String>()

    private val _changes = MutableStateFlow(0L)
    override val changes: StateFlow<Long> = _changes

    fun set(pkg: String, daily: DailyAccess) {
        access[pkg] = daily
        _changes.value = _changes.value + 1
    }

    override fun access(pkg: String): DailyAccess = access[pkg] ?: DailyAccess.None

    override suspend fun end(pkg: String) {
        ended += pkg
        set(pkg, DailyAccess.None)
    }
}
