package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Display history lives outside pairing records so updating it cannot retire a live route. */
internal object NativeMacLastSeen {
    private const val KEY = "computer_last_seen"
    fun values(state: JSONObject?): Map<String, Long> = state?.optJSONObject(KEY)?.let { values ->
        values.keys().asSequence().take(4096).mapNotNull { key -> timestamp(values.opt(key))?.let { key to it } }.toMap()
    }.orEmpty()
    fun read(state: JSONObject?, mac: NativeCredentialStore.PairedMac): Long? = read(values(state), mac)
    fun read(values: Map<String, Long>, mac: NativeCredentialStore.PairedMac): Long? = mac.origins.mapNotNull(values::get).maxOrNull()

    fun record(state: JSONObject, login: String?, mac: NativeCredentialStore.PairedMac, time: Long,
        permits: () -> Boolean): Boolean {
        if (login.isNullOrBlank() || state.optString("task_session") != login || state.optString("refresh_token").isBlank() ||
            timestamp(time) == null || !permits()) return false
        val rows = saved(state)
        if (!NativeComputerMenuPairing.isCurrent(mac, rows)) return false
        val previous = read(state, mac)
        if (previous != null && previous >= time) return false
        val values = state.optJSONObject(KEY) ?: JSONObject()
        values.put(mac.origin, time); state.put(KEY, values)
        prune(state)
        return true
    }
    fun prune(state: JSONObject) {
        val values = state.optJSONObject(KEY) ?: return
        val retained = saved(state).flatMap { it.origins }.toSet()
        val newest = values.keys().asSequence().filter { it in retained }
            .mapNotNull { key -> timestamp(values.opt(key))?.let { key to it } }
            .sortedByDescending { it.second }.take(4096).toList()
        state.put(KEY, JSONObject().apply { newest.forEach { (key, time) -> put(key, time) } })
    }
    private fun saved(state: JSONObject) = state.optJSONArray("pairings")?.let { rows ->
        (0 until rows.length()).mapNotNull { rows.optJSONObject(it)?.let(NativePairingRecords::decode) }
    }.orEmpty()
    internal fun timestamp(value: Any?): Long? = (value as? Number)?.toDouble()?.takeIf {
        it.isFinite() && it > 0 && it <= 253402300799999.0
    }?.toLong()
}
