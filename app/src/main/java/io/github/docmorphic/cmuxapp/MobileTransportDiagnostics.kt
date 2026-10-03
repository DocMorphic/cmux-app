package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxConnectionDiagnostics

/** Safe projection of a live transport. Never carries addresses, peer IDs or server text. */
internal data class MobileTransportDiagnostics(
    val route: Route,
    val encryption: Encryption,
    val roundTripMillis: Long? = null
) {
    enum class Route(val label: String) {
        DIRECT("Direct Peer-to-Peer"), PRIVATE_NETWORK("LAN or Private VPN"), RELAY("Relay"),
        TAILSCALE("Tailscale VPN (TCP)"), TCP("TCP"), UNAVAILABLE("No Live Route")
    }
    enum class Encryption(val label: String) {
        IROH_QUIC("Verified (Iroh QUIC)"), VPN_MANAGED("Managed by VPN"),
        UNVERIFIED("Not Verified"), UNAVAILABLE("Not Reported")
    }
    companion object {
        fun fromIroh(value: IrxConnectionDiagnostics): MobileTransportDiagnostics {
            val route = when (value.route) {
                IrxConnectionDiagnostics.Route.DIRECT -> Route.DIRECT
                IrxConnectionDiagnostics.Route.PRIVATE_NETWORK -> Route.PRIVATE_NETWORK
                IrxConnectionDiagnostics.Route.RELAY -> Route.RELAY
                IrxConnectionDiagnostics.Route.UNAVAILABLE -> Route.UNAVAILABLE
            }
            val live = route != Route.UNAVAILABLE
            return MobileTransportDiagnostics(route, if (live) Encryption.IROH_QUIC else Encryption.UNAVAILABLE,
                value.roundTripMillis?.takeIf { live && it >= 0 })
        }
        fun tcp() = MobileTransportDiagnostics(Route.TCP, Encryption.UNVERIFIED)
        // The authority verifies the tunnel and actual socket endpoints. It cannot
        // inspect the VPN's cipher or prove the identity of its Android application.
        fun tailscale() = MobileTransportDiagnostics(Route.TAILSCALE, Encryption.VPN_MANAGED)
    }
}
