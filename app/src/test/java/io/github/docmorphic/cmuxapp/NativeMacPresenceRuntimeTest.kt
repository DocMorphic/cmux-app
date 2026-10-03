package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.mockwebserver.*
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit

class NativeMacPresenceRuntimeTest {
    private class Fixture : AutoCloseable {
        val server = MockWebServer()
        val team = NativeTeamScope("login", "user", "team", 1)
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val active = MutableStateFlow(IrxProbeActivity(false))
        val peers = Channel<WebSocket>(Channel.UNLIMITED)
        val requests = CopyOnWriteArrayList<RecordedRequest>()
        val refreshes = CopyOnWriteArrayList<Boolean>()
        var runtime: NativeMacPresenceRuntime? = null
        var firstStatus: Int? = null
        init {
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request
                    if (requests.size == 1 && firstStatus != null) return MockResponse().setResponseCode(firstStatus!!)
                    return MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                        override fun onOpen(webSocket: WebSocket, response: Response) { peers.trySend(webSocket) }
                    })
                }
            }
        }
        fun start() = NativeMacPresenceRuntime(teams, active, { teams.value.scope == it },
            { refreshes += it; "fixture-token" }, server.url("/"), retryBaseMs = 10).also { runtime = it }
        suspend fun peer() = withTimeout(5000) { peers.receive() }
        suspend fun ready(runtime: NativeMacPresenceRuntime, scope: NativeTeamScope = team) = withTimeout(5000) {
            runtime.state.first { it.owner == scope }
        }
        override fun close() { runtime?.close(); peers.close(); server.shutdown() }
    }
    @Test fun foregroundSocketAuthenticatesScopesUpdatesAndStopsOnBackground() = runBlocking {
        Fixture().use { f ->
            val runtime = f.start()
            assertNull(f.server.takeRequest(100, TimeUnit.MILLISECONDS))
            f.active.value = IrxProbeActivity(true, 1)
            val peer = f.peer()
            peer.send(NativeMacPresenceTest.snapshot("team", NativeMacPresenceTest.instance()))
            assertEquals("com.cmuxterm.app", f.ready(runtime).instances.values.single().bundleId)
            val request = f.requests.single()
            assertEquals("/v1/presence/subscribe", request.path)
            assertEquals("Bearer fixture-token", request.getHeader("Authorization"))
            assertEquals("team", request.getHeader("X-Cmux-Team-Id"))
            peer.send(NativeMacPresenceTest.event("routes", NativeMacPresenceTest.instance(bundle = "com.cmuxterm.app.nightly")))
            withTimeout(5000) { runtime.state.first { it.instances.values.singleOrNull()?.bundleId == "com.cmuxterm.app.nightly" } }
            f.active.value = IrxProbeActivity(false, 2)
            withTimeout(5000) { runtime.state.first { it.owner == null } }
            peer.send(NativeMacPresenceTest.snapshot("team", NativeMacPresenceTest.instance()))
            delay(100)
            assertNull(runtime.state.value.owner); assertEquals(1, f.requests.size)
        }
    }
    @Test fun accountTransitionRejectsLateOldFramesAndStartsWithFreshSnapshot() = runBlocking {
        Fixture().use { f ->
            f.active.value = IrxProbeActivity(true)
            val runtime = f.start(); val old = f.peer()
            old.send(NativeMacPresenceTest.snapshot("team", NativeMacPresenceTest.instance())); f.ready(runtime)
            val next = f.team.copy(login = "new-login", teamId = "other", generation = 2)
            f.teams.value = NativeAccountTeamsState(scope = next)
            val peer = f.peer()
            old.send(NativeMacPresenceTest.snapshot("team", NativeMacPresenceTest.instance(bundle = "wrong")))
            assertNull(runtime.state.value.owner)
            peer.send(NativeMacPresenceTest.snapshot("other", NativeMacPresenceTest.instance(bundle = "com.cmuxterm.app.nightly")))
            assertEquals("com.cmuxterm.app.nightly", f.ready(runtime, next).instances.values.single().bundleId)
            assertEquals("other", f.requests.last().getHeader("X-Cmux-Team-Id"))
            runtime.close(); delay(50); assertNull(runtime.state.value.owner)
        }
    }
    @Test fun unauthorizedHandshakeRefreshesTokenAndWrongTeamFrameRestartsStream() = runBlocking {
        Fixture().use { f ->
            f.firstStatus = 401; f.active.value = IrxProbeActivity(true)
            val runtime = f.start(); val peer = f.peer()
            assertEquals(listOf(false, true), f.refreshes.toList())
            peer.send(NativeMacPresenceTest.snapshot("wrong", NativeMacPresenceTest.instance()))
            val recovered = f.peer()
            assertNull(runtime.state.value.owner)
            recovered.send(NativeMacPresenceTest.snapshot("team", NativeMacPresenceTest.instance()))
            f.ready(runtime)
            assertEquals(3, f.requests.size)
        }
    }
    @Test fun delayedTokenForRetiredAccountCannotOpenSocket() = runBlocking {
        Fixture().use { f ->
            val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val runtime = NativeMacPresenceRuntime(f.teams, f.active, { f.teams.value.scope == it }, {
                started.complete(Unit); withContext(NonCancellable) { release.await() }; "fixture-token"
            }, f.server.url("/"))
            f.runtime = runtime
            f.active.value = IrxProbeActivity(true)
            withTimeout(5000) { started.await() }
            f.teams.value = NativeAccountTeamsState()
            release.complete(Unit)
            delay(100)
            assertNull(f.server.takeRequest(100, TimeUnit.MILLISECONDS))
            assertNull(runtime.state.value.owner)
        }
    }
}
