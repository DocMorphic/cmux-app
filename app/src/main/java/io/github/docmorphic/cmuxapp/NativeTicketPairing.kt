package io.github.docmorphic.cmuxapp

import java.net.URLEncoder
import java.text.Normalizer
import java.util.UUID
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Only public route data may be used by navigation, saved-state or origin hashing. */
internal object NativeTicketPairingRoutes {
    data class Choice(val code: String, val label: String, val pairing: PairingCode)

    /** iOS routeSortsBefore: ascending priority, then canonically normalized ID. */
    internal fun ordered(routes: List<MobileAttachRoute>): List<MobileAttachRoute> = routes.sortedWith { left, right ->
        left.priority.compareTo(right.priority).takeIf { it != 0 } ?: compareRouteIds(left.id, right.id)
    }

    private fun compareRouteIds(left: String, right: String): Int {
        // Swift String ordering uses Unicode scalar order after canonical
        // normalization. JVM UTF-16 order differs for supplementary characters.
        val a = Normalizer.normalize(left, Normalizer.Form.NFC)
        val b = Normalizer.normalize(right, Normalizer.Form.NFC)
        var i = 0; var j = 0
        while (i < a.length && j < b.length) {
            val x = a.codePointAt(i); val y = b.codePointAt(j)
            if (x != y) return x.compareTo(y)
            i += Character.charCount(x); j += Character.charCount(y)
        }
        return (a.length - i).compareTo(b.length - j)
    }

    fun choices(ticket: MobileAttachTicket): List<Choice> = ordered(ticket.routes).mapNotNull { route ->
        fun encode(value: String) = URLEncoder.encode(value, "UTF-8")
        val code: String
        val label: String
        when (val endpoint = route.endpoint) {
            is MobileAttachEndpoint.Peer -> {
                if (route.kind != "iroh") return@mapNotNull null
                code = "cmux-ios://attach?v=3&i=${encode(endpoint.identity)}&d=${encode(ticket.deviceId)}" +
                    (ticket.userId?.let { "&ub=${encode(it)}" } ?: "")
                label = "Native connection"
            }
            is MobileAttachEndpoint.HostPort -> {
                if (route.kind != "tailscale") return@mapNotNull null
                val host = if (':' in endpoint.host) "[${endpoint.host}]" else endpoint.host
                code = "cmux-ios://attach?v=2&r=${encode("$host:${endpoint.port}")}" +
                    (ticket.userId?.let { "&ub=${encode(it)}" } ?: "")
                label = "$host:${endpoint.port}"
            }
            is MobileAttachEndpoint.Url -> return@mapNotNull null
        }
        PairingCodeParser.parse(code).getOrNull()?.let { Choice(code, label, it) }
    }.distinctBy { it.code }

    fun covers(ticket: MobileAttachTicket, pairing: PairingCode): Boolean = when (pairing) {
        is PairingCode.Iroh -> choices(ticket).any { choice ->
            val route = choice.pairing as? PairingCode.Iroh
            route != null && route.endpointId == pairing.endpointId &&
                pairing.macDeviceId?.let(::canonicalMacDeviceId) == ticket.deviceId
        }
        is PairingCode.Tailscale -> pairing.routes.isNotEmpty() && pairing.routes.all { selected ->
            choices(ticket).any { (it.pairing as? PairingCode.Tailscale)?.routes?.contains(selected) == true }
        }
    }
}

/** ViewModel-owned, memory-only ticket input. Never saved into an Android Bundle. */
internal class NativeTicketPairing {
    class Proposal(val owner: NativeTeamScope, val ticket: MobileAttachTicket, val choices: List<NativeTicketPairingRoutes.Choice>) {
        val id = UUID.randomUUID().toString()
        override fun toString() = "TicketProposal(redacted)"
    }
    class Attempt(val owner: NativeTeamScope, val ticket: MobileAttachTicket, val code: String) {
        override fun toString() = "TicketAttempt(redacted)"
    }
    private val pendingMutable = MutableStateFlow<Proposal?>(null)
    val pending = pendingMutable.asStateFlow()
    private var attempt: Attempt? = null
    private var retiredCode: String? = null

    fun propose(ticket: MobileAttachTicket, owner: NativeTeamScope, email: String?, entry: NativePairingEntry = NativePairingEntry.EXTERNAL_LINK) {
        pendingMutable.value = null
        ticket.requireAccount(owner.userId, email)
        val choices = NativePairingEntryPolicy.choices(ticket, entry)
        require(choices.isNotEmpty()) { if (entry == NativePairingEntry.EXTERNAL_LINK) NativePairingEntryPolicy.ENTER_IN_APP
            else "This ticket has no supported remote route. Use a native or numeric Tailscale pairing code from the Mac." }
        pendingMutable.value = Proposal(owner, ticket, choices)
    }

    fun select(proposal: Proposal, choice: NativeTicketPairingRoutes.Choice, owner: NativeTeamScope,
               computers: NativeComputersState): Attempt {
        check(pendingMutable.value === proposal && proposal.owner == owner && choice in proposal.choices) { "Pairing or account changed" }
        val code = when (choice.pairing) {
            is PairingCode.Tailscale -> choice.code
            is PairingCode.Iroh -> (incomingPairingAction(choice.code, true, false, owner, computers) as? NativePairingLinkAction.Select)?.code
                ?: error("This Mac is not available in your selected team. Refresh Computers and try again.")
        }
        return Attempt(owner, proposal.ticket, code).also { attempt = it; retiredCode = null; pendingMutable.value = null }
    }

    fun current(code: String, owner: NativeTeamScope?) = attempt?.takeIf { it.code == code && it.owner == owner }
    fun resumeCode(): String? = attempt?.code ?: retiredCode
    fun requiresTicket(code: String) = attempt?.code == code || retiredCode == code
    fun isCurrent(value: Attempt) = attempt === value
    fun completed(value: Attempt) { if (attempt === value) { attempt = null; retiredCode = null } }
    fun dismiss() { pendingMutable.value = null }
    fun reconcile(owner: NativeTeamScope?) {
        if (pendingMutable.value?.owner != owner) pendingMutable.value = null
        if (attempt?.owner != owner) { retiredCode = attempt?.code ?: retiredCode; attempt = null }
    }
    fun cancel() { pendingMutable.value = null; retiredCode = attempt?.code ?: retiredCode; attempt = null }
    fun clear() { pendingMutable.value = null; attempt = null; retiredCode = null }
}
