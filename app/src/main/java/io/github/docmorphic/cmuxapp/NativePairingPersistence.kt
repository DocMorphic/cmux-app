package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** A computer owns a stable origin; adding or upgrading a route does not create a new draft/notification namespace. */
internal object NativePairingPersistence {
    fun remember(state: JSONObject, incoming: NativeCredentialStore.PairedMac,
                 team: NativeTeamScope? = null): NativeCredentialStore.PairedMac {
        if (team == null) return rememberUnscoped(state, incoming)
        check(state.optString("task_session") == team.login && state.optString("refresh_token").isNotBlank()) {
            "Account session changed. Reconnect to the Mac."
        }
        val pairing = PairingCodeParser.parse(incoming.code).getOrThrow()
        val grants = TailscaleGrantStore({ state }, { error("Read-only grant lookup") })
        when (pairing) {
            is PairingCode.Tailscale -> {
                require(pairing.stackUserId == null || pairing.stackUserId == team.userId) { "Computer account changed" }
                val grant = grants.find(team, TailscaleGrantStore.source(pairing))
                check(grant != null && grant.device == canonicalMacDeviceId(incoming.deviceId) && grant.build == incoming.instanceTag) {
                    "The Tailscale authorization changed. Pair this Mac again."
                }
            }
            is PairingCode.Iroh -> {
                require((pairing.userId == null || pairing.userId == team.userId) &&
                    (pairing.teamId == null || pairing.teamId == team.teamId)) { "Computer account or team changed" }
                require((pairing.macDeviceId == null || canonicalMacDeviceId(pairing.macDeviceId) == canonicalMacDeviceId(incoming.deviceId)) &&
                    (pairing.buildTag == null || pairing.buildTag == incoming.instanceTag)) { "Computer identity changed" }
            }
        }
        val previous = state.optJSONArray("pairings") ?: JSONArray()
        val decoded = (0 until previous.length()).mapNotNull { i ->
            previous.optJSONObject(i)?.let(NativePairingRecords::decode)?.let { i to it }
        }
        fun sameIdentity(row: NativeCredentialStore.PairedMac) =
            canonicalMacDeviceId(row.deviceId) == canonicalMacDeviceId(incoming.deviceId) && row.instanceTag == incoming.instanceTag
        val owner = team.userId to team.teamId
        val owned = decoded.filter { NativePairingRecords.owner(it.second, grants) == owner }
        check(owned.none { it.second.code == incoming.code && !sameIdentity(it.second) }) { "This pairing reaches a different Mac or installation." }
        val matches = owned.filter { sameIdentity(it.second) }
        check(matches.size <= 1) { "Multiple pairings match this Mac. Resolve them in Computers before reconnecting." }
        val existing = matches.singleOrNull()
        if (pairing is PairingCode.Tailscale) {
            // Historical native hints without sufficient scope must not be erased by a QR scan.
            val ambiguous = decoded.any { (_, row) ->
                val code = PairingCodeParser.parse(row.code).getOrNull() as? PairingCode.Iroh
                code != null && sameIdentity(row) && incoming.instanceTag != null &&
                    (code.userId == null || code.userId == team.userId) && (code.teamId == null || code.teamId == team.teamId) &&
                    NativePairingRecords.owner(row, grants) == null
            }
            check(!ambiguous) { "Reconnect this native computer first, then add its route from Computer Details." }
        }
        val keepNative = pairing is PairingCode.Tailscale && existing?.second?.let {
            PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Iroh
        } == true
        val remembered = NativePairingRecords.scoped(if (keepNative) existing!!.second else incoming, team, existing?.second?.origin)
        val next = JSONArray()
        for (index in 0 until previous.length()) next.put(if (index == existing?.first)
            NativePairingRecords.encode(remembered) else previous.get(index))
        if (existing == null) next.put(NativePairingRecords.encode(remembered))
        state.put("pairings", next).put("pairing_code", remembered.code)
        return remembered
    }

    /** Compatibility for fixtures/imports with no account authority. Production uses the scoped path above. */
    private fun rememberUnscoped(state: JSONObject, incoming: NativeCredentialStore.PairedMac): NativeCredentialStore.PairedMac {
        val previous = state.optJSONArray("pairings") ?: JSONArray()
        val next = JSONArray()
        for (index in 0 until previous.length()) {
            val item = previous.get(index)
            val existing = (item as? JSONObject)?.let(NativePairingRecords::decode)
            if (existing == null || (existing.code != incoming.code &&
                (incoming.deviceId.isBlank() || canonicalMacDeviceId(existing.deviceId) != canonicalMacDeviceId(incoming.deviceId) ||
                    existing.instanceTag != incoming.instanceTag))) next.put(item)
        }
        next.put(NativePairingRecords.encode(incoming))
        state.put("pairings", next).put("pairing_code", incoming.code)
        return incoming
    }
}
