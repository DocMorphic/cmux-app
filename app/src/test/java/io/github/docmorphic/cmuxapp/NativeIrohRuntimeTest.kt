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
        override suspend fun start() { }
        override suspend fun refresh() { refreshes.incrementAndGet() }
        override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport {
            assertTrue(permits())
            return PoolTestTransport().also { synchronized(transports) { transports += it } }
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
}
