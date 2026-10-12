package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** One rendered action captures the pairing, its account owner and the actual connection generation. */
internal data class NativeLocalPairingRemoval(val login: String, val team: NativeTeamScope?,
    val mac: NativeCredentialStore.PairedMac?, val code: String) {
    fun remove(state: JSONObject, permits: () -> Boolean) {
        check(state.optString("task_session") == login && state.optString("refresh_token").isNotBlank() && permits()) {
            "Account or pairing changed. Reopen Settings."
        }
        val previous = state.optJSONArray("pairings") ?: JSONArray()
        val rows = (0 until previous.length()).mapNotNull { previous.optJSONObject(it)?.let(NativePairingRecords::decode) }
        if (mac == null) {
            check(rows.none { it.code == code }) { "Choose the saved computer before removing its pairing." }
        } else {
            check(mac.code == code && NativeComputerMenuPairing.isCurrent(mac, rows)) { "Saved pairing changed. Reopen Settings." }
            if (team != null) {
                check(team.login == login && NativePairingRecords.owner(mac, TailscaleGrantStore({ state }, { error("read only") })) ==
                    (team.userId to team.teamId)) { "Pairing account changed. Reopen Settings." }
            } else check(mac.accountUserId == null && mac.accountTeamId == null) { "Pairing account is unavailable." }
        }
        val next = JSONArray()
        for (index in 0 until previous.length()) {
            val item = previous.get(index)
            val row = (item as? JSONObject)?.let(NativePairingRecords::decode)
            if (mac == null || row == null || !NativeComputerMenuPairing.isCurrent(mac, listOf(row))) next.put(item)
        }
        check(permits()) { "Account or pairing changed. Reopen Settings." }
        state.put("pairings", next)
        if (mac != null && team != null) TailscaleGrantStore.removeComputer(state, team, mac.deviceId, mac.instanceTag)
        NativeAttachTicketStore.prune(state)
        if (state.optString("pairing_code") == code) state.put("pairing_code", "")
        if (mac?.ownsOrigin(state.optString("computer_selection")) == true) state.put("computer_selection", "")
    }
}

/** A same public locator in another team cannot stop the already admitted root. */
internal fun nativeForgetOwnsRoot(owner: NativeTeamScope, target: NativeComputerTarget,
    captured: List<NativeCredentialStore.PairedMac>, admitted: NativeCredentialStore.PairedMac?,
    expected: NativeCredentialStore.PairedMac?, code: String, ready: Boolean): Boolean {
    val live = admitted?.takeIf { it.code == code }
    val pending = expected?.takeIf { it.code == code }
    val record = if (ready) live else pending ?: live
    if (record != null && record.code == code && captured.any { NativeComputerMenuPairing.isCurrent(record, listOf(it)) } &&
        NativeComputerTarget.from(record, owner)?.let {
            canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(target.deviceId) && it.buildTag == target.buildTag
        } == true) return true
    return record == null && NativeComputerForgetLocal.ownsForeground(code, owner, target, emptyList())
}
