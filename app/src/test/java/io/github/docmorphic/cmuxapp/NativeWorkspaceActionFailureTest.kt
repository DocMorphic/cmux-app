package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class NativeWorkspaceActionFailureTest {
    @Test fun rejectionIsClassifiedWithoutPublishingPeerText() {
        recordWorkspaceActionFailure(MobileRpcException("private-code", "private workspace name and terminal content"))
        val log = MobileDebugLog.snapshot()
        assertTrue(log.contains("RPC_WORKSPACE REMOTE_ERROR"))
        assertFalse(log.contains("private-code"))
        assertFalse(log.contains("private workspace name"))
    }

    @Test fun ownerCancellationPropagatesUnchanged() {
        val cancelled = CancellationException("Retired owner")
        assertSame(cancelled, runCatching { recordWorkspaceActionFailure(cancelled) }.exceptionOrNull())
    }
}
