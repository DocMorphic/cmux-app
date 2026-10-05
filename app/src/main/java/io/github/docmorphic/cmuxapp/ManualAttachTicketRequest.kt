package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** A successful but unusable ticket must not turn into an account-only retry on another route. */
internal class InvalidManualAttachTicket : IllegalStateException("The Mac returned an invalid or incompatible pairing ticket.")

/** Called only after the exact numeric peer has passed Tailscale consent/grant admission. */
internal object ManualAttachTicketRequest {
    fun allowsFallback(failure: Throwable): Boolean {
        if (failure !is MobileRpcException) return false
        val code = failure.code?.trim()?.lowercase(java.util.Locale.ROOT)
        val message = failure.message.orEmpty().trim().lowercase(java.util.Locale.ROOT)
        return code in setOf("method_not_found", "not_found", "unknown_method", "unsupported_method") ||
            listOf("unknown method", "method not found", "unsupported method", "ticket unavailable", "ticket not available")
                .any(message::contains)
    }

    suspend fun request(client: MobileRpcClient, route: PairingCode.Route, host: JSONObject,
                        owner: NativeTeamScope, email: String?): MobileAttachTicket? {
        require(TailscalePeerAddress.canonical(route.host) == route.host && route.port in 1..65535)
        val result = try {
            client.requestWithAttachTicketPolicy("mobile.attach_ticket.create",
                JSONObject().put("ttl_seconds", 3600).put("scope", "mac").put("target", "ticket_only"),
                ticketPolicy = MobileAttachTicketPolicy.OMIT)
        } catch (failure: MobileRpcException) {
            if (allowsFallback(failure)) return null
            throw failure
        }
        // Decode outside the fallback catch: an RPC success is never an unsupported-method response.
        return try {
            val ticket = MobileAttachTicketCodec.decodeJson(checkNotNull(result.optJSONObject("ticket")).toString()).getOrThrow()
            ticket.requireHost(host)
            ticket.requireAccount(owner.userId, email)
            ticket.constrainingRoutes(listOf(MobileAttachRoute("tailscale", "tailscale", 0,
                MobileAttachEndpoint.HostPort(route.host, route.port))),
                host.optString("mac_display_name").ifBlank { route.host })
        } catch (_: Exception) { throw InvalidManualAttachTicket() }
    }
}
