package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class ReportingCloudTerminalLinkTest {
    private class Link : CloudTerminalLink {
        var failure: Throwable? = null
        var accepts = true
        private fun checkFailure() { failure?.let { throw it } }
        override fun attach(terminal: String): Long { checkFailure(); return 7 }
        override fun detach(attachment: Long) { checkFailure() }
        override fun send(attachment: Long, bytes: ByteArray): Boolean { checkFailure(); return accepts }
        override fun resize(attachment: Long, columns: Int, rows: Int): Long { checkFailure(); return if (accepts) 1 else 0 }
        override suspend fun output(attachment: Long): CloudTerminalOutput? { checkFailure(); return null }
    }
    @Test fun attachAndStreamResultsReachMachineStatusWithoutDiagnosticsInUserCopy() = runTest {
        val source = Link(); val results = mutableListOf<CloudSessionFailure?>()
        val link = ReportingCloudTerminalLink(source, results::add)
        source.failure = IOException("private diagnostic")
        assertTrue(runCatching { link.attach("terminal") }.isFailure)
        assertEquals(CloudFailureKind.LINK, results.single()!!.kind)
        assertFalse(results.single()!!.userReason.contains("private diagnostic"))
        source.failure = null; assertEquals(7L, link.attach("terminal")); assertNull(results.last())
        val count = results.size
        link.send(7, byteArrayOf(1)); link.resize(7, 80, 24); link.output(7); link.detach(7)
        assertEquals(count, results.size)
        source.failure = CloudNotSignedIn()
        assertTrue(runCatching { link.output(7) }.isFailure)
        assertTrue(results.last()!!.signedOut)
    }
    @Test fun rejectedInputAndResizeAreFailuresButCancellationAndCleanupDoNotChangeStatus() = runTest {
        val source = Link(); val results = mutableListOf<CloudSessionFailure?>()
        val link = ReportingCloudTerminalLink(source, results::add)
        source.accepts = false
        assertFalse(link.send(7, byteArrayOf(1))); assertEquals(0L, link.resize(7, 80, 24))
        assertEquals(2, results.size)
        source.failure = CancellationException("superseded")
        assertTrue(runCatching { link.attach("old") }.isFailure)
        assertTrue(runCatching { link.output(7) }.isFailure)
        assertEquals(2, results.size)
        source.failure = UnsatisfiedLinkError("private runtime diagnostic")
        assertTrue(runCatching { link.attach("old") }.isFailure)
        assertEquals("Cloud native runtime is unavailable", results.last()!!.detail)
        val count = results.size
        assertTrue(runCatching { link.detach(7) }.isFailure)
        assertEquals(count, results.size)
    }
}
