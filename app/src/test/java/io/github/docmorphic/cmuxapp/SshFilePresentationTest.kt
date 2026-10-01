package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class SshFilePresentationTest {
    private fun entry(name: String, folder: Boolean = false) = SshFileEntry(name, folder, false, 0, 0)

    @Test fun foldersComeFirstAndNumberedFilesReadInNumericOrder() {
        val files = listOf(entry("file10.txt"), entry("file2.txt"), entry("file1.txt"), entry("z10", true), entry("z2", true))
        assertEquals(listOf("z2", "z10", "file1.txt", "file2.txt", "file10.txt"), files.sortedWith(SshFilePresentation.order(Locale.US)).map { it.name })
    }

    @Test fun largeNumericRunsAndLeadingZerosHaveStableTotalOrder() {
        val names = listOf("a999999999999999999999999999", "a1000000000000000000000000000", "a02", "A2", "a2", "a0", "a00", "a")
        val order = SshFilePresentation.order(Locale.US)
        val sorted = names.map(::entry).sortedWith(order)
        assertEquals(listOf("a", "a0", "a00", "A2", "a02", "a2", "a999999999999999999999999999", "a1000000000000000000000000000"), sorted.map { it.name })
        sorted.forEachIndexed { index, a -> sorted.drop(index + 1).forEach { b ->
            assertTrue(order.compare(a, b) < 0); assertTrue(order.compare(b, a) > 0)
        } }
    }

    @Test fun textCollationUsesRequestedLocale() {
        val files = listOf(entry("z2"), entry("ä2"), entry("a2"))
        assertEquals(listOf("a2", "ä2", "z2"), files.sortedWith(SshFilePresentation.order(Locale.GERMAN)).map { it.name })
        assertEquals(listOf("a2", "z2", "ä2"), files.sortedWith(SshFilePresentation.order(Locale("sv"))).map { it.name })
    }

    @Test fun photoBasenamesUseLocalTimeAndOneBasedBatchSuffixes() {
        val now = Date(0)
        val utc = TimeZone.getTimeZone("UTC")
        assertEquals("Photo-19700101-000000.png", SshFilePresentation.photoName(0, "PNG", now, utc))
        assertEquals("Photo-19700101-000000-2.mov", SshFilePresentation.photoName(1, "mov", now, utc))
        assertEquals("Photo-19700101-053000-12.jpg", SshFilePresentation.photoName(11, "jpg", now, TimeZone.getTimeZone("GMT+05:30")))
        assertTrue(runCatching { SshFilePresentation.photoName(0, "../png", now, utc) }.isFailure)
    }
}
