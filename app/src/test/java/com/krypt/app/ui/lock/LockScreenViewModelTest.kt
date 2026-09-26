package com.krypt.app.ui.lock

import app.cash.turbine.test
import com.krypt.app.common.Outcome
import com.krypt.app.deeplink.UnlockRequestIssuer
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LockScreenViewModelTest {

    private val issuer = mockk<UnlockRequestIssuer>()

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After
    fun tearDown() = Dispatchers.resetMain()

    @Test
    fun askGuardian_sharesTheIssuedUrlAndMarksRequestSent() = runTest {
        coEvery { issuer.issue("com.example.target") } returns Outcome.ok("krypt://request?v=1")
        val vm = LockScreenViewModel(issuer)
        vm.show("com.example.target")

        vm.shareRequests.test {
            vm.askGuardian()
            assertEquals("krypt://request?v=1", awaitItem())
        }
        assertEquals(RequestStatus.Sent, vm.state.value.request)
    }

    @Test
    fun askGuardian_withoutPinSetup_reportsNotConfiguredAndSharesNothing() = runTest {
        coEvery { issuer.issue(any()) } returns
            Outcome.err(UnlockRequestIssuer.IssueError.NotConfigured)
        val vm = LockScreenViewModel(issuer)
        vm.show("com.example.target")

        vm.shareRequests.test {
            vm.askGuardian()
            expectNoEvents()
        }
        assertEquals(RequestStatus.NotConfigured, vm.state.value.request)
    }

    @Test
    fun askGuardian_whenIssuingThrows_reportsFailed() = runTest {
        coEvery { issuer.issue(any()) } throws IllegalStateException("database unavailable")
        val vm = LockScreenViewModel(issuer)
        vm.show("com.example.target")

        vm.askGuardian()

        assertEquals(RequestStatus.Failed, vm.state.value.request)
    }

    @Test
    fun showingAnotherApp_startsFromACleanState() = runTest {
        coEvery { issuer.issue(any()) } returns Outcome.ok("krypt://request?v=1")
        val vm = LockScreenViewModel(issuer)
        vm.show("com.example.one")
        vm.askGuardian()

        vm.show("com.example.two")

        assertEquals(LockScreenState(lockedPackage = "com.example.two"), vm.state.value)
    }

    @Test
    fun reopeningTheSameApp_keepsThePendingRequestStatus() = runTest {
        coEvery { issuer.issue(any()) } returns Outcome.ok("krypt://request?v=1")
        val vm = LockScreenViewModel(issuer)
        vm.show("com.example.target")
        vm.askGuardian()

        vm.show("com.example.target")

        assertEquals(RequestStatus.Sent, vm.state.value.request)
    }
}
