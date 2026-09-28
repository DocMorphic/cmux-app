package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NativeIrohRuntimeTest {
    private val team = NativeTeamScope("login-a", "user-a", "team-a", 1)
    private val mac = IrohV2Computer("record", "ab".repeat(32), "mac-id", "default", "Mac", emptyList())
    private fun ready() = IrohV2ControlState(ready = true, computers = listOf(mac), permissionExpiresAt = 2000)
    private inner class Backend : IrohAccountBackend {
        override val state = MutableStateFlow(ready())
        val closes = AtomicInteger()
        val refreshes = AtomicInteger()
        val transports = mutableListOf<PoolTestTransport>()
        var startAction: suspend () -> Unit = { }
        var dial: suspend () -> Unit = { }
        override suspend fun start() { startAction() }
        override suspend fun refresh() { refreshes.incrementAndGet() }
        override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport {
            assertTrue(permits())
            val wire = PoolTestTransport().also { synchronized(transports) { transports += it } }
            return object : MobileRpcTransport by wire {
                override suspend fun connect() { dial(); wire.connect() }
            }
        }
        override fun close() { closes.incrementAndGet() }
    }
    private fun pairing(scope: NativeTeamScope = team) = PairingCodeParser.parse(PairingCodeParser.computer(mac, scope)).getOrThrow() as PairingCode.Iroh

    @Test fun oneScopedOwnerDiscoversAndSharesMacUntilLastConsumerReleasesIt() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            val a = runtime.connect(pairing())
            val b = runtime.connect(pairing())
            assertEquals(1, backend.transports.size)
            runtime.refresh()
            withTimeout(2000) { while (backend.refreshes.get() == 0) delay(1) }
            assertEquals(0, backend.closes.get())
            assertFalse(a.isClosed)
            a.close()
            assertEquals(0, backend.transports.single().closes.get())
            b.close()
            assertEquals(1, backend.transports.single().closes.get())
        }
    }

    @Test fun locatorScopeAndMacIdentityAreCheckedAgainstLiveDirectory() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            for (wrong in listOf(pairing().copy(userId = "other"), pairing().copy(teamId = "other"),
                pairing().copy(macDeviceId = "other"), pairing().copy(buildTag = "other"), pairing().copy(endpointId = "cc".repeat(32)))) {
                assertTrue(runCatching { runtime.connect(wrong) }.isFailure)
            }
            assertTrue(backend.transports.isEmpty())
        }
    }

    @Test fun directoryRevocationClosesExistingWireAndRejectsReconnect() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val client = runtime.connect(pairing())
            backend.state.value = ready().copy(computers = emptyList())
            withTimeout(2000) { client.disconnected.first() }
            assertTrue(client.isClosed)
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            client.close()
        }
    }

    @Test fun teamChangeClosesOldOwnerAndCannotReuseItsSavedLocator() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val created = Channel<Backend>(4)
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> Backend().also { created.send(it) } }, { 1000 }).use { runtime ->
            val firstBackend = withTimeout(2000) { created.receive() }
            val old = runtime.connect(pairing())
            val next = team.copy(teamId = "team-b", generation = 2)
            teams.value = NativeAccountTeamsState(scope = next)
            withTimeout(2000) { runtime.state.first { it.account == next && it.ready }; old.disconnected.first() }
            assertTrue(firstBackend.closes.get() >= 1)
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            runtime.connect(pairing(next)).close()
            old.close()
        }
    }

    @Test fun lateBackendCreationCannotRestoreSignedOutAccount() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ ->
            entered.complete(Unit)
            withContext(NonCancellable) { release.await() }
            backend
        }, { 1000 }).use { runtime ->
            withTimeout(2000) { entered.await() }
            teams.value = NativeAccountTeamsState()
            release.complete(Unit)
            withTimeout(2000) { runtime.state.first { it.account == null }; while (backend.closes.get() == 0) delay(1) }
            assertFalse(runtime.state.value.ready)
            assertTrue(backend.transports.isEmpty())
        }
    }

    @Test fun expiryDeniesNewConnectionsWithoutWaitingForAnotherServerEvent() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend()
        var time = 1000L
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { time }).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            time = 2000
            assertTrue(runCatching { runtime.connect(pairing()) }.isFailure)
            assertTrue(backend.transports.isEmpty())
        }
    }

    @Test fun childDialTimeoutIsReconnectableWithoutCancellingTheCaller() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val backend = Backend().apply { dial = { withTimeout(30) { awaitCancellation() } } }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val failure = runCatching { runtime.connect(pairing()) }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            assertTrue(failure?.cause is TimeoutCancellationException)
            assertTrue(currentCoroutineContext().isActive)
            assertEquals(1, backend.transports.single().closes.get())
            backend.dial = { }
            runtime.connect(pairing()).close()
            assertEquals(2, backend.transports.size)
        }
    }

    @Test fun cancellingCallerDuringDialStillPropagatesAndClosesCandidate() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val entered = CompletableDeferred<Unit>()
        val backend = Backend().apply { dial = { entered.complete(Unit); awaitCancellation() } }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            val pending = async { runtime.connect(pairing()) }
            withTimeout(2000) { entered.await() }
            pending.cancelAndJoin()
            assertTrue(pending.isCancelled)
            assertEquals(1, backend.transports.single().closes.get())
            assertEquals(0, backend.closes.get())
            backend.dial = { }
            runtime.connect(pairing()).close()
        }
    }

    @Test fun startupRevocationCannotBeHiddenByItsTransportCancellation() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val count = AtomicInteger()
        val backend = Backend().apply { startAction = {
            state.value = IrohV2ControlState(failure = "device_revoked")
            throw CancellationException("Account session changed")
        } }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ ->
            count.incrementAndGet(); backend
        }, { 1000 }, retryDelayMillis = 10).use { runtime ->
            val result = withTimeout(2000) { runtime.state.first { it.error != null } }
            assertEquals("This device’s cmux access was revoked", result.error)
            delay(100)
            assertEquals(1, count.get())
        }
    }

    @Test fun runningAuthenticationFailureStopsAutomaticReenrollment() = runBlocking<Unit> {
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val count = AtomicInteger()
        val backend = Backend()
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "test-token" }, { _, _ ->
            count.incrementAndGet(); backend
        }, { 1000 }, retryDelayMillis = 10).use { runtime ->
            withTimeout(2000) { runtime.state.first { it.ready } }
            backend.state.value = IrohV2ControlState(failure = "unauthorized")
            withTimeout(2000) { runtime.state.first { it.error == "Sign in again to connect to your computers" } }
            delay(100)
            assertEquals(1, count.get())
            assertFalse(runtime.state.value.ready)
        }
    }
}
