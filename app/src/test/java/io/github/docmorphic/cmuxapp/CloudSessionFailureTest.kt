package io.github.docmorphic.cmuxapp

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class CloudSessionFailureTest {
    @Test fun localDiagnosticsRemainSeparateFromUserCopyAtEveryStage() {
        for (kind in CloudFailureKind.entries.filter { it != CloudFailureKind.SIGNED_OUT }) {
            val failure = CloudSessionFailure.classify(IOException("diagnostic-only-private-value"), kind)
            assertEquals(kind, failure.kind)
            assertEquals("diagnostic-only-private-value", failure.detail)
            assertFalse(failure.userMessage.contains("diagnostic-only"))
            assertEquals(failure.userMessage, failure.userReason)
        }
    }
    @Test fun serverActionSurvivesConnectionContextWithoutChangingRetryClassification() {
        val failure = CloudSessionFailure.classify(CloudApiFailure(429, "Wait for capacity to become available.", "trace detail"), CloudFailureKind.LINK)
        assertEquals(CloudFailureKind.CONTROL_PLANE, failure.kind)
        assertEquals("Wait for capacity to become available.", failure.userReason)
        assertTrue(failure.retryable)
        assertFalse(failure.userMessage.contains("trace detail"))
    }
    @Test fun expiredSessionAlwaysWinsOverTunnelAndServerCopy() {
        for (exception in listOf(CloudNotSignedIn(), CloudApiFailure(401, "server detail", "trace detail"))) {
            val failure = CloudSessionFailure.classify(exception, CloudFailureKind.TUNNEL)
            assertTrue(failure.signedOut); assertFalse(failure.retryable)
            assertTrue(failure.userMessage.contains("Sign in again"))
            assertEquals(failure.userMessage, failure.userReason)
        }
    }
    @Test fun emptyServerActionFallsBackToStableMessage() {
        val failure = CloudSessionFailure.classify(CloudApiFailure(403, " ", "private trace"))
        assertEquals(failure.userMessage, failure.userReason); assertFalse(failure.retryable)
    }
}
