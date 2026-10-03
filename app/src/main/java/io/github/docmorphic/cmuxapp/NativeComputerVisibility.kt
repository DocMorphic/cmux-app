package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Local visibility metadata; hiding never edits a pairing, grant or remote account binding. */
internal object NativeComputerVisibility {
    private const val KEY = "hidden_computer_origins"
    fun hiddenOrigins(state: JSONObject?): Set<String> = state?.optJSONObject(KEY)?.let { values ->
        values.keys().asSequence().filter { values.opt(it) == true }.toSet()
    }.orEmpty()
    fun isHidden(origins: Set<String>, mac: NativeCredentialStore.PairedMac) = mac.origins.any { it in origins }
    fun isHidden(state: JSONObject?, mac: NativeCredentialStore.PairedMac) = isHidden(hiddenOrigins(state), mac)
    fun saved(state: JSONObject?): List<NativeCredentialStore.PairedMac> = state?.optJSONArray("pairings")?.let { rows ->
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(NativePairingRecords::decode) }
    }.orEmpty()

    private fun sameComputer(a: NativeCredentialStore.PairedMac, b: NativeCredentialStore.PairedMac, state: JSONObject): Boolean {
        return a.deviceId.isNotBlank() && canonicalMacDeviceId(a.deviceId) == canonicalMacDeviceId(b.deviceId) &&
            a.instanceTag == b.instanceTag && sameOwner(a, b, state)
    }
    private fun sameOwner(a: NativeCredentialStore.PairedMac, b: NativeCredentialStore.PairedMac, state: JSONObject): Boolean {
        val grants = TailscaleGrantStore({ state }, { error("read only") })
        return NativePairingRecords.owner(a, grants) == NativePairingRecords.owner(b, grants)
    }

    fun setVisible(state: JSONObject, login: String?, captured: NativeCredentialStore.PairedMac,
        visible: Boolean, permits: () -> Boolean): Boolean {
        if (login.isNullOrBlank() || state.optString("task_session") != login || state.optString("refresh_token").isBlank() || !permits()) return false
        val saved = saved(state)
        if (!NativeComputerMenuPairing.isCurrent(captured, saved)) return false
        val targets = saved.filter { it.origin == captured.origin || sameComputer(it, captured, state) }
        val values = state.optJSONObject(KEY) ?: JSONObject()
        targets.flatMap { it.origins }.forEach { if (visible) values.remove(it) else values.put(it, true) }
        state.put(KEY, values)
        if (!visible) {
            if (targets.any { it.code == state.optString("pairing_code") }) state.put("pairing_code", "")
            if (targets.any { it.ownsOrigin(state.optString("computer_selection")) }) state.put("computer_selection", "")
        }
        prune(state)
        return true
    }

    /** Also rejects a late handshake that would otherwise re-enrich a just-hidden saved row. */
    fun requireVisibleHandshake(state: JSONObject, incoming: NativeCredentialStore.PairedMac) {
        val hidden = hiddenOrigins(state)
        check(!isHidden(hidden, incoming) && saved(state).none {
            isHidden(hidden, it) && ((it.code == incoming.code && sameOwner(it, incoming, state)) || sameComputer(it, incoming, state))
        }) { "This computer is hidden on this phone. Show it in Computers before connecting." }
    }

    fun prune(state: JSONObject) {
        val values = state.optJSONObject(KEY) ?: return
        val retained = saved(state).flatMap { it.origins }.toSet()
        state.put(KEY, JSONObject().apply {
            values.keys().asSequence().filter { it in retained && values.opt(it) == true }.forEach { put(it, true) }
        })
    }
}
