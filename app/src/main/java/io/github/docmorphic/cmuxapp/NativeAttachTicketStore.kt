package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Supplemental credentials live only inside NativeCredentialStore's encrypted state. */
internal object NativeAttachTicketStore {
    const val KEY = "attach_ticket_contexts"

    /** Call after account, host and exact-route admission. This does not grant network permission. */
    fun install(state: JSONObject, team: NativeTeamScope, expected: NativeCredentialStore.PairedMac,
                ticket: MobileAttachTicket, accountEmail: String?): NativeCredentialStore.PairedMac {
        requireOwner(state, team, expected)
        require(ticket.deviceId == canonicalMacDeviceId(expected.deviceId)) { "Pairing ticket names a different Mac" }
        require(ticket.userId == null || ticket.userId == team.userId) { "Pairing ticket belongs to another account" }
        require(ticket.userEmail == null || (accountEmail != null && ticket.userEmail.trim().equals(accountEmail.trim(), true))) {
            "Pairing ticket belongs to another account"
        }
        val pairing = PairingCodeParser.parse(expected.code).getOrThrow()
        val covered = when (pairing) {
            is PairingCode.Iroh -> ticket.routes.any { route ->
                route.kind == "iroh" && (route.endpoint as? MobileAttachEndpoint.Peer)?.identity == pairing.endpointId
            }
            is PairingCode.Tailscale -> pairing.routes.all { selected -> ticket.routes.any { route ->
                val endpoint = route.endpoint as? MobileAttachEndpoint.HostPort
                // Reuse the existing public-route grammar/canonicalization; no DNS or dialing.
                route.kind == "tailscale" && endpoint != null && runCatching {
                    val host = if (':' in endpoint.host) "[${endpoint.host}]" else endpoint.host
                    val encoded = java.net.URLEncoder.encode("$host:${endpoint.port}", "UTF-8")
                    val parsed = PairingCodeParser.parse("cmux-ios://attach?v=2&r=$encoded").getOrThrow() as PairingCode.Tailscale
                    parsed.routes.single() == selected
                }.getOrDefault(false)
            } }
        }
        require(covered) { "Pairing ticket does not cover the selected route" }
        val revision = UUID.randomUUID().toString()
        val updated = expected.copy(name = rows(state).single { it.origin == expected.origin }.name, ticketRevision = revision)
        val entry = binding(updated).put("context", ticket.context().credentialJson())
        val previous = state.getJSONArray("pairings")
        val next = JSONArray()
        for (index in 0 until previous.length()) {
            val item = previous.getJSONObject(index)
            val row = NativePairingRecords.decode(item)
            next.put(if (row?.origin == expected.origin) NativePairingRecords.encode(updated) else item)
        }
        val contexts = state.optJSONObject(KEY) ?: JSONObject()
        contexts.put(revision, entry)
        state.put("pairings", next).put(KEY, contexts)
        prune(state)
        return updated
    }

    fun read(state: JSONObject?, team: NativeTeamScope, mac: NativeCredentialStore.PairedMac): MobileAttachTicketContext? {
        val current = checkNotNull(state) { "Account session changed" }
        requireOwner(current, team, mac)
        val revision = mac.ticketRevision ?: return null
        val entry = current.optJSONObject(KEY)?.optJSONObject(revision)
        check(entry != null && matches(entry, mac)) { "Saved pairing ticket changed. Pair this Mac again." }
        return try { MobileAttachTicketContext.fromCredentialJson(entry.getJSONObject("context")) }
        catch (_: Exception) { error("Saved pairing ticket is invalid. Pair this Mac again.") }
    }

    /** Forget, replacement and account cleanup cannot leave detached bearer records behind. */
    fun prune(state: JSONObject) {
        val contexts = state.optJSONObject(KEY) ?: return
        val rows = rows(state)
        for (revision in contexts.keys().asSequence().toList()) {
            val row = rows.singleOrNull { it.ticketRevision == revision }
            val entry = contexts.optJSONObject(revision)
            val valid = row != null && entry != null && matches(entry, row) && runCatching {
                MobileAttachTicketContext.fromCredentialJson(entry.getJSONObject("context"))
            }.isSuccess
            if (!valid) contexts.remove(revision)
        }
        if (contexts.length() == 0) state.remove(KEY)
    }

    private fun requireOwner(state: JSONObject, team: NativeTeamScope, mac: NativeCredentialStore.PairedMac) {
        check(state.optString("task_session") == team.login && state.optString("refresh_token").isNotBlank()) { "Account session changed" }
        check(mac.accountUserId == team.userId && mac.accountTeamId == team.teamId && mac.stableOrigin != null &&
            NativeComputerMenuPairing.isCurrent(mac, rows(state))) { "Saved pairing or account changed" }
        NativeComputerVisibility.requireVisibleHandshake(state, mac)
        check(NativePairingRecords.usable(mac, team, TailscaleGrantStore({ state }, { error("read only") }))) {
            "Saved route authorization changed"
        }
    }

    private fun rows(state: JSONObject): List<NativeCredentialStore.PairedMac> {
        val values = state.optJSONArray("pairings") ?: return emptyList()
        return (0 until values.length()).mapNotNull { values.optJSONObject(it)?.let(NativePairingRecords::decode) }
    }

    private fun binding(mac: NativeCredentialStore.PairedMac) = JSONObject()
        .put("owner_user", mac.accountUserId).put("owner_team", mac.accountTeamId).put("origin", mac.origin)
        .put("code", mac.code).put("device", canonicalMacDeviceId(mac.deviceId))
        .put("build", mac.instanceTag ?: JSONObject.NULL)

    private fun matches(entry: JSONObject, mac: NativeCredentialStore.PairedMac): Boolean {
        if (mac.accountUserId == null || mac.accountTeamId == null || mac.stableOrigin == null) return false
        val expected = binding(mac)
        return listOf("owner_user", "owner_team", "origin", "code", "device", "build").all { key ->
            entry.has(key) && entry.opt(key) == expected.opt(key)
        }
    }
}
