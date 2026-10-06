package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** Committed with the authenticated pairing; cosmetic storage can finish after a restart. */
internal data class NativePairingAppearanceUpgrade(val id: String, val user: String, val team: String,
    val device: String, val build: String, val origin: String, val inherit: Boolean)

internal object NativePairingAppearanceUpgrades {
    private const val KEY = "pairing_appearance_upgrades"

    fun enqueue(state: JSONObject, owner: NativeTeamScope, mac: NativeCredentialStore.PairedMac, inherit: Boolean) {
        val build = checkNotNull(mac.instanceTag)
        val pending = read(state).filter { current(state, it) }.filterNot {
            it.user == owner.userId && it.team == owner.teamId && it.origin == mac.origin
        } + NativePairingAppearanceUpgrade(UUID.randomUUID().toString(), owner.userId, owner.teamId,
            mac.deviceId, build, mac.origin, inherit)
        write(state, pending)
    }

    fun pending(state: JSONObject?, owner: NativeTeamScope): List<NativePairingAppearanceUpgrade> =
        pending(state, owner.userId, owner.teamId)

    fun pending(state: JSONObject?, user: String, team: String): List<NativePairingAppearanceUpgrade> =
        read(state).filter { it.user == user && it.team == team }

    fun current(state: JSONObject?, move: NativePairingAppearanceUpgrade): Boolean {
        val rows = NativeComputerVisibility.saved(state)
        val grants = TailscaleGrantStore({ state }, {})
        if (rows.any { it.instanceTag == null && canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(move.device) &&
                NativePairingRecords.owner(it, grants) == (move.user to move.team) }) return false
        return rows.any {
            it.accountUserId == move.user && it.accountTeamId == move.team && it.ownsOrigin(move.origin) &&
                canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(move.device) && it.instanceTag == move.build
        }
    }

    fun acknowledge(state: JSONObject, ids: Set<String>) = write(state, read(state).filterNot { it.id in ids })

    /** Disarm queued writes before Forget clears the appearance file. */
    fun discard(state: JSONObject, owner: NativeTeamScope, target: NativeComputerTarget): List<NativePairingAppearanceUpgrade> {
        val removed = pending(state, owner).filter {
            canonicalMacDeviceId(it.device) == canonicalMacDeviceId(target.deviceId) && it.build == target.buildTag
        }
        acknowledge(state, removed.map { it.id }.toSet())
        return removed
    }

    private fun read(state: JSONObject?): List<NativePairingAppearanceUpgrade> {
        val array = state?.optJSONArray(KEY) ?: return emptyList()
        return (0 until array.length()).map { index ->
            val row = array.getJSONObject(index)
            NativePairingAppearanceUpgrade(row.getString("id"), row.getString("user"), row.getString("team"),
                row.getString("device"), row.getString("build"), row.getString("origin"), row.getBoolean("inherit"))
        }
    }

    private fun write(state: JSONObject, entries: List<NativePairingAppearanceUpgrade>) {
        if (entries.isEmpty()) state.remove(KEY)
        else state.put(KEY, JSONArray(entries.map { JSONObject().put("id", it.id).put("user", it.user).put("team", it.team)
            .put("device", it.device).put("build", it.build).put("origin", it.origin).put("inherit", it.inherit) }))
    }

    fun reconcile(owner: NativeTeamScope, read: () -> JSONObject?, update: ((JSONObject) -> Unit) -> Unit,
        appearance: NativeMacAppearanceStore, permits: () -> Boolean) {
        if (!permits()) return
        for (move in pending(read(), owner)) {
            if (!permits()) return
            if (current(read(), move)) appearance.adoptLegacy(move) {
                permits() && pending(read(), owner).contains(move) && current(read(), move)
            }
            update { state ->
                check(permits()) { "Account or team changed" }
                acknowledge(state, setOf(move.id))
            }
        }
        // An applied marker survives a failed acknowledgement, including a user
        // resetting the appearance before the next attempt. Retire only acknowledged IDs.
        appearance.retainUpgradeReceipts({ pending(read(), owner).map { it.id }.toSet() }, permits)
    }
}
