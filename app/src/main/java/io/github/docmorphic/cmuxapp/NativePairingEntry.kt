package io.github.docmorphic.cmuxapp

/** An external Intent can never stand in for a code the user scanned or entered in cmux. */
internal enum class NativePairingEntry { IN_APP, EXTERNAL_LINK }

internal object NativePairingEntryPolicy {
    const val ENTER_IN_APP = "Scan or paste the code from your Mac in cmux's pairing screen to connect over Tailscale. Opening a link from another app cannot authorize that address."
    const val NUMERIC_ADDRESS = "Use the numeric Tailscale address from your Mac's Mobile settings. A hostname cannot authorize this pairing."

    fun isExactTailscale(pairing: PairingCode.Tailscale) = pairing.routes.isNotEmpty() &&
        pairing.routes.all { TailscalePeerAddress.canonical(it.host) != null && it.port in 1..65535 }

    fun choices(ticket: MobileAttachTicket, entry: NativePairingEntry): List<NativeTicketPairingRoutes.Choice> {
        // A legacy ticket containing any self-dialing route is rejected as a whole,
        // even if another route could otherwise be used. Production never dials phone loopback.
        require(ticket.routes.none { route -> route.kind == "debug_loopback" ||
            (route.endpoint as? MobileAttachEndpoint.HostPort)?.let { BrowserLoopbackHost.matches(it.host) } == true }) {
            "This code includes a loopback address that points to the phone. Use a remote pairing code from your Mac's Mobile settings."
        }
        val candidates = NativeTicketPairingRoutes.choices(ticket)
        val tailscale = candidates.filter { (it.pairing as? PairingCode.Tailscale)?.let(::isExactTailscale) == true }
        // iOS supportedRoutes gives explicit numeric entry precedence over Automatic.
        // Saved Direct/Tailscale preferences use their own connection policy.
        return if (entry == NativePairingEntry.IN_APP && tailscale.isNotEmpty()) tailscale
            else candidates.filter { it.pairing is PairingCode.Iroh }
    }
}
