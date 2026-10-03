package io.github.docmorphic.cmuxapp

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import java.net.InetAddress

/** Numeric peers only. These are coordinates, not proof of a Mac's identity. */
internal object TailscalePeerAddress {
    fun canonical(raw: String): String? {
        if (raw.isEmpty() || raw.length > 64 || raw != raw.trim()) return null
        val canonical = if (':' in raw) {
            if (raw.any { it !in "0123456789abcdefABCDEF:" }) return null
            "http://[$raw]/".toHttpUrlOrNull()?.host ?: return null
        } else {
            val parts = raw.split('.')
            if (parts.size != 4 || parts.any { part ->
                part.isEmpty() || part.any { it !in '0'..'9' } ||
                    part.toIntOrNull() !in 0..255 || part.toInt().toString() != part
            }) return null
            raw
        }
        // The numeric grammar has already excluded names, zones and IPv4-mapped IPv6.
        val bytes = InetAddress.getByName(canonical).address.map { it.toInt() and 255 }
        if (':' in raw && bytes.size != 16) return null
        return when (bytes.size) {
            4 -> canonical.takeIf {
                bytes[0] == 100 && bytes[1] in 64..127 &&
                    !(bytes[1] == 100 && bytes[2] in setOf(0, 100)) &&
                    !(bytes[1] == 115 && bytes[2] in setOf(92, 93))
            }
            16 -> canonical.takeIf {
                bytes.take(6) == listOf(0xfd, 0x7a, 0x11, 0x5c, 0xa1, 0xe0) &&
                    canonical != "fd7a:115c:a1e0::53"
            }
            else -> null
        }
    }

    fun isMagicDnsName(raw: String): Boolean {
        if (raw.length > 253 || !raw.endsWith(".ts.net", ignoreCase = true)) return false
        val labels = raw.split('.')
        return labels.size >= 3 && labels.all { label ->
            label.length in 1..63 && label.first().isLetterOrDigit() && label.last().isLetterOrDigit() &&
                label.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' }
        }
    }
}

/** Pure path proof, shared by Android observations and deterministic boundary tests. */
internal data class TailscaleTunnel(val network: Long, val name: String, val peers: Set<String>)

internal data class TailscalePathProof(val tunnel: TailscaleTunnel, val route: PairingCode.Route) {
    fun validate(tunnels: List<TailscaleTunnel>, localHost: String? = null,
                 remoteHost: String? = null, remotePort: Int? = null) {
        check(tunnels.singleOrNull() == tunnel) { "The Tailscale connection changed. Reconnect to the Mac." }
        if (localHost != null) check(TailscalePeerAddress.canonical(localHost) in tunnel.peers) {
            "The connection is not using the Tailscale tunnel"
        }
        if (remoteHost != null) check(TailscalePeerAddress.canonical(remoteHost) == route.host && remotePort == route.port) {
            "The connection does not match the selected Tailscale peer"
        }
    }

    companion object {
        fun prepare(tunnels: List<TailscaleTunnel>, route: PairingCode.Route): TailscalePathProof {
            require(route.port in 1..65535)
            val peer = TailscalePeerAddress.canonical(route.host) ?: error("Expected a numeric Tailscale peer")
            val tunnel = tunnels.singleOrNull() ?: error("Connect one Tailscale VPN on this phone first")
            check(tunnel.name.isNotBlank() && tunnel.peers.isNotEmpty() &&
                tunnel.peers.all { TailscalePeerAddress.canonical(it) == it }) { "The Tailscale tunnel is unavailable" }
            check(peer !in tunnel.peers) { "The pairing route points to this phone, not the Mac" }
            return TailscalePathProof(tunnel, route.copy(host = peer))
        }
    }
}
