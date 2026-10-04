package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NativeSupportInformationTest {
    private fun report(account: String? = null, model: String = "Pixel") = NativeSupportInformation(account,
        "team", "app.debug", "dev", "0.2.0-debug", "2", "17", model, "connected", "iroh",
        "abc123def456+", Instant.parse("2026-10-04T00:00:00Z")).report()
    @Test fun missingIdentifiersStayUnavailableAndReportIsAndroidAndUtc() {
        val value = report()
        assertTrue(value.contains("Account ID: <unavailable>"))
        assertTrue(value.contains("Install ID: <unavailable>\nDevice ID: <unavailable>"))
        assertTrue(value.contains("Android Version: 17"))
        assertTrue(value.endsWith("Reported At (UTC): 2026-10-04T00:00:00Z"))
        assertFalse(value.contains("iOS Version"))
    }
    @Test fun valuesCannotInjectExtraFieldsOrUnboundedClipboardText() {
        val value = report("user\n\r\t\u0000", "x".repeat(10_000))
        assertTrue(value.contains("Account ID: user\n"))
        assertEquals(15, value.lines().size)
        assertEquals(512, value.lineSequence().single { it.startsWith("Device Model:") }.substringAfter(": ").length)
        assertTrue(value.length < 2000)
    }
}
