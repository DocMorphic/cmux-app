package io.github.docmorphic.cmuxapp

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.util.Locale
import java.util.TimeZone

class SshImageNamesTest {
    @Test fun basenameUsesUtcAndAsciiRegardlessOfDeviceLocale() {
        val locale = Locale.getDefault(); val zone = TimeZone.getDefault()
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"))
            TimeZone.setDefault(TimeZone.getTimeZone("Pacific/Auckland"))
            assertEquals("20261003-000102-003.jpg", SshImageNames.fileName("JPG", Instant.parse("2026-10-03T00:01:02.003Z")))
            assertEquals("20261003-000102-003.gif", SshImageNames.fileName("GIF", Instant.parse("2026-10-03T00:01:02.003Z")))
        } finally { Locale.setDefault(locale); TimeZone.setDefault(zone) }
    }
    @Test fun unsafeOrOversizedExtensionsFallBackToPng() {
        for (format in listOf("", "../../sh", "jpegxx", "png\n", "j pg", "π", "İ")) {
            assertEquals("19700101-000000-000.png", SshImageNames.fileName(format, Instant.EPOCH))
        }
        assertEquals("19700101-000000-000.heic", SshImageNames.fileName("HEIC", Instant.EPOCH))
    }
}
