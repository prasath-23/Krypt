package com.krypt.app.deeplink

import com.krypt.app.common.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class KryptLinksTest {

    private val clock = FixedClock(nowMs = 1_700_000_000_000L)
    private val requestUrl = UnlockRequestBuilder(clock).build(
        setupSalt = ByteArray(UnlockRequest.SALT_BYTES) { it.toByte() },
        pinProof = ByteArray(UnlockRequest.PIN_PROOF_BYTES) { (it * 7).toByte() },
        targetPackage = "com.whatsapp",
        kdfIterations = 450_000,
    ).first

    @Test
    fun findsTheRequestLinkInAWholeCopiedMessage() {
        val message = "Krypt unlock request for WhatsApp.\n" +
            "Tap the link to review it. If it doesn't open, copy this whole message.\n" +
            requestUrl

        assertEquals(requestUrl, KryptLinks.find(message))
    }

    @Test
    fun linkFoundInAMessage_parsesCleanly() {
        val found = KryptLinks.find("see: $requestUrl thanks")!!

        val parsed = UnlockRequestParser().parse(found, clock.nowSeconds())

        assertTrue("got $parsed", parsed is Outcome.Ok)
        assertEquals(450_000, (parsed as Outcome.Ok).value.kdfIterations)
    }

    @Test
    fun findsAnApprovalLink() {
        val approval = "krypt://approve?v=1&req=12345678-1234-4abc-8def-123456789abc&data=AbC-_d&iat=1700000000"

        assertEquals(approval, KryptLinks.find("Krypt approval for Chrome.\n$approval"))
        assertTrue(KryptLinks.isApproval(approval))
        assertFalse(KryptLinks.isApproval(requestUrl))
    }

    @Test
    fun trailingFullStopIsNotPartOfTheLink() {
        assertEquals(requestUrl, KryptLinks.find("Here it is: $requestUrl."))
    }

    @Test
    fun textWithoutAKryptLink_findsNothing() {
        assertNull(KryptLinks.find(null))
        assertNull(KryptLinks.find(""))
        assertNull(KryptLinks.find("hello, see https://example.com/request?v=1"))
        assertNull(KryptLinks.find("krypt://request"))
    }

    @Test
    fun legacyPairingLinks_areNotKryptLinksAnyMore() {
        assertNull(KryptLinks.find("krypt://pair?v=1&sub=abc"))
        assertNull(KryptLinks.find("krypt://paired?v=1&mac=abc"))
    }
}
