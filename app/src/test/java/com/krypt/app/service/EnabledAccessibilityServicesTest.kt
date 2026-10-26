package com.krypt.app.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Reading the switched-on services, which tells "switched off" from a passing unbind (Amendment 5). */
class EnabledAccessibilityServicesTest {

    private fun includes(setting: String?) = EnabledAccessibilityServices.includes(setting, PKG, CLS)

    @Test
    fun fullClassName_isIncluded() {
        assertTrue(includes("$PKG/$CLS"))
    }

    @Test
    fun classNameRelativeToItsPackage_isIncluded() {
        assertTrue(includes("$PKG/.service.AppLockerAccessibilityService"))
    }

    @Test
    fun amongOtherServices_isIncluded() {
        assertTrue(includes("com.google.android.marvin.talkback/.TalkBackService:$PKG/$CLS:com.example/.Other"))
    }

    @Test
    fun switchedOff_isNotIncluded() {
        // What Settings leaves when Krypt is switched off and another service stays on.
        assertFalse(includes("com.google.android.marvin.talkback/.TalkBackService"))
        assertFalse(includes(""))
        assertFalse(includes(null))
    }

    @Test
    fun sameClassInAnotherPackage_isNotIncluded() {
        assertFalse(includes("com.example/$CLS"))
        assertFalse(includes("com.example/.service.AppLockerAccessibilityService"))
    }

    @Test
    fun malformedEntries_areSkipped() {
        assertFalse(includes(PKG))
        assertFalse(includes("$PKG/"))
        assertFalse(includes("/$CLS"))
        assertTrue(includes("nonsense:$PKG/:$PKG/$CLS"))
    }

    private companion object {
        const val PKG = "com.krypt.app"
        const val CLS = "com.krypt.app.service.AppLockerAccessibilityService"
    }
}
