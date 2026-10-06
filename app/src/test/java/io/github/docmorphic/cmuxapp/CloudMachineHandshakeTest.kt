package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class CloudMachineHandshakeTest {
    private class Session : AutoCloseable {
        val closes = AtomicInteger(); val closed = CountDownLatch(1)
        override fun close() { closes.incrementAndGet(); closed.countDown() }
    }
    private class Api(var endpoint: CloudAttachEndpoint = endpoint()) : CloudTerminalService {
        var reads = 0; var approvals = 0
        var approval: suspend () -> Boolean = { true }
        override suspend fun attach(id: String, fingerprint: String, capabilities: List<String>): CloudAttachEndpoint {
            reads++; assertEquals("vm", id); assertEquals("fingerprint", fingerprint); return endpoint
        }
        override suspend fun approve(id: String, invitationId: String): Boolean {
            approvals++; assertEquals("invitation", invitationId); return approval()
        }
    }
    @Test fun publishesOnlyAfterNativeConnectAndApprovalBothSucceed() = runTest {
        val api = Api(); val session = Session()
        val link = CloudMachineHandshake(this, api, "vm", "fingerprint", { true }, { session }, StandardTestDispatcher(testScheduler))
        try {
            link.start(); runCurrent()
            assertEquals(CloudLinkPhase.CONNECTING, link.state.value.phase)
            assertEquals(0, api.approvals)
            advanceTimeBy(2000); runCurrent()
            assertSame(session, link.awaitSession())
            assertEquals(CloudLinkPhase.READY, link.state.value.phase)
            assertEquals(1, api.reads); assertEquals(1, api.approvals)
        } finally { link.close(); runCurrent() }
        assertEquals(1, session.closes.get())
    }
    @Test fun explicitCarrierTrustSkipsApprovalButAbsentInvitationDoesNotInventTrust() = runTest {
        for (trusted in listOf(true, false)) {
            val api = Api(CloudAttachEndpoint("ws://fixture", "session", null, trusted))
            val session = Session()
            val link = CloudMachineHandshake(this, api, "vm", "fingerprint", { true }, {
                assertEquals(trusted, it.trustedCarrier); session
            }, StandardTestDispatcher(testScheduler))
            try {
                link.start(); runCurrent(); assertSame(session, link.awaitSession()); assertEquals(0, api.approvals)
            } finally { link.close(); runCurrent() }
            assertEquals(1, session.closes.get())
        }
    }
    @Test fun approvalFailureReturnsBeforeBlockingConnectAndClosesItsLateResult() = runTest {
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val session = Session()
        val api = Api().apply { approval = { throw CloudApiFailure(401, null, "Expired login") } }
        val link = CloudMachineHandshake(this, api, "vm", "fingerprint", { true }, {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); session
        }, executor)
        try {
            link.start(); runCurrent(); assertTrue(entered.await(1, TimeUnit.SECONDS))
            advanceTimeBy(2000); runCurrent()
            assertEquals(CloudLinkPhase.FAILED, link.state.value.phase)
            assertTrue(link.state.value.failure!!.signedOut)
            assertTrue(runCatching { link.awaitSession() }.isFailure)
            assertEquals(0, session.closes.get())
            release.countDown(); assertTrue(session.closed.await(1, TimeUnit.SECONDS))
            assertEquals(1, session.closes.get())
        } finally { release.countDown(); link.close(); executor.close() }
    }
    @Test fun parentCancellationDoesNotWaitForNativeCallAndRetiresTheLateHandle() = runTest {
        val executor = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        val entered = CountDownLatch(1); val release = CountDownLatch(1); val session = Session()
        val parent = CoroutineScope(coroutineContext + SupervisorJob())
        val link = CloudMachineHandshake(parent, Api(), "vm", "fingerprint", { true }, {
            entered.countDown(); check(release.await(3, TimeUnit.SECONDS)); session
        }, executor)
        try {
            link.start(); runCurrent(); assertTrue(entered.await(1, TimeUnit.SECONDS))
            parent.cancel(); runCurrent()
            assertEquals(CloudLinkPhase.CLOSED, link.state.value.phase)
            assertTrue(runCatching { link.awaitSession() }.exceptionOrNull() is CancellationException)
            release.countDown(); assertTrue(session.closed.await(1, TimeUnit.SECONDS))
            assertEquals(1, session.closes.get())
        } finally { release.countDown(); link.close(); parent.cancel(); executor.close() }
    }
    @Test fun nativeFailureCancelsApprovalAndDoesNotRetryTheConnect() = runTest {
        val api = Api(); var connects = 0
        val link = CloudMachineHandshake<Session>(this, api, "vm", "fingerprint", { true }, {
            connects++; throw IOException("Connect failed")
        }, StandardTestDispatcher(testScheduler))
        link.start(); runCurrent(); advanceTimeBy(10_000); runCurrent()
        assertEquals(CloudLinkPhase.FAILED, link.state.value.phase)
        assertEquals(1, connects); assertEquals(0, api.approvals)
        link.close()
    }
    @Test fun retriesTransientApprovalButStopsOnExpiredInvitation() = runTest {
        val session = Session(); val api = Api()
        api.approval = { when (api.approvals) { 1 -> throw CloudApiFailure(503, null, "Retry"); 2 -> false; else -> throw CloudApiFailure(404, null, "Gone") } }
        val link = CloudMachineHandshake(this, api, "vm", "fingerprint", { true }, { session }, StandardTestDispatcher(testScheduler))
        link.start(); advanceUntilIdle()
        assertEquals(3, api.approvals)
        assertEquals(CloudLinkPhase.FAILED, link.state.value.phase)
        assertEquals("Cloud invitation expired", link.state.value.failure!!.detail)
        assertEquals(1, session.closes.get()); link.close()
    }
    @Test fun approvalAttemptAndTimeBudgetsAreBounded() = runTest {
        for (timeout in listOf(300_000L, 1_000L)) {
            val session = Session(); val api = Api().apply { approval = { false } }
            val link = CloudMachineHandshake(this, api, "vm", "fingerprint", { true }, { session }, StandardTestDispatcher(testScheduler),
                approvalAttempts = 2, approvalTimeoutMillis = timeout)
            link.start(); advanceUntilIdle()
            assertEquals(if (timeout == 1000L) 0 else 2, api.approvals)
            assertEquals(CloudLinkPhase.FAILED, link.state.value.phase)
            assertEquals("Cloud invitation approval timed out", link.state.value.failure!!.detail)
            assertEquals(1, session.closes.get()); link.close()
        }
    }
    @Test fun observerCancellationPreservesTheOwnedConnectionUntilRetirement() = runTest {
        val session = Session()
        val link = CloudMachineHandshake(this, Api(), "vm", "fingerprint", { true }, { session }, StandardTestDispatcher(testScheduler))
        val observer = launch { link.awaitSession() }
        runCurrent(); observer.cancel(); advanceTimeBy(2000); runCurrent()
        assertSame(session, link.awaitSession()); assertEquals(0, session.closes.get())
        link.close(); runCurrent(); assertEquals(1, session.closes.get())
    }
    companion object {
        private fun endpoint() = CloudAttachEndpoint("ws://fixture", "session", CloudAttachEndpoint.Invitation("secret", "invitation"), false)
    }
}
