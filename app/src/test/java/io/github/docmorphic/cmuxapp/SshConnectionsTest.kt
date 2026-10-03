package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.UUID

@OptIn(ExperimentalCoroutinesApi::class)
class SshConnectionsTest {
    private val cleanup = mutableListOf<AutoCloseable>()
    @After fun cleanup() { cleanup.asReversed().forEach { it.close() } }
    private val hosts = SshHostStore({ null }, {})
    private val host = SshHostRecord(name = "Fixture", endpoint = SshEndpoint("fixture.invalid", username = "test"))
    private val key = SshHostKey.parse("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")
    private class Connection(override val plan: SshDialPlan) : SshManagedConnection {
        override val disconnected = MutableStateFlow(false)
        override val isConnected get() = !disconnected.value
        override fun close() { disconnected.value = true }
    }
    private fun TestScope.manager(dial: suspend (UUID, suspend (SshTrustQuestion) -> Boolean) -> Connection): SshConnections<Connection> {
        hosts.upsert(host)
        return SshConnections(hosts, backgroundScope, { true }) { id, _, _, ask -> dial(id, ask) }.also { cleanup += it }
    }
    @Test fun simultaneousOpenAndAutoOpenShareDialDespiteOneViewCancellation() = runTest {
        val gate = CompletableDeferred<Unit>(); var dials = 0
        val manager = manager { id, _ -> dials++; gate.await(); Connection(hosts.dialPlan(id)) }
        val first = async { manager.open(host.id) }
        val second = async { manager.autoConnect(host.id) }
        runCurrent(); assertEquals(1, dials)
        first.cancelAndJoin(); gate.complete(Unit); runCurrent()
        val connection = second.await()!!
        assertSame(connection, manager.open(host.id)); assertEquals(1, dials)
        assertEquals(SshConnectionPhase.CONNECTED, manager.statuses.value[host.id]?.phase)
    }
    @Test fun failureWaitsForExplicitRetryAndDoesNotLoopAutomatically() = runTest {
        var dials = 0
        val manager = manager { id, _ -> if (++dials == 1) throw IOException("fixture refused") else Connection(hosts.dialPlan(id)) }
        val failed = async { runCatching { manager.open(host.id) } }; runCurrent()
        assertTrue(failed.await().isFailure)
        assertEquals(SshConnectionPhase.FAILED, manager.statuses.value[host.id]?.phase)
        assertNull(manager.autoConnect(host.id)); assertEquals(1, dials)
        val retry = async { manager.open(host.id) }; runCurrent(); assertTrue(retry.await().isConnected)
        assertEquals(2, dials)
    }
    @Test fun disconnectPersistsPauseAndExplicitOpenResumes() = runTest {
        val manager = manager { id, _ -> Connection(hosts.dialPlan(id)) }
        val pending = async { manager.open(host.id) }; runCurrent(); val original = pending.await()
        manager.disconnect(host.id); runCurrent()
        assertFalse(original.isConnected); assertNull(manager.autoConnect(host.id))
        assertTrue(hosts.state.value.host(host.id)!!.autoConnectPaused)
        val retry = async { manager.open(host.id) }; runCurrent()
        assertNotSame(original, retry.await()); assertFalse(hosts.state.value.host(host.id)!!.autoConnectPaused)
    }
    @Test fun labelEditKeepsConnectionRouteEditRetiresItAndDropAllowsAutoOpen() = runTest {
        var dials = 0
        val manager = manager { id, _ -> dials++; Connection(hosts.dialPlan(id)) }
        val pending = async { manager.open(host.id) }; runCurrent(); val connection = pending.await()
        hosts.upsert(host.copy(name = "Renamed")); runCurrent(); assertTrue(connection.isConnected)
        hosts.upsert(host.copy(endpoint = host.endpoint.copy(port = 2222))); runCurrent()
        assertFalse(connection.isConnected)
        val next = async { manager.autoConnect(host.id) }; runCurrent(); val replacement = next.await()!!
        replacement.close(); runCurrent()
        assertEquals(SshConnectionPhase.IDLE, manager.statuses.value[host.id]?.phase)
        val third = async { manager.autoConnect(host.id) }; runCurrent(); assertTrue(third.await()!!.isConnected)
        assertEquals(3, dials)
    }
    @Test fun routeEditDuringPromptRemovesQuestionAndRejectsLateAnswer() = runTest {
        val manager = manager { id, ask ->
            ask(SshTrustQuestion(id, host.endpoint, hosts.trustSnapshot(host.endpoint), key))
            Connection(hosts.dialPlan(id))
        }
        val pending = async { runCatching { manager.open(host.id) } }; runCurrent()
        val prompt = manager.prompts.value.single()
        hosts.upsert(host.copy(endpoint = host.endpoint.copy(username = "changed"))); runCurrent()
        assertTrue(manager.prompts.value.isEmpty()); assertFalse(manager.answer(prompt.id, true))
        assertTrue(pending.await().isFailure); assertTrue(hosts.state.value.pinnedKeys.isEmpty())
    }
    @Test fun sharedJumpQuestionCoalescesAndOneApprovalCanCommitForBothRoutes() = runTest {
        val child = host.copy(id = UUID.randomUUID(), name = "Child", jumpHostId = host.id)
        val manager = manager { id, ask ->
            val plan = hosts.dialPlan(id)
            val question = SshTrustQuestion(host.id, host.endpoint, hosts.trustSnapshot(host.endpoint), key)
            check(ask(question)); check(hosts.confirmHostKey(plan, host.id, question.prior, key))
            Connection(plan)
        }
        hosts.upsert(child)
        val first = async { manager.open(host.id) }; val second = async { manager.open(child.id) }
        runCurrent(); val prompt = manager.prompts.value.single()
        assertTrue(manager.answer(prompt.id, true)); assertFalse(manager.answer(prompt.id, false))
        runCurrent(); assertTrue(first.await().isConnected); assertTrue(second.await().isConnected)
        assertEquals(key, hosts.trustSnapshot(host.endpoint).pinned)
    }
    @Test fun declinedJumpQuestionPausesAllWaitingRoutesWithoutFailureStatus() = runTest {
        val child = host.copy(id = UUID.randomUUID(), jumpHostId = host.id)
        val manager = manager { id, ask ->
            check(ask(SshTrustQuestion(host.id, host.endpoint, hosts.trustSnapshot(host.endpoint), key)))
            Connection(hosts.dialPlan(id))
        }
        hosts.upsert(child)
        val first = async { runCatching { manager.open(host.id) } }
        val second = async { runCatching { manager.open(child.id) } }
        runCurrent(); assertTrue(manager.answer(manager.prompts.value.single().id, false)); runCurrent()
        assertTrue(first.await().isFailure); assertTrue(second.await().isFailure)
        assertTrue(hosts.state.value.host(host.id)!!.autoConnectPaused)
        assertNull(manager.autoConnect(child.id)); assertTrue(manager.prompts.value.isEmpty())
        assertEquals(SshConnectionPhase.IDLE, manager.statuses.value[child.id]?.phase)
    }
    @Test fun ownerCloseClearsPromptsAndPreventsLateConnectionPublication() = runTest {
        val manager = manager { id, ask ->
            ask(SshTrustQuestion(id, host.endpoint, hosts.trustSnapshot(host.endpoint), key)); Connection(hosts.dialPlan(id))
        }
        val pending = async { runCatching { manager.open(host.id) } }; runCurrent()
        val question = manager.prompts.value.single(); manager.close(); runCurrent()
        assertTrue(pending.await().isFailure); assertTrue(manager.prompts.value.isEmpty())
        assertFalse(manager.answer(question.id, true)); assertTrue(manager.statuses.value.isEmpty())
    }
    @Test fun trustApprovalCannotSurvivePinRemovalOrKeyRoundTrip() {
        hosts.upsert(host)
        val plan = hosts.dialPlan(host.id); val question = hosts.trustSnapshot(host.endpoint)
        assertTrue(hosts.confirmHostKey(plan, host.id, question, key))
        assertTrue(hosts.confirmHostKey(plan, host.id, question, key))
        hosts.forgetHostKey(hosts.trustSnapshot(host.endpoint))
        assertFalse(hosts.confirmHostKey(plan, host.id, question, key))
        assertTrue(hosts.confirmHostKey(plan, host.id, hosts.trustSnapshot(host.endpoint), key))
        assertFalse(hosts.confirmHostKey(plan, host.id, question, key))
    }
}
