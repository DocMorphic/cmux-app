package io.github.docmorphic.cmuxapp.iroh

import computer.iroh.PathSnapshot
import java.net.InetAddress
import java.net.URI

/** Address-free projection. Native endpoint ids, peer IPs and relay URLs never leave this boundary. */
data class IrxConnectionDiagnostics(val route: Route, val roundTripMillis: Long?) {
    enum class Route { UNAVAILABLE, DIRECT, PRIVATE_NETWORK, RELAY }

    companion object {
        fun fromPaths(paths: List<PathSnapshot>): IrxConnectionDiagnostics {
            val selected = paths.singleOrNull { it.isSelected }
                ?: return IrxConnectionDiagnostics(Route.UNAVAILABLE, null)
            val route = when {
                selected.isRelay && !selected.isIp -> Route.RELAY
                selected.isIp && !selected.isRelay -> when (privateAddress(selected.remoteAddr)) {
                    true -> Route.PRIVATE_NETWORK
                    false -> Route.DIRECT
                    null -> Route.UNAVAILABLE
                }
                else -> Route.UNAVAILABLE
            }
            val rtt = selected.rttMs.takeIf { route != Route.UNAVAILABLE && it <= Long.MAX_VALUE.toULong() }?.toLong()
            return IrxConnectionDiagnostics(route, rtt)
        }

        private fun privateAddress(address: String): Boolean? = runCatching {
            val host = URI("udp://$address").host?.removeSurrounding("[", "]")?.substringBefore('%') ?: return null
            // Resolve only numeric literals. A diagnostic check must never issue a DNS lookup.
            val numeric = if (':' in host) host.matches(Regex("[0-9a-fA-F:]+")) else
                host.split('.').let { it.size == 4 && it.all { part -> part.isNotEmpty() && part.all(Char::isDigit) && (part.toIntOrNull() ?: -1) in 0..255 } }
            if (!numeric) return null
            val ip = InetAddress.getByName(host)
            val bytes = ip.address
            ip.isLoopbackAddress || ip.isLinkLocalAddress || ip.isSiteLocalAddress ||
                (bytes.size == 16 && (bytes[0].toInt() and 0xfe) == 0xfc) ||
                (bytes.size == 4 && bytes[0].toInt() == 100 && (bytes[1].toInt() and 0xff) in 64..127)
        }.getOrNull()
    }
}
