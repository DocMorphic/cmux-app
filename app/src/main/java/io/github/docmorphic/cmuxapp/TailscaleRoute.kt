package io.github.docmorphic.cmuxapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.Socket

/** Resolve once on a proven tunnel; retain its monitor through the socket lifetime. */
internal object TailscaleRoute {
    private data class Prepared(val network: Network, val proof: TailscalePathProof, val authority: AndroidTailscaleAuthority)
    suspend fun resolvePeer(context: Context, route: PairingCode.Route,
                            permits: () -> Boolean = { true }): PairingCode.Route {
        val prepared = prepare(context, route, permits)
        return try { prepared.proof.route } finally { prepared.authority.close() }
    }

    suspend fun resolve(context: Context, route: PairingCode.Route,
                        permits: () -> Boolean = { true }): MobileRpcTransport {
        val prepared = prepare(context, route, permits)
        return try {
            SocketMobileRpcTransport(prepared.proof.route, prepared.network.socketFactory, prepared.authority)
        } catch (failure: Throwable) { prepared.authority.close(); throw failure }
    }

    private suspend fun prepare(context: Context, route: PairingCode.Route, permits: () -> Boolean): Prepared {
        check(permits()) { "The Tailscale authorization changed" }
        require(route.port in 1..65535 && (TailscalePeerAddress.canonical(route.host) != null ||
            TailscalePeerAddress.isMagicDnsName(route.host))) { "Expected a Tailscale peer address" }
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val authority = AndroidTailscaleAuthority(manager, permits)
        return try {
            withContext(Dispatchers.IO) {
                authority.observe()
                val observed = TailscaleReadiness.await(authority.observations.state, route, permits)
                val tunnel = observed.tunnels.single()
                val network = authority.network(tunnel.network)
                val numeric = TailscalePeerAddress.canonical(route.host)
                val candidates = if (numeric != null) listOf(numeric) else {
                    network.getAllByName(route.host).mapNotNull { TailscalePeerAddress.canonical(it.hostAddress.orEmpty()) }.distinct()
                }
                val peer = candidates.firstOrNull { it !in tunnel.peers }
                    ?: error("The QR route did not resolve to a remote Tailscale peer")
                val proof = TailscalePathProof.prepare(observed.tunnels, route.copy(host = peer))
                authority.bind(observed, proof) // DNS must not outlive the captured tunnel generation.
                Prepared(network, proof, authority)
            }
        } catch (failure: Throwable) { authority.close(); throw failure }
    }

    internal fun snapshots(manager: ConnectivityManager): List<TailscaleTunnel> = manager.allNetworks.mapNotNull { network ->
        if (manager.getNetworkCapabilities(network)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) != true) null
        else manager.getLinkProperties(network)?.let { snapshot(network, it) }
    }

    internal fun snapshot(network: Network, link: LinkProperties): TailscaleTunnel? {
        val peers = link.linkAddresses.mapNotNull { TailscalePeerAddress.canonical(it.address.hostAddress.orEmpty()) }.toSet()
        val name = link.interfaceName?.takeIf { it.isNotBlank() } ?: return null
        return if (peers.isEmpty()) null else TailscaleTunnel(network.networkHandle, name, peers)
    }
}

/** A lost or changed tunnel permanently retires this incarnation, even if it later returns. */
private class AndroidTailscaleAuthority(
    private val manager: ConnectivityManager,
    private val permits: () -> Boolean
) : MobileSocketAuthority {
    val observations = TailscaleObservations()
    private val lock = Any()
    private val networks = mutableMapOf<Long, Network>()
    private var expected: TailscaleObservation? = null
    private var proof: TailscalePathProof? = null
    private var registered = false
    private var retired = false
    private var invalidated: (() -> Unit)? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = changed {
            synchronized(lock) { networks[network.networkHandle] = network }
            observations.available(network.networkHandle)
        }
        override fun onLost(network: Network) = changed {
            synchronized(lock) { networks.remove(network.networkHandle) }
            observations.lost(network.networkHandle)
        }
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) = changed {
            observations.capabilities(network.networkHandle, capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN))
        }
        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) = changed {
            observations.link(network.networkHandle, TailscaleRoute.snapshot(network, link))
        }
        override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = changed {
            observations.blocked(network.networkHandle, blocked)
        }
    }

    fun observe() = synchronized(lock) {
        check(!retired)
        if (!registered) {
            manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), callback)
            registered = true
        }
    }

    fun network(handle: Long): Network = synchronized(lock) {
        check(!retired)
        checkNotNull(networks[handle]) { "The Tailscale connection changed" }
    }

    fun bind(observation: TailscaleObservation, proof: TailscalePathProof) {
        synchronized(lock) {
            check(!retired && expected == null)
            expected = observation
            this.proof = proof
        }
        validate()
    }

    // Callback arguments are authoritative here. Synchronous ConnectivityManager
    // queries inside callbacks can return stale properties; reserve those for IO boundaries.
    private fun changed(update: () -> Unit) {
        update()
        val mismatch = synchronized(lock) { expected?.let { it != observations.state.value } == true }
        if (mismatch) retire()
    }

    override fun start(onInvalidated: () -> Unit) {
        synchronized(lock) {
            check(!retired && registered) { "The Tailscale connection changed. Reconnect to the Mac." }
            invalidated = onInvalidated
        }
        validate()
    }

    override fun diagnostics(socket: Socket): MobileTransportDiagnostics {
        validate(socket)
        return MobileTransportDiagnostics.tailscale()
    }

    override fun validate(socket: Socket?) {
        try {
            check(permits()) { "The Tailscale authorization changed. Pair this Mac again." }
            val captured = synchronized(lock) {
                check(!retired && expected == observations.state.value) { "The Tailscale connection changed. Reconnect to the Mac." }
                checkNotNull(proof)
            }
            captured.validate(TailscaleRoute.snapshots(manager), socket?.localAddress?.hostAddress,
                socket?.inetAddress?.hostAddress, socket?.port)
            synchronized(lock) {
                check(!retired && expected == observations.state.value) { "The Tailscale connection changed. Reconnect to the Mac." }
            }
        } catch (failure: Exception) { retire(); throw failure }
    }

    private fun retire() {
        val notify = synchronized(lock) { if (retired) return; retired = true; invalidated }
        notify?.invoke()
    }
    override fun close() {
        val unregister = synchronized(lock) {
            retired = true
            invalidated = null
            networks.clear()
            registered.also { registered = false }
        }
        if (unregister) manager.unregisterNetworkCallback(callback)
    }
}
