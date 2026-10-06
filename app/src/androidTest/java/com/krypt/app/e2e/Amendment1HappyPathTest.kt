package com.krypt.app.e2e

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.krypt.app.common.Clock
import com.krypt.app.common.Outcome
import com.krypt.app.data.KryptDatabase
import com.krypt.app.data.LockerSessionStore
import com.krypt.app.data.OutstandingRequestRepository
import com.krypt.app.data.UnlockGrantDao
import com.krypt.app.data.daily.DailyAccess
import com.krypt.app.data.daily.DailyAllowanceMeter
import com.krypt.app.deeplink.AccessChoice
import com.krypt.app.deeplink.ApprovalLinkBuilder
import com.krypt.app.deeplink.Base64Url
import com.krypt.app.deeplink.UnlockRequest
import com.krypt.app.deeplink.UnlockRequestBuilder
import com.krypt.app.deeplink.UnlockRequestIssuer
import com.krypt.app.deeplink.UnlockRequestParser
import com.krypt.app.guardian.GuardianPinValidator
import com.krypt.app.security.MasterKeyStore
import com.krypt.app.subject.ApprovalTrampolineActivity
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import javax.inject.Inject

/**
 * The whole unlock round trip on one device, through the real Room
 * database and the silent [ApprovalTrampolineActivity]:
 *   Subject issues a request (UnlockRequestIssuer saves the pending row)
 *   -> Guardian parses it, checks the PIN, builds the approval
 *   -> Subject taps the approval link -> grant recorded, app unlocked.
 * Plus the ways an approval must be refused.
 */
@RunWith(AndroidJUnit4::class)
@HiltAndroidTest
class Amendment1HappyPathTest {

    @get:Rule val hilt = HiltAndroidRule(this)

    @Inject lateinit var state: TestState
    @Inject lateinit var issuer: UnlockRequestIssuer
    @Inject lateinit var requestParser: UnlockRequestParser
    @Inject lateinit var requestBuilder: UnlockRequestBuilder
    @Inject lateinit var validator: GuardianPinValidator
    @Inject lateinit var approvalBuilder: ApprovalLinkBuilder
    @Inject lateinit var outstandingRepo: OutstandingRequestRepository
    @Inject lateinit var grantDao: UnlockGrantDao
    @Inject lateinit var sessionStore: LockerSessionStore
    @Inject lateinit var masterKeyStore: MasterKeyStore
    @Inject lateinit var clock: Clock
    @Inject lateinit var db: KryptDatabase
    @Inject lateinit var dailyMeter: DailyAllowanceMeter

    private val targetPackage = "com.example.target"

    @Before
    fun setUp() {
        hilt.inject()
        state.reset(onboardingComplete = true, pinConfigured = true)
    }

    /** Subject asks; the Guardian approves with [pin], allowing [access]. */
    private fun approval(
        pin: String = TEST_PIN,
        access: AccessChoice = AccessChoice.OneTime(20),
    ): Pair<String, UnlockRequest> = runBlocking {
        val requestUrl = (issuer.issue(targetPackage) as Outcome.Ok).value
        val request = (requestParser.parse(requestUrl, clock.nowSeconds()) as Outcome.Ok).value
        val key = validator.validate(pin.toCharArray(), request, request.kdfIterations)
        assertTrue("Guardian must accept the right PIN: $key", key is Outcome.Ok)
        approvalBuilder.build((key as Outcome.Ok).value, request, access) to request
    }

    private fun tap(approvalUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(approvalUrl), targetContext, ApprovalTrampolineActivity::class.java)
        ActivityScenario.launch<ApprovalTrampolineActivity>(intent).use { scenario ->
            val deadline = System.currentTimeMillis() + 10_000
            while (scenario.state != Lifecycle.State.DESTROYED && System.currentTimeMillis() < deadline) {
                Thread.sleep(50)
            }
        }
    }

    /** The `unlock_grants` rows: the record of which request authorised each grant. */
    private fun activeGrants() = runBlocking { grantDao.observeActiveAt(clock.nowMs()).first() }

    @Test
    fun requestApproveTap_unlocksTheApp() {
        val (approvalUrl, request) = approval()

        tap(approvalUrl)

        assertTrue("request consumed", runBlocking { outstandingRepo.findById(request.requestId)!!.consumed })
        assertEquals(listOf(targetPackage), activeGrants().map { it.targetPackage })
        assertTrue(sessionStore.isUnlockedNow(targetPackage))
    }

    @Test
    fun wrongPin_isRejectedByTheGuardian() = runBlocking {
        val requestUrl = (issuer.issue(targetPackage) as Outcome.Ok).value
        val request = (requestParser.parse(requestUrl, clock.nowSeconds()) as Outcome.Ok).value

        val result = validator.validate("0000".toCharArray(), request, request.kdfIterations)

        assertTrue(result is Outcome.Err)
    }

    @Test
    fun replayedApproval_grantsNothingMore() {
        val (approvalUrl, _) = approval()
        tap(approvalUrl)
        sessionStore.expireAll()

        tap(approvalUrl)

        assertEquals(1, activeGrants().size)
        assertFalse("a replay must not unlock again", sessionStore.isUnlockedNow(targetPackage))
    }

    @Test
    fun approvalForARequestThisDeviceNeverIssued_grantsNothing() {
        val masterKey = runBlocking { state.setUpPin() }
        val salt = runBlocking { masterKeyStore.loadSalt()!! }
        val pinProof = runBlocking { masterKeyStore.loadPinProof()!! }
        val (_, foreignRequest) = requestBuilder.build(salt, pinProof, targetPackage)
        val approvalUrl = approvalBuilder.build(masterKey, foreignRequest)

        tap(approvalUrl)

        assertTrue(activeGrants().isEmpty())
        assertFalse(sessionStore.isUnlockedNow(targetPackage))
    }

    @Test
    fun tamperedApproval_grantsNothingAndLeavesTheRequestOpen() {
        val (approvalUrl, request) = approval()
        val tampered = approvalUrl.replace(Regex("data=[^&]+")) { match ->
            val bytes = Base64Url.decode(match.value.substringAfter("data="))
            bytes[bytes.size / 2] = (bytes[bytes.size / 2].toInt() xor 0x01).toByte()
            "data=${Base64Url.encode(bytes)}"
        }

        tap(tampered)

        assertTrue(activeGrants().isEmpty())
        assertFalse(runBlocking { outstandingRepo.findById(request.requestId)!!.consumed })
    }

    @Test
    fun everyDayApproval_savesTheRule_andReplayingItChangesNothing() {
        val (approvalUrl, request) = approval(access = AccessChoice.EveryDay(minutes = 60, days = 7))

        tap(approvalUrl)

        assertTrue(dailyMeter.access(targetPackage) is DailyAccess.Available)
        assertTrue("no one-time grant", activeGrants().isEmpty())
        val rules = runBlocking { db.dailyAllowanceDao().all() }
        assertEquals(listOf(60 to 6L), rules.map { it.minutesPerDay to (it.lastEpochDay - it.firstEpochDay) })
        assertTrue(runBlocking { outstandingRepo.findById(request.requestId)!!.consumed })

        tap(approvalUrl)

        assertEquals(rules, runBlocking { db.dailyAllowanceDao().all() })
    }

    @Test
    fun grantSurvivesAProcessRestart() {
        val (approvalUrl, _) = approval()
        tap(approvalUrl)

        // A fresh store, as in the next process after this one died.
        assertNotNull("no boot count on this device, so no grant can survive a restart", clock.bootCount())
        val restarted = LockerSessionStore(targetContext, clock).apply { restore() }

        assertTrue(restarted.isUnlockedNow(targetPackage))
    }
}
