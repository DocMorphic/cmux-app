package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.*

/** Only the already admitted foreground wire supplies this Mac's lanes. */
internal class NativeForegroundBrowserAccess(private val scope: CoroutineScope, private val client: MobileRpcClient,
    private val capabilities: () -> Set<String>, private val permits: () -> Boolean) : MacBrowserAccess {
    override suspend fun availability() = withContext(scope.coroutineContext.minusKey(Job)) {
        when {
            client.isClosed || !permits() -> MacBrowserAvailability.NOT_CONNECTED
            BrowserTunnelProtocol.CAPABILITY !in capabilities() -> MacBrowserAvailability.NEEDS_MAC_UPDATE
            !client.supportsBrowserTunnels -> MacBrowserAvailability.ROUTE_WITHOUT_LANES
            else -> MacBrowserAvailability.AVAILABLE
        }
    }
    private suspend fun <T> use(action: suspend () -> T): T = withContext(scope.coroutineContext.minusKey(Job)) {
        check(availability() == MacBrowserAvailability.AVAILABLE) { "Foreground browser connection changed" }
        val result = withContext(Dispatchers.IO) { action() }
        ensureActive(); check(availability() == MacBrowserAvailability.AVAILABLE) { "Foreground browser connection changed" }
        result
    }
    override suspend fun listeningPorts() = use { checkNotNull(client.browserListeningPorts()) }
    override suspend fun use(host: String, port: Int, connected: suspend (BrowserTunnelLane) -> Unit) = use {
        check(client.useBrowserTunnel(host, port, connected)) { "Foreground route has no browser lanes" }
    }
}
