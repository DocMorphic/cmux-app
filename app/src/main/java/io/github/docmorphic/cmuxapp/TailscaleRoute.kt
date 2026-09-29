package io.github.docmorphic.cmuxapp

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.Socket

/** Resolve once on a proven tunnel; retain that numeric destination for this connection only. */
internal object TailscaleRoute {
    private data class Prepared(val manager: ConnectivityManager, val network: Network, val proof: TailscalePathProof)
    fun resolvePeer(context: Context, route: PairingCode.Route): PairingCode.Route = prepare(context, route).proof.route

    fun resolve(context: Context, route: PairingCode.Route, permits: () -> Boolean = { true }): MobileRpcTransport {
        check(permits()) { "The Tailscale authorization changed" }
        val prepared = prepare(context, route)
        return SocketMobileRpcTransport(prepared.proof.route, prepared.network.socketFactory,
            AndroidTailscaleAuthority(prepared.manager, prepared.network, prepared.proof, permits))
    }

    private fun prepare(context: Context, route: PairingCode.Route): Prepared {
        val manager = context.getSystemService(ConnectivityManager::class.java)
        val tunnels = snapshots(manager)
        val tunnel = tunnels.singleOrNull() ?: error("Connect one Tailscale VPN on this phone first")
        val network = manager.allNetworks.singleOrNull { it.networkHandle == tunnel.network }
            ?: error("The Tailscale connection changed")
        val numeric = TailscalePeerAddress.canonical(route.host)
        val candidates = if (numeric != null) listOf(numeric) else {
            require(TailscalePeerAddress.isMagicDnsName(route.host)) { "Expected a Tailscale peer address" }
            network.getAllByName(route.host).mapNotNull { TailscalePeerAddress.canonical(it.hostAddress.orEmpty()) }.distinct()
        }
        val peer = candidates.firstOrNull { it !in tunnel.peers }
            ?: error("The QR route did not resolve to a remote Tailscale peer")
        val proof = TailscalePathProof.prepare(tunnels, route.copy(host = peer))
        proof.validate(snapshots(manager)) // DNS must not outlive the selected network.
        return Prepared(manager, network, proof)
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
    private val network: Network,
    private val proof: TailscalePathProof,
    private val permits: () -> Boolean
) : MobileSocketAuthority {
    private val lock = Any()
    private var registered = false
    private var retired = false
    private var invalidated: (() -> Unit)? = null
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLost(network: Network) {
            if (network == this@AndroidTailscaleAuthority.network) retire()
        }
        override fun onAvailable(network: Network) = recheck()
        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            if (network == this@AndroidTailscaleAuthority.network &&
                !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) retire()
            else recheck()
        }
        override fun onLinkPropertiesChanged(network: Network, link: LinkProperties) {
            if (network == this@AndroidTailscaleAuthority.network && TailscaleRoute.snapshot(network, link) != proof.tunnel) retire()
            else recheck()
        }
    }

    override fun start(onInvalidated: () -> Unit) {
        synchronized(lock) {
            check(!retired) { "The Tailscale connection changed. Reconnect to the Mac." }
            if (registered) return
            invalidated = onInvalidated
            manager.registerNetworkCallback(NetworkRequest.Builder().clearCapabilities()
                .addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(), callback)
            registered = true
        }
        validate()
    }

    override fun validate(socket: Socket?) {
        try {
            check(permits()) { "The Tailscale authorization changed. Pair this Mac again." }
            synchronized(lock) { check(!retired) { "The Tailscale connection changed. Reconnect to the Mac." } }
            proof.validate(TailscaleRoute.snapshots(manager), socket?.localAddress?.hostAddress,
                socket?.inetAddress?.hostAddress, socket?.port)
            synchronized(lock) { check(!retired) { "The Tailscale connection changed. Reconnect to the Mac." } }
        } catch (failure: Exception) { retire(); throw failure }
    }

    private fun recheck() { try { validate() } catch (_: Exception) { /* validate retires the transport. */ } }
    private fun retire() {
        val notify = synchronized(lock) { if (retired) return; retired = true; invalidated }
        notify?.invoke()
    }
    override fun close() {
        val unregister = synchronized(lock) {
            retired = true
            invalidated = null
            registered.also { registered = false }
        }
        if (unregister) manager.unregisterNetworkCallback(callback)
    }
}
