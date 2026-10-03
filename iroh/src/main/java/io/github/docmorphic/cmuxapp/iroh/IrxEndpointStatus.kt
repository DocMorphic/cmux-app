package io.github.docmorphic.cmuxapp.iroh

import computer.iroh.Endpoint

/** Endpoint metadata only. A home relay is not proof of a peer's selected route. */
data class IrxEndpointStatus(val open: Boolean, val homeRelayUrl: String?) {
    companion object {
        internal fun read(endpoint: Endpoint): IrxEndpointStatus {
            if (endpoint.isClosed()) return IrxEndpointStatus(false, null)
            val relay = endpoint.addr().use { it.relayUrl() }
            return if (endpoint.isClosed()) IrxEndpointStatus(false, null) else IrxEndpointStatus(true, relay)
        }
    }
}
