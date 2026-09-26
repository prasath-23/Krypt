package com.krypt.app.manifest

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * FR-005 / SC-008 hard gate.
 *
 * Parses the real app/src/main/AndroidManifest.xml and asserts:
 *   1. NO network-adjacent permission is declared (INTERNET, ACCESS_NETWORK_STATE,
 *      ACCESS_WIFI_STATE, CHANGE_WIFI_STATE, CHANGE_NETWORK_STATE).
 *   2. The permissions Krypt *requires* for its legitimate behaviour ARE present
 *      (SYSTEM_ALERT_WINDOW, POST_NOTIFICATIONS, QUERY_ALL_PACKAGES,
 *      RECEIVE_BOOT_COMPLETED).
 *
 * This test runs under `./gradlew :app:testDebugUnitTest` (JVM unit, no Android
 * framework needed) and is wired into `./gradlew check` so every build — local
 * and CI — gates on it. Any future PR that accidentally adds INTERNET will fail
 * here with a message pointing back at FR-005 in the feature spec.
 */
class ManifestAuditTest {

    private val manifestPath = "src/main/AndroidManifest.xml"

    @Test
    fun manifestDoesNotDeclareNetworkPermissions() {
        val declared = declaredPermissions()

        // Each entry here is a Krypt-disallowed permission. Adding any of them to
        // the manifest would violate FR-005 and break Krypt's air-gap guarantee.
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
            "android.permission.CHANGE_NETWORK_STATE"
        )

        for (perm in forbidden) {
            assertFalse(
                "Manifest declares $perm. Krypt MUST NOT declare network-adjacent " +
                    "permissions (FR-005 / SC-008). See " +
                    "polaris-specs/001-krypt-app-locker/spec.md section 5.",
                perm in declared
            )
        }
    }

    @Test
    fun manifestDeclaresRequiredPermissions() {
        val declared = declaredPermissions()

        // Each entry here is Krypt-required for its documented runtime behaviour.
        // If any of these ever disappears, a feature breaks silently and this
        // test is the canary.
        val required = listOf(
            "android.permission.SYSTEM_ALERT_WINDOW"       to "FR-001 (start the lock screen from the background)",
            "android.permission.POST_NOTIFICATIONS"        to "FR-004 (Security-Alerts channel)",
            "android.permission.VIBRATE"                   to "FR-021 (unlock haptic)",
            "android.permission.QUERY_ALL_PACKAGES"        to "enumerate apps for lock list (research.md R6)",
            "android.permission.RECEIVE_BOOT_COMPLETED"    to "rebind Accessibility after reboot",
            "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS" to "FR-010 (Doze survival)",
            "android.permission.FOREGROUND_SERVICE"        to "FR-010 (watchdog FGS, WP16)"
        )

        for ((perm, reason) in required) {
            assertTrue(
                "Manifest is missing $perm (needed for $reason). " +
                    "Re-add or update this test.",
                perm in declared
            )
        }
    }

    @Test
    fun manifestOnlyHandlesRequestAndApprovalLinks() {
        // The legacy krypt://pair and krypt://paired screens could overwrite
        // this device's PBKDF2 settings (Amendment 2 removed them).
        val kryptHosts = attributeValues("data", "host")
        assertEquals(setOf("request", "approve"), kryptHosts)
    }

    // --------------------------------------------------------------------

    private fun declaredPermissions(): Set<String> = attributeValues("uses-permission", "name")

    private fun attributeValues(tag: String, attribute: String): Set<String> {
        val manifestFile = File(manifestPath)
        if (!manifestFile.exists()) {
            fail(
                "Could not locate AndroidManifest.xml at $manifestPath " +
                    "(cwd=${File(".").absolutePath}). Gradle runs unit tests " +
                    "with the module directory as cwd; check that path."
            )
        }

        val doc = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
        }.newDocumentBuilder().parse(manifestFile)

        val androidNs = "http://schemas.android.com/apk/res/android"
        val toolsNs = "http://schemas.android.com/tools"
        val nodes = doc.getElementsByTagName(tag)

        val out = mutableSetOf<String>()
        for (i in 0 until nodes.length) {
            val el = nodes.item(i) as Element
            // tools:node="remove" drops the element from the merged manifest.
            if (el.getAttributeNS(toolsNs, "node") == "remove") continue
            val value = el.getAttributeNS(androidNs, attribute)
            if (value.isNotBlank()) out.add(value)
        }
        return out
    }
}
