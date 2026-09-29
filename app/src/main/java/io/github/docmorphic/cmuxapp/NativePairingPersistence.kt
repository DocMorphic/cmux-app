package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** A QR route is an additional coordinate, never a replacement for a known scoped native identity. */
internal object NativePairingPersistence {
    fun remember(state: JSONObject, incoming: NativeCredentialStore.PairedMac,
                 team: NativeTeamScope? = null): NativeCredentialStore.PairedMac {
        val pairing = PairingCodeParser.parse(incoming.code).getOrNull()
        if (team != null) {
            check(state.optString("task_session") == team.login && state.optString("refresh_token").isNotBlank()) {
                "Account session changed. Reconnect to the Mac."
            }
            if (pairing is PairingCode.Tailscale) {
                val grant = TailscaleGrantStore({ state }, { error("Read-only grant lookup") })
                    .find(team, TailscaleGrantStore.source(pairing))
                check(grant != null && grant.device == canonicalMacDeviceId(incoming.deviceId) && grant.build == incoming.instanceTag) {
                    "The Tailscale authorization changed. Pair this Mac again."
                }
                val native = nativeIdentity(state, team, incoming)
                if (native != null) {
                    // Keep code, name and origin stable: drafts, notifications and local
                    // connection preferences still refer to this same computer/build.
                    state.put("pairing_code", native.code)
                    return native
                }
            } else if (pairing is PairingCode.Iroh) {
                require((pairing.userId == null || pairing.userId == team.userId) &&
                    (pairing.teamId == null || pairing.teamId == team.teamId)) { "Computer account or team changed" }
                require((pairing.macDeviceId == null || canonicalMacDeviceId(pairing.macDeviceId) == canonicalMacDeviceId(incoming.deviceId)) &&
                    (pairing.buildTag == null || pairing.buildTag == incoming.instanceTag)) { "Computer identity changed" }
            }
        }
        val previous = state.optJSONArray("pairings")
        val next = JSONArray()
        if (previous != null) for (index in 0 until previous.length()) {
            val item = previous.optJSONObject(index) ?: continue
            val existing = decode(item)
            // Fully scoped native rows from another team/account must survive this write.
            val otherOwner = team != null && (PairingCodeParser.parse(existing.code).getOrNull() as? PairingCode.Iroh)?.let {
                (it.userId != null && it.userId != team.userId) || (it.teamId != null && it.teamId != team.teamId)
            } == true
            if (otherOwner || (existing.code != incoming.code &&
                (incoming.deviceId.isBlank() || canonicalMacDeviceId(existing.deviceId) != canonicalMacDeviceId(incoming.deviceId) ||
                    existing.instanceTag != incoming.instanceTag))) next.put(item)
        }
        next.put(JSONObject().put("code", incoming.code).put("device_id", incoming.deviceId)
            .put("name", incoming.name).put("instance_tag", incoming.instanceTag))
        state.put("pairings", next).put("pairing_code", incoming.code)
        return incoming
    }

    private fun nativeIdentity(state: JSONObject, team: NativeTeamScope,
                               incoming: NativeCredentialStore.PairedMac): NativeCredentialStore.PairedMac? {
        val rows = state.optJSONArray("pairings") ?: return null
        val matches = (0 until rows.length()).mapNotNull { index -> rows.optJSONObject(index)?.let(::decode) }.filter { row ->
            val code = PairingCodeParser.parse(row.code).getOrNull() as? PairingCode.Iroh ?: return@filter false
            if (canonicalMacDeviceId(row.deviceId) != canonicalMacDeviceId(incoming.deviceId) ||
                row.instanceTag != incoming.instanceTag || incoming.instanceTag == null) return@filter false
            if ((code.userId != null && code.userId != team.userId) || (code.teamId != null && code.teamId != team.teamId)) return@filter false
            // An unscoped historical hint cannot authorize replacing or merging a native identity.
            check(code.userId == team.userId && code.teamId == team.teamId &&
                code.macDeviceId?.let(::canonicalMacDeviceId) == canonicalMacDeviceId(incoming.deviceId) &&
                code.buildTag == incoming.instanceTag) { "Reconnect this native computer first, then add its route from Computer Details." }
            true
        }
        check(matches.size <= 1) { "Multiple native pairings match this Mac. Add its route from Computer Details." }
        return matches.singleOrNull()
    }

    private fun decode(item: JSONObject) = NativeCredentialStore.PairedMac(item.optString("code"), item.optString("device_id"),
        item.optString("name", "cmux"), item.optString("instance_tag").takeIf { !item.isNull("instance_tag") && it.isNotBlank() })
}
