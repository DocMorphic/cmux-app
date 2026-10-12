package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class NativeForegroundBrowserAccessTest {
    @Test fun foregroundBrowserUsesExistingLaneAndRejectsResultsAfterAuthorityChanges() = runBlocking<Unit> {
        val control = PoolTestTransport()
        var live = true
        var opened = 0
        var closed = 0
        val transport = object : MobileRpcTransport by control {
            override val supportsBrowserTunnels = true
            override suspend fun browserListeningPorts() = BrowserTunnelProtocol.ListeningPorts(emptyList(), false)
            override suspend fun openBrowserTunnel(host: String, port: Int): BrowserTunnelLane {
                opened++
                return object : BrowserTunnelLane {
                    override suspend fun read(maximumBytes: Int): ByteArray? = null
                    override suspend fun write(bytes: ByteArray) {}
                    override suspend fun finishSending() {}
                    override fun close() { closed++ }
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        MobileRpcClient(transport, { "fixture" }).use { client ->
            client.connect()
            val access = NativeForegroundBrowserAccess(scope, client, { setOf(BrowserTunnelProtocol.CAPABILITY) }, { live })
            assertEquals(MacBrowserAvailability.AVAILABLE, access.availability())
            val entered = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
            val pending = async { runCatching { access.use("localhost", 3000) { entered.complete(Unit); release.await() } } }
            withTimeout(2000) { entered.await() }; live = false; release.complete(Unit)
            assertTrue(withTimeout(2000) { pending.await() }.isFailure)
            assertEquals(1, opened); assertEquals(1, closed); assertEquals(1, control.connects.get())
            assertEquals(MacBrowserAvailability.NOT_CONNECTED, access.availability())
            assertTrue(runCatching { access.listeningPorts() }.isFailure)
            assertEquals(1, opened)
        }
        scope.cancel()
    }

    @Test fun missingCapabilityAndMissingLanesHaveDistinctAvailabilityWithoutNewConnection() = runBlocking<Unit> {
        val control = PoolTestTransport(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        MobileRpcClient(control, { "fixture" }).use { client ->
            client.connect()
            var capabilities = emptySet<String>()
            val access = NativeForegroundBrowserAccess(scope, client, { capabilities }, { true })
            assertEquals(MacBrowserAvailability.NEEDS_MAC_UPDATE, access.availability())
            capabilities = setOf(BrowserTunnelProtocol.CAPABILITY)
            assertEquals(MacBrowserAvailability.ROUTE_WITHOUT_LANES, access.availability())
            client.close(); assertEquals(MacBrowserAvailability.NOT_CONNECTED, access.availability())
            assertEquals(1, control.connects.get())
        }
        scope.cancel()
    }
}
