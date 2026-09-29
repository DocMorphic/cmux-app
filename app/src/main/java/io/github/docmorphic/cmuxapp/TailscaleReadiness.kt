package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException

internal data class TailscaleObservation(val generation: Long, val tunnels: List<TailscaleTunnel>)

internal class TailscaleReadinessException : IOException(
    "The Tailscale VPN did not become ready. Open Tailscale, connect this phone, then try again."
)

/** Waits for callback evidence; startup snapshots alone cannot authorize a socket. */
internal object TailscaleReadiness {
    const val DEADLINE_MILLIS = 10_000L
    suspend fun await(observations: StateFlow<TailscaleObservation?>, route: PairingCode.Route,
                      permits: () -> Boolean, timeoutMillis: Long = DEADLINE_MILLIS,
                      permissionPollMillis: Long = 200): TailscaleObservation {
        require(timeoutMillis > 0 && permissionPollMillis > 0)
        require(route.port in 1..65535)
        val peer = TailscalePeerAddress.canonical(route.host)
        require(peer != null || TailscalePeerAddress.isMagicDnsName(route.host)) { "Expected a Tailscale peer address" }
        return withTimeoutOrNull(timeoutMillis) {
            var ready: TailscaleObservation? = null
            while (ready == null) {
                currentCoroutineContext().ensureActive()
                check(permits()) { "The Tailscale authorization changed. Pair this Mac again." }
                val latest = observations.value
                if (latest?.tunnels?.size == 1) {
                    val tunnel = latest.tunnels.single()
                    check(peer == null || peer !in tunnel.peers) { "The pairing route points to this phone, not the Mac" }
                    ready = latest
                } else withTimeoutOrNull(permissionPollMillis) { observations.first { it != latest } }
            }
            check(permits()) { "The Tailscale authorization changed. Pair this Mac again." }
            ready
        } ?: throw TailscaleReadinessException()
    }
}

/** Callback cache. Incomplete capability/link deliveries are never treated as a usable tunnel. */
internal class TailscaleObservations {
    private data class Entry(val vpn: Boolean? = null, val tunnel: TailscaleTunnel? = null, val blocked: Boolean = false)
    private val lock = Any()
    private val entries = mutableMapOf<Long, Entry>()
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow<TailscaleObservation?>(null)
    val state: StateFlow<TailscaleObservation?> = mutableState
    fun available(network: Long) = update { entries.putIfAbsent(network, Entry()) }
    fun capabilities(network: Long, vpn: Boolean) = update {
        entries[network]?.let { entries[network] = it.copy(vpn = vpn) }
    }
    fun link(network: Long, tunnel: TailscaleTunnel?) = update {
        entries[network]?.let { entries[network] = it.copy(tunnel = tunnel) }
    }
    fun blocked(network: Long, blocked: Boolean) = update {
        entries[network]?.let { entries[network] = it.copy(blocked = blocked) }
    }
    fun lost(network: Long) = update { entries.remove(network) }
    private fun update(change: () -> Unit) = synchronized(lock) {
        change()
        val tunnels = entries.values.mapNotNull { it.tunnel?.takeIf { _ -> it.vpn == true && !it.blocked } }
            .sortedBy { it.network }
        val old = mutableState.value
        if (old == null || old.tunnels != tunnels)
            mutableState.value = TailscaleObservation((old?.generation ?: 0) + 1, tunnels)
    }
}
