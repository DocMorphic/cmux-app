package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxWire
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.EOFException
import java.io.IOException
import java.net.*
import java.nio.file.AccessDeniedException
import java.nio.file.Files
import java.util.zip.ZipFile
import javax.net.ssl.SSLHandshakeException

class DiagnosticFailureTest {
    @Test fun upstreamCodesAreStableAndUnique() {
        assertEquals((0..30).toList() + 255, DiagnosticFailure.entries.map { it.code })
        assertEquals(13, DiagnosticFailure.IDENTITY_MISMATCH.code)
        assertEquals(21, DiagnosticFailure.TRANSPORT_IDLE_TIMED_OUT.code)
        assertEquals(255, DiagnosticFailure.UNKNOWN.code)
    }
    @Test fun typedNetworkFailuresExcludeMessagesAndAmbiguousErrorsRemainUnknown() {
        val secret = "account@example.test/token/private-host"
        val expected = listOf(
            UnknownHostException(secret) to DiagnosticFailure.DNS_FAILED,
            NoRouteToHostException(secret) to DiagnosticFailure.HOST_UNREACHABLE,
            ConnectException(secret) to DiagnosticFailure.CONNECTION_REFUSED,
            SocketTimeoutException(secret) to DiagnosticFailure.TIMED_OUT,
            SSLHandshakeException(secret) to DiagnosticFailure.SECURE_CHANNEL_FAILED,
            AccessDeniedException(secret) to DiagnosticFailure.PERMISSION_DENIED,
            SecurityException(secret) to DiagnosticFailure.PERMISSION_DENIED,
            ProtocolException(secret) to DiagnosticFailure.PROTOCOL_VIOLATION,
            EOFException(secret) to DiagnosticFailure.CONNECTION_CLOSED,
            IOException("irx:identity-mismatch $secret") to DiagnosticFailure.UNKNOWN,
            SocketException("Connection reset $secret") to DiagnosticFailure.UNKNOWN,
            MobileRpcException("identity_mismatch", secret) to DiagnosticFailure.UNKNOWN,
            IllegalStateException(secret, UnknownHostException(secret)) to DiagnosticFailure.UNKNOWN)
        val ring = DebugLogBuffer()
        for ((error, category) in expected) {
            assertEquals(category, DiagnosticFailure.classify(error))
            ring.finish(ring.begin(DebugOperation.RPC_CONNECT), debugOutcome(error), DiagnosticFailure.classify(error))
        }
        assertFalse(ring.snapshot().contains(secret))
        assertTrue(ring.snapshot().contains("failure=6:DNS_FAILED"))
    }
    @Test fun typedAdmissionCodesDistinguishIdentityProtocolTimeoutAndLifecycle() {
        val expected = listOf(DiagnosticFailure.ADMISSION_DENIED, DiagnosticFailure.ADMISSION_DENIED,
            DiagnosticFailure.ADMISSION_DENIED, DiagnosticFailure.IDENTITY_MISMATCH,
            DiagnosticFailure.PROTOCOL_VIOLATION, DiagnosticFailure.PROTOCOL_VIOLATION,
            DiagnosticFailure.TIMED_OUT, DiagnosticFailure.SUPERSEDED, DiagnosticFailure.CANCELLED,
            DiagnosticFailure.CONNECTION_CLOSED, DiagnosticFailure.TRANSPORT_IDLE_TIMED_OUT,
            DiagnosticFailure.CONNECTION_CLOSED)
        assertEquals(expected, IrxWire.CloseCode.entries.map { DiagnosticFailure.classify(IrxWire.AdmissionRejected(it)) })
    }
    @Test fun timeoutAndCallerCancellationAreSeparateAndTraceRethrowsOriginal() = runBlocking {
        val timeout = runCatching { withTimeout(1) { awaitCancellation() } }.exceptionOrNull()!!
        assertEquals(DiagnosticFailure.TIMED_OUT, DiagnosticFailure.classify(timeout))
        val cancelled = CancellationException("private")
        assertEquals(DiagnosticFailure.CANCELLED, DiagnosticFailure.classify(cancelled))
        val original = UnknownHostException("private.example")
        assertSame(original, runCatching { MobileDebugLog.trace(DebugOperation.RPC_CONNECT) { throw original } }.exceptionOrNull())
        assertTrue(MobileDebugLog.snapshot().contains("failure=6:DNS_FAILED"))
        assertFalse(MobileDebugLog.snapshot().contains("private.example"))
    }
    @Test fun exportedFailuresStayDistinctWhenCoalescingAndSurviveReopen() = runBlocking {
        val root = Files.createTempDirectory("failure-diagnostics").toFile()
        val files = DiagnosticFiles(root.resolve("logs"), root.resolve("exports"), "fixture")
        try {
            val base = DiagnosticRecord(DebugOperation.RPC_CONNECT, DebugOutcome.IO_ERROR, DiagnosticRole.APP, 100, 100, 1)
            files.record(listOf(base.copy(failure = DiagnosticFailure.DNS_FAILED),
                base.copy(failure = DiagnosticFailure.DNS_FAILED), base.copy(failure = DiagnosticFailure.SECURE_CHANNEL_FAILED)))
            val recorder = DiagnosticRecorder(files, 1, DiagnosticRole.APP)
            try {
                recorder.record(DebugOperation.RPC_CONNECT, DebugOutcome.IO_ERROR, failure = DiagnosticFailure.IDENTITY_MISMATCH)
                recorder.flush()
            } finally { recorder.shutdown() }
            val reopened = DiagnosticFiles(root.resolve("logs"), root.resolve("exports"), "reopened")
            ZipFile(reopened.export()).use { zip ->
                assertEquals(2, zip.size())
                val text = zip.getInputStream(zip.getEntry("cmux-diagnostics/networking.log")).bufferedReader().use { it.readText() }
                assertTrue(text.contains("count=2 failure=6:DNS_FAILED"))
                assertTrue(text.contains("count=1 failure=7:SECURE_CHANNEL_FAILED"))
                assertTrue(text.contains("failure=13:IDENTITY_MISMATCH"))
                assertEquals(3, text.lineSequence().count { "RPC_CONNECT" in it })
            }
        } finally { root.deleteRecursively() }
    }
}
