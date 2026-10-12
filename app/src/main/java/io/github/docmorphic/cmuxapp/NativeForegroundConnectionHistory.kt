package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** Encrypted credential-store metadata chooses a candidate, never supplies admission. */
internal class NativeForegroundConnectionHistory(private val read: () -> JSONObject?,
    private val update: ((JSONObject) -> Unit) -> Unit) {
    private fun key(team: NativeTeamScope) = JSONArray().put(team.userId).put(team.teamId).toString()
    fun record(team: NativeTeamScope, mac: NativeCredentialStore.PairedMac, permits: () -> Boolean) {
        check(mac.accountUserId == team.userId && mac.accountTeamId == team.teamId && permits()) { "Foreground history owner changed" }
        update { state ->
            check(permits()) { "Foreground history owner changed" }
            val history = state.optJSONObject("foreground_connection_history") ?: JSONObject()
            history.put(key(team), JSONObject().put("origin", mac.origin).put("device", canonicalMacDeviceId(mac.deviceId))
                .put("build", mac.instanceTag?.trim()?.takeIf(String::isNotEmpty)).put("seen", System.currentTimeMillis()))
            while (history.length() > 128) {
                val oldest = history.keys().asSequence().minByOrNull { history.optJSONObject(it)?.optLong("seen", 0) ?: 0 } ?: break
                history.remove(oldest)
            }
            state.put("foreground_connection_history", history)
        }
    }
    fun candidate(team: NativeTeamScope?, saved: List<NativeCredentialStore.PairedMac>,
        permits: (NativeCredentialStore.PairedMac) -> Boolean): NativeCredentialStore.PairedMac? {
        if (team == null) return null
        val row = read()?.optJSONObject("foreground_connection_history")?.optJSONObject(key(team)) ?: return null
        val origin = row.optString("origin").takeIf(String::isNotBlank) ?: return null
        val device = row.optString("device").takeIf(String::isNotBlank) ?: return null
        val build = (row.opt("build") as? String)?.trim()?.takeIf(String::isNotEmpty)
        return saved.singleOrNull { it.accountUserId == team.userId && it.accountTeamId == team.teamId &&
            it.ownsOrigin(origin) && canonicalMacDeviceId(it.deviceId) == device &&
            it.instanceTag?.trim()?.takeIf(String::isNotEmpty) == build }?.takeIf(permits)
    }
}
