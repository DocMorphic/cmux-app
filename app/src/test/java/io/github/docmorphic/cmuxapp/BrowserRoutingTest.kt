package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class BrowserRoutingTest {
    private val empty = object : BrowserTunnelLane {
        override suspend fun read(maximumBytes: Int): ByteArray? = null
        override suspend fun write(bytes: ByteArray) {}
        override suspend fun finishSending() {}
        override fun close() {}
    }
    @Test fun everyLoopbackSpellingMatchesCompiledUnchangedSwiftMatcher() {
        val golden = JSONObject(javaClass.getResource("/browser/tunnel-204a11d.json")!!.readText()).getJSONArray("loopback")
        for (index in 0 until golden.length()) {
            val row = golden.getJSONObject(index)
            assertEquals(row.getString("host"), row.getBoolean("matches"), BrowserLoopbackHost.matches(row.getString("host")))
        }
    }
    @Test fun routesEachDestinationAndOnlyPolicyDenialBeforeOpenFallsBack() = runBlocking<Unit> {
        val opens = mutableListOf<String>()
        var status: BrowserTunnelProtocol.Status? = null
        val router = MacBrowserRouter(BrowserTunnelBackend { host, _, use ->
            opens += "mac:$host"
            status?.let { throw BrowserTunnelProtocol.OpenFailure(it) }
            use(empty)
        }, BrowserTunnelBackend { host, _, use -> opens += "direct:$host"; use(empty) })
        router.use("app.localhost", 3000) {}
        router.use("example.invalid", 443) {}
        router.allowsNonLoopbackHosts = true
        router.use("build.lan", 8080) {}
        status = BrowserTunnelProtocol.Status.DENIED
        router.use("metadata.internal", 80) {}
        assertEquals(listOf("mac:app.localhost", "direct:example.invalid", "mac:build.lan",
            "mac:metadata.internal", "direct:metadata.internal"), opens)
        opens.clear()
        for (failure in BrowserTunnelProtocol.Status.entries.filter { it != BrowserTunnelProtocol.Status.CONNECTED }) {
            status = failure
            assertTrue(runCatching { router.use("127.1", 3000) {} }.isFailure)
            if (failure != BrowserTunnelProtocol.Status.DENIED)
                assertTrue(runCatching { router.use("build.lan", 80) {} }.isFailure)
        }
        assertTrue(opens.none { it.startsWith("direct:") })
        status = null; opens.clear()
        assertTrue(runCatching { router.use("build.lan", 80) {
            throw BrowserTunnelProtocol.OpenFailure(BrowserTunnelProtocol.Status.DENIED)
        } }.isFailure)
        assertEquals(listOf("mac:build.lan"), opens) // A failed transfer must never be replayed directly.
    }
    @Test fun cancellationNeverFallsBackAndProviderCapacityReturnsAfterCancellationOrFailure() = runBlocking<Unit> {
        val router = MacBrowserRouter(BrowserTunnelBackend { _, _, _ -> throw CancellationException("retired owner") },
            BrowserTunnelBackend { _, _, _ -> fail("Retired owner fell back") }).apply { allowsNonLoopbackHosts = true }
        assertTrue(runCatching { router.use("build.lan", 80) {} }.exceptionOrNull() is CancellationException)
        val control = PoolTestTransport()
        val transport = object : MobileRpcTransport by control {
            override suspend fun openBrowserTunnel(host: String, port: Int): BrowserTunnelLane = empty
        }
        MobileRpcClient(transport, { "fixture" }).use { client ->
            client.connect()
            var providers = 0; var available = true
            val mac = MacBrowserLaneBackend({ providers++; if (available) client else throw IOException("offline") }, 1)
            val entered = CompletableDeferred<Unit>()
            val first = launch { mac.use("localhost", 80) { entered.complete(Unit); awaitCancellation() } }
            entered.await()
            val busy = runCatching { mac.use("localhost", 80) {} }.exceptionOrNull()
            assertEquals(BrowserTunnelProtocol.Status.BUSY, (busy as BrowserTunnelProtocol.OpenFailure).status)
            assertEquals(1, providers)
            first.cancelAndJoin()
            available = false
            assertTrue(runCatching { mac.use("localhost", 80) {} }.exceptionOrNull() is IOException)
            available = true
            mac.use("localhost", 80) {}
            assertEquals(3, providers)
        }
    }
}
