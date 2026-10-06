package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONObject

/** Follow only a persisted, scoped successor that still owns this computer's history. */
internal fun refreshedNativeSavedSelection(captured: NativeCredentialStore.PairedMac?,
    rows: List<NativeCredentialStore.PairedMac>, team: NativeTeamScope?): NativeCredentialStore.PairedMac? {
    if (captured == null || team == null || rows.contains(captured)) return captured
    return rows.singleOrNull { row ->
        row.stableOrigin != null && row.ownsOrigin(captured.origin) &&
            canonicalMacDeviceId(row.deviceId) == canonicalMacDeviceId(captured.deviceId) &&
            row.instanceTag != null && (captured.instanceTag == null || captured.instanceTag == row.instanceTag) &&
            row.accountUserId == team.userId && row.accountTeamId == team.teamId &&
            (captured.accountUserId == null || captured.accountUserId == team.userId) &&
            (captured.accountTeamId == null || captured.accountTeamId == team.teamId)
    } ?: captured
}

/** Background readers retire their captured session after an authenticated identity change. */
internal suspend fun refreshNativeSavedIdentity(
    mac: NativeCredentialStore.PairedMac,
    selectedCode: String,
    status: JSONObject,
    team: NativeTeamScope,
    permits: () -> Boolean,
    authenticate: suspend () -> Unit,
    persist: (NativeCredentialStore.PairedMac) -> Unit
): Boolean {
    check(permits()) { "Saved computer or account changed" }
    mac.requireMatchingHost(status)
    val raw = status.opt("mac_instance_tag")
    val build = if (raw == null || raw === JSONObject.NULL || raw == "") null else {
        require(raw is String && raw.isNotBlank() && raw == raw.trim() && raw.length <= 64 && raw.none(Char::isISOControl)) {
            "The Mac returned an invalid build identity"
        }
        raw
    }
    val target = NativeComputerTarget.from(mac, team)
    check(target == null || target.buildTag == build) { "This route reaches a different cmux installation." }
    if (build == mac.instanceTag && selectedCode == mac.code) return false
    // The selected route came from connectSaved, not host-supplied locator data.
    val incoming = mac.copy(code = selectedCode, instanceTag = build,
        name = status.optString("mac_display_name").ifBlank { mac.name }, ticketRevision = null, nativeRouteCode = null)
    authenticate()
    currentCoroutineContext().ensureActive()
    check(permits()) { "Saved computer or account changed" }
    persist(incoming)
    // Storage invalidates the old row's admission. Callers must close it and let
    // their store-revision reconciliation start a session under the new record.
    return true
}
