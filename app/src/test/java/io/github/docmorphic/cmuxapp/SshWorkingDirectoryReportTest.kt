package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test

class SshWorkingDirectoryReportTest {
    private fun report(path: String) = "\u001b]7;file://host$path\u0007"
    private fun SshWorkingDirectoryReport.feed(text: String) = consume(text.toByteArray())

    @Test fun completesBelAndStringTerminatorsAndPreservesLiteralPathPunctuation() {
        val reader = SshWorkingDirectoryReport()
        assertEquals("/tmp/My Project+λ?#", reader.feed(report("/tmp/My%20Project+%CE%BB?#")))
        assertEquals("/other", reader.feed("\u001b]7;FILE://host/other\u001b\\$ "))
        assertEquals("/100%25", reader.feed("\u001b]7;kitty-shell-cwd://host/100%25\u0007"))
        assertEquals("/", reader.feed(report("/")))
    }

    @Test fun everyChunkBoundaryIncludingUtf8AndTerminatorWorksWithoutMutatingInput() {
        val bytes = ("out\u001b[1mbold\u001b[0m\u001b]0;title\u0007" +
            "\u001b]7;file://h/λ 中\u001b\\$ ").toByteArray()
        val original = bytes.copyOf()
        for (size in 1..bytes.size) {
            val reader = SshWorkingDirectoryReport()
            for (start in bytes.indices step size) reader.consume(bytes.copyOfRange(start, minOf(start + size, bytes.size)))
            assertEquals("chunk size $size", "/λ 中", reader.directory)
        }
        assertArrayEquals(original, bytes)
    }

    @Test fun onlyCompleteNewestDirectoryIsPublished() {
        val reader = SshWorkingDirectoryReport()
        assertNull(reader.feed("\u001b]7;file://h/par"))
        assertNull(reader.directory)
        assertEquals("/part", reader.feed("t\u0007"))
        assertNull(reader.feed("more output"))
        assertEquals("/part", reader.directory)
        assertEquals("/b", reader.feed(report("/a") + report("/b")))
        assertNull(SshWorkingDirectoryReport().directory)
    }

    @Test fun malformedUnknownCancelledAndOverflowReportsRetainLastDirectory() {
        val reader = SshWorkingDirectoryReport()
        reader.feed(report("/good"))
        for (value in listOf("\u001b]0;file://h/wrong\u0007", "\u001b]7;relative/path\u0007",
            "\u001b]7;file://hostonly\u0007", report("/bad%00path"), report("/bad\u0000path"),
            "\u001b]7;file://h/cancel\u0018", "\u001b]7;file://h/cancel\u001a",
            report("/" + "x".repeat(100000)))) {
            assertNull(reader.feed(value)); assertEquals("/good", reader.directory)
        }
        assertEquals("/recovered", reader.feed(report("/recovered")))
    }

    @Test fun maximumLengthIsInclusiveAndNextByteOverflows() {
        val reader = SshWorkingDirectoryReport()
        val prefix = "7;file://host/"
        val path = "/" + "x".repeat(SshWorkingDirectoryReport.MAX_REPORT_LENGTH - prefix.length)
        assertEquals(path, reader.feed(report(path)))
        assertNull(reader.feed(report(path + "x")))
        assertEquals(path, reader.directory)
    }

    @Test fun interruptedControlStringsResynchronize() {
        val reader = SshWorkingDirectoryReport()
        assertEquals("/new", reader.feed("\u001b]7;file://h/old" + report("/new")))
        assertNull(reader.feed("\u001b]7;file://h/no\u001bx\u0007"))
        assertEquals("/after", reader.feed("\u001b\u001b" + report("/after")))
    }

    @Test fun malformedPercentEncodingFallsBackToWholeLiteralPath() {
        for (path in listOf("/x%20bad%", "/%GG", "/%FF", "/%C0%AF"))
            assertEquals(path, SshWorkingDirectoryReport.path("file://host$path"))
        assertNull(SshWorkingDirectoryReport.path("https://host/path"))
        assertEquals("/λ+%", SshWorkingDirectoryReport.path("file:///λ+%25"))
    }
}
