package com.krypt.app.worker

import org.junit.Assert.assertEquals
import org.junit.Test

/** What the health check makes of Krypt's accessibility service, and so which alert it posts (Amendment 10). */
class ServiceHealthTest {

    @Test
    fun connected_isRunning() {
        assertEquals(ServiceHealth.RUNNING, serviceHealth(enabledInSettings = true, bound = true, startingHere = false))
        // Still connected as it is being switched off: the service reports that itself.
        assertEquals(ServiceHealth.RUNNING, serviceHealth(enabledInSettings = false, bound = true, startingHere = false))
    }

    @Test
    fun switchedOffInSettings_isOff() {
        assertEquals(ServiceHealth.OFF, serviceHealth(enabledInSettings = false, bound = false, startingHere = false))
    }

    @Test
    fun switchedOnButNotRunning_isStopped_soTheAlertSaysToSwitchItOffAndOn() {
        // What Android leaves when the phone stops Krypt and nothing starts it again: Settings still shows it on.
        assertEquals(ServiceHealth.STOPPED, serviceHealth(enabledInSettings = true, bound = false, startingHere = false))
    }

    @Test
    fun createdHereButNotConnectedYet_isStarting_andLeftToTheWatchdog() {
        assertEquals(ServiceHealth.STARTING, serviceHealth(enabledInSettings = true, bound = false, startingHere = true))
    }
}
