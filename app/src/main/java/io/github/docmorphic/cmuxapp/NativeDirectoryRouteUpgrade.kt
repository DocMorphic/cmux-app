package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** An authenticated account directory can enrich an existing pairing, never create a raw address grant. */
internal object NativeDirectoryRouteUpgrade {
    fun retain(state: JSONObject, team: NativeTeamScope, directory: NativeComputersState): Boolean {
        if (directory.account != team || !directory.ready || state.optString("task_session") != team.login ||
            state.optString("refresh_token").isBlank()) return false
        val previous = state.optJSONArray("pairings") ?: return false
        val grants = TailscaleGrantStore({ state }, { error("Read-only grant lookup") })
        val computers = directory.computers.distinct().groupBy {
            NativeMacIdentity(canonicalMacDeviceId(it.deviceId), it.buildTag)
        }.mapValues { it.value.singleOrNull() }
        var changed = false
        val next = JSONArray()
        for (index in 0 until previous.length()) {
            val value = previous.get(index)
            val row = (value as? JSONObject)?.let(NativePairingRecords::decode)
            val computer = row?.instanceTag?.let { computers[NativeMacIdentity(canonicalMacDeviceId(row.deviceId), it)] }
            val upgrade = if (row != null && computer != null &&
                PairingCodeParser.parse(row.code).getOrNull() is PairingCode.Tailscale &&
                NativePairingRecords.owner(row, grants) == (team.userId to team.teamId) &&
                !NativeComputerVisibility.isHidden(state, row)) {
                val code = PairingCodeParser.computer(computer, team)
                // Validate the public locator before any write; directory presence does
                // not bypass the native backend's current permission/host checks at dial.
                val enriched = NativePairingRecords.scoped(row.copy(nativeRouteCode = code), team, row.origin)
                enriched.takeIf { NativePairingRecords.retainedNativeRoute(it) != null && it != row }
            } else null
            if (upgrade != null) { next.put(NativePairingRecords.encode(upgrade)); changed = true }
            else next.put(value)
        }
        // Empty, unavailable or ambiguous discovery never removes the native pin.
        // Keep the primary code, raw grants, ticket binding, origins and local history.
        if (changed) state.put("pairings", next)
        return changed
    }

    /** A pending reconnect may adopt only a directory-enriched locator for its same saved identity. */
    fun refreshedSelection(captured: NativeCredentialStore.PairedMac?, current: NativeCredentialStore.PairedMac?,
                           team: NativeTeamScope? = null, grants: TailscaleGrantStore? = null): NativeCredentialStore.PairedMac? {
        if (captured == null || current == null || captured == current) return captured
        if (NativePairingRecords.retainedNativeRoute(current) == null) return captured
        if (captured.copy(nativeRouteCode = current.nativeRouteCode) == current) return current
        // A historical grant-owned row may also acquire explicit scope metadata.
        // Resolve its old authority independently instead of trusting the new row's claim.
        if (team == null || grants == null || captured.accountUserId != null || captured.accountTeamId != null ||
            captured.stableOrigin != null || captured.origin != current.origin) return captured
        val sameOwner = runCatching {
            NativePairingRecords.owner(captured, grants) == (team.userId to team.teamId) &&
                NativePairingRecords.owner(current, grants) == (team.userId to team.teamId)
        }.getOrDefault(false)
        if (!sameOwner) return captured
        val scoped = NativePairingRecords.scoped(captured, team, captured.origin)
        return if (scoped.copy(nativeRouteCode = current.nativeRouteCode) == current) current else captured
    }
}
