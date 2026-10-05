package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Scoped record metadata is independent of the route used to reach a computer. */
internal object NativePairingRecords {
    fun decode(item: JSONObject): NativeCredentialStore.PairedMac? = runCatching {
        fun optional(key: String) = if (!item.has(key) || item.isNull(key)) null else (item.get(key) as String).also {
            require(it.isNotBlank() && it.length <= 128)
        }
        val user = optional("owner_user"); val team = optional("owner_team")
        require((user == null) == (team == null))
        val origin = optional("stable_origin")
        val ticketRevision = optional("attach_ticket_revision")
        require(ticketRevision == null || (user != null && origin != null &&
            runCatching { java.util.UUID.fromString(ticketRevision).toString() == ticketRevision }.getOrDefault(false)))
        require(origin == null || (user != null && origin.length == 64 && origin.all { it in "0123456789abcdef" }))
        val aliases = if (!item.has("previous_origins")) emptySet() else {
            require(user != null && origin != null)
            val values = item.getJSONArray("previous_origins")
            (0 until values.length()).map { values.get(it) as String }.toSet().also { entries ->
                require(entries.all { it.length == 64 && it.all { c -> c in "0123456789abcdef" } })
            }
        }
        val nativeCode = if (item.isNull("native_route_code")) null else (item.get("native_route_code") as String).also {
            require(it.length in 1..4096)
        }
        NativeCredentialStore.PairedMac(item.getString("code").also { require(it.isNotBlank()) }, item.optString("device_id"),
            item.optString("name", "cmux"), optional("instance_tag"), user, team, origin, aliases.filterNot { it == origin }.toSet(), ticketRevision,
            nativeCode).also { require(nativeCode == null || retainedNativeRoute(it) != null) }
    }.getOrNull()

    fun encode(row: NativeCredentialStore.PairedMac): JSONObject = JSONObject().put("code", row.code)
        .put("device_id", row.deviceId).put("name", row.name).put("instance_tag", row.instanceTag).apply {
            row.accountUserId?.let { put("owner_user", it) }
            row.accountTeamId?.let { put("owner_team", it) }
            row.stableOrigin?.let { put("stable_origin", it) }
            row.ticketRevision?.let { put("attach_ticket_revision", it) }
            row.nativeRouteCode?.let { put("native_route_code", it) }
            if (row.previousOrigins.isNotEmpty()) put("previous_origins", JSONArray(row.previousOrigins.sorted()))
        }

    fun owner(row: NativeCredentialStore.PairedMac, grants: TailscaleGrantStore): Pair<String, String>? {
        val pairing = PairingCodeParser.parse(row.code).getOrNull() ?: return null
        val explicit = row.accountUserId?.let { user -> row.accountTeamId?.let { user to it } }
        if ((row.accountUserId == null) != (row.accountTeamId == null)) return null
        if (pairing is PairingCode.Iroh) {
            if ((pairing.macDeviceId != null && canonicalMacDeviceId(pairing.macDeviceId) != canonicalMacDeviceId(row.deviceId)) ||
                (pairing.buildTag != null && pairing.buildTag != row.instanceTag)) return null
            val hinted = pairing.userId?.let { user -> pairing.teamId?.let { user to it } }
            if (explicit != null && ((pairing.userId != null && pairing.userId != explicit.first) ||
                (pairing.teamId != null && pairing.teamId != explicit.second))) return null
            return explicit ?: hinted
        }
        pairing as PairingCode.Tailscale
        if (explicit != null && pairing.stackUserId != null && pairing.stackUserId != explicit.first) return null
        return explicit ?: grants.owners(TailscaleGrantStore.source(pairing), row.deviceId, row.instanceTag)
            .filter { pairing.stackUserId == null || pairing.stackUserId == it.first }.singleOrNull()
    }

    fun usable(row: NativeCredentialStore.PairedMac, team: NativeTeamScope, grants: TailscaleGrantStore): Boolean {
        if (owner(row, grants) != (team.userId to team.teamId)) return false
        val pairing = PairingCodeParser.parse(row.code).getOrNull() ?: return false
        if (pairing is PairingCode.Tailscale) {
            if (retainedNativeRoute(row) != null) return true
            val grant = grants.find(team, TailscaleGrantStore.source(pairing)) ?: return false
            return grant.device == canonicalMacDeviceId(row.deviceId) && grant.build == row.instanceTag
        }
        return true
    }

    /** Only an already authenticated, scoped saved row may retain another native route. */
    fun retainedNativeRoute(row: NativeCredentialStore.PairedMac): PairingCode.Iroh? {
        if (row.accountUserId == null || row.accountTeamId == null || row.stableOrigin == null) return null
        val route = row.nativeRouteCode?.let { PairingCodeParser.parse(it).getOrNull() } as? PairingCode.Iroh ?: return null
        return route.takeIf {
            (it.userId == null || it.userId == row.accountUserId) && (it.teamId == null || it.teamId == row.accountTeamId) &&
                (it.macDeviceId == null || canonicalMacDeviceId(it.macDeviceId) == canonicalMacDeviceId(row.deviceId)) &&
                (it.buildTag == null || it.buildTag == row.instanceTag)
        }
    }

    fun removeLocal(state: JSONObject, code: String, team: NativeTeamScope? = null) {
        if (team != null) check(state.optString("task_session") == team.login && state.optString("refresh_token").isNotBlank()) {
            "Account session changed"
        }
        val grants = TailscaleGrantStore({ state }, { error("read only") })
        val previous = state.optJSONArray("pairings") ?: JSONArray()
        val next = JSONArray(); val removed = mutableListOf<NativeCredentialStore.PairedMac>()
        for (index in 0 until previous.length()) {
            val item = previous.get(index)
            val row = (item as? JSONObject)?.let(::decode)
            if (row != null && row.code == code && (team == null || owner(row, grants) == (team.userId to team.teamId))) removed += row
            else next.put(item)
        }
        TailscaleGrantStore.removeForCode(state, code, team)
        state.put("pairings", next)
        NativeAttachTicketStore.prune(state)
        if (removed.any { it.code == state.optString("pairing_code") }) state.put("pairing_code", "")
        if (removed.any { it.ownsOrigin(state.optString("computer_selection")) }) state.put("computer_selection", "")
    }

    fun scoped(row: NativeCredentialStore.PairedMac, team: NativeTeamScope, retainedOrigin: String? = null) = row.copy(
        accountUserId = team.userId, accountTeamId = team.teamId,
        stableOrigin = retainedOrigin ?: MessageDigest.getInstance("SHA-256").digest(JSONArray(listOf(
            "scoped-mac-v1", team.userId, team.teamId, canonicalMacDeviceId(row.deviceId), row.instanceTag
        )).toString().toByteArray()).joinToString("") { "%02x".format(it) })
}
