package io.github.docmorphic.cmuxapp

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.wireguard.android.backend.GoBackend
import com.wireguard.android.backend.Tunnel
import com.wireguard.config.Config
import com.wireguard.config.Peer
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

/** One backend owner. The controller serializes IO; the short native lock also
 * serializes upstream service destruction. DNS is resolved before taking it. */
internal class NativeCloudVpnPlatform(private val context: Context,
    private val changed: (String, Boolean) -> Unit) : CloudSystemVpnPlatform {
    private val epoch = AtomicLong()
    private var backend: GoBackend? = null
    @Volatile private var service: NativeCloudVpnService? = null
    @Volatile private var tunnel: Tunnel? = null
    @Volatile private var attempt: String? = null
    override val consentGranted get() = VpnService.prepare(context) == null
    override fun interrupt() {
        epoch.incrementAndGet()
        service?.requestStop()
    }
    override suspend fun install(profile: CloudVpnProfile, current: () -> Boolean) {
        val at = epoch.get()
        fun admitted() = at == epoch.get() && current() && consentGranted
        check(admitted()) { "Cloud VPN permission or account changed" }
        require(CloudVpnRoutePolicy.permitsConfiguration(profile.configuration)) { "Invalid Cloud VPN routes" }
        val parsed = Config.parse(profile.configuration.reader().buffered())
        require(parsed.peers.size == 1 && parsed.`interface`.addresses.isNotEmpty())
        // Build a config containing literal endpoints, so GoBackend never waits for
        // DNS while holding the native/service-destruction lock on Android's UI thread.
        val resolved = Config.Builder().setInterface(parsed.`interface`).apply {
            parsed.peers.forEach { peer ->
                val endpoint = peer.endpoint.orElseThrow { IllegalArgumentException("Missing Cloud VPN endpoint") }
                    .resolved.orElseThrow { IllegalStateException("Could not resolve Cloud VPN endpoint") }
                addPeer(Peer.Builder().setPublicKey(peer.publicKey).addAllowedIps(peer.allowedIps).setEndpoint(endpoint).apply {
                    peer.preSharedKey.ifPresent { setPreSharedKey(it) }
                    peer.persistentKeepalive.ifPresent { setPersistentKeepalive(it) }
                }.build())
            }
        }.build()
        check(admitted()) { "Cloud VPN account changed" }
        val startup = NativeCloudVpnService.Startup(UUID.randomUUID().toString(), this, ::admitted)
        try {
            NativeCloudVpnService.register(startup)
            withContext(Dispatchers.Main.immediate) {
                check(admitted())
                context.startForegroundService(Intent(context, NativeCloudVpnService::class.java)
                    .setAction(NativeCloudVpnService.START).putExtra(NativeCloudVpnService.TOKEN, startup.id))
            }
            val live = withTimeout(5000) { startup.ready.await() }
            service = live
            synchronized(NativeCloudVpnService.nativeLock) {
                check(admitted() && live.alive) { "Cloud VPN service retired" }
                val engine = backend ?: GoBackend(context).also { backend = it }
                val active = object : Tunnel {
                    override fun getName() = "cmux-cloud"
                    override fun onStateChange(state: Tunnel.State) { changed(profile.enrollment.attempt, state == Tunnel.State.UP) }
                }
                tunnel = active; attempt = profile.enrollment.attempt
                check(engine.setState(active, Tunnel.State.UP, resolved) == Tunnel.State.UP)
                check(admitted() && live.alive) { "Cloud VPN service retired" }
            }
            live.showConnected()
        } finally {
            NativeCloudVpnService.unregister(startup)
            // Preserve ownership even if cancellation beats await's resumption.
            if (service == null) service = startup.service
        }
    }
    override suspend fun stop() {
        val live = service
        synchronized(NativeCloudVpnService.nativeLock) {
            tunnel?.let { backend?.setState(it, Tunnel.State.DOWN, null) }
            tunnel = null; attempt = null
        }
        if (live != null) {
            live.requestStop()
            withTimeout(5000) { live.destroyed.await() }
            if (service === live) service = null
        }
    }
    override fun connected(attempt: String): Boolean = synchronized(NativeCloudVpnService.nativeLock) {
        this.attempt == attempt && service?.alive == true && tunnel?.let { backend?.getState(it) == Tunnel.State.UP } == true
    }
    fun serviceEnded(live: NativeCloudVpnService) {
        if (service === live) attempt?.let { changed(it, false) }
    }
}
