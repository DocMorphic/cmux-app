package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject

/** Last authenticated version is display metadata, outside immutable pairing/route records. */
internal object NativeMacVersionHistory {
    internal const val KEY = "computer_version_history"
    private const val LIMIT = 256
    private data class Key(val user: String, val team: String, val identity: NativeMacIdentity)

    fun read(state: JSONObject?, owner: NativeTeamScope): Map<NativeMacIdentity, String?> {
        return readDisplay(state, NativeComputerDisplayOwner(owner.login, owner.userId, owner.teamId))
    }

    fun readDisplay(state: JSONObject?, owner: NativeComputerDisplayOwner): Map<NativeMacIdentity, String?> {
        if (state == null || owner.login.isBlank() || state.optString("task_session") != owner.login ||
            state.optString("refresh_token").isBlank()) return emptyMap()
        val eligible = eligible(state)
        return decode(state).filterKeys { it.user == owner.user && it.team == owner.team && it in eligible }
            .mapKeys { it.key.identity }
    }

    /** Existing pairings only; an absent account or forgotten Mac is never recreated by a late probe. */
    fun record(state: JSONObject, owner: NativeTeamScope, versions: Map<NativeMacIdentity, String?>,
               permits: () -> Boolean): Boolean {
        if (!owns(state, owner) || !permits()) return false
        val eligible = eligible(state)
        val current = decode(state).filterKeys { it in eligible }.toMutableMap()
        var changed = false
        versions.entries.take(LIMIT).forEach { (identity, rawVersion) ->
            val key = Key(owner.userId, owner.teamId, NativeMacIdentity(canonicalMacDeviceId(identity.deviceId), identity.buildTag))
            if (key !in eligible) return@forEach
            val version = rawVersion?.take(1024)
            if (!current.containsKey(key) || current[key] != version) {
                current.remove(key); current[key] = version; changed = true
            }
        }
        if (!changed) return false
        write(state, current.entries.toList().takeLast(LIMIT).associate { it.key to it.value })
        return true
    }

    fun prune(state: JSONObject) {
        if (!state.has(KEY)) return
        val allowed = eligible(state)
        write(state, decode(state).filterKeys { it in allowed })
    }

    private fun owns(state: JSONObject, owner: NativeTeamScope) = owner.login.isNotBlank() &&
        state.optString("task_session") == owner.login && state.optString("refresh_token").isNotBlank()

    private fun eligible(state: JSONObject): Set<Key> {
        val grants = TailscaleGrantStore({ state }, { error("read only") })
        return NativeComputerVisibility.saved(state).mapNotNull { mac ->
            if (mac.deviceId.isBlank()) return@mapNotNull null
            val owner = NativePairingRecords.owner(mac, grants) ?: return@mapNotNull null
            Key(owner.first, owner.second, NativeMacIdentity(canonicalMacDeviceId(mac.deviceId), mac.instanceTag))
        }.toSet()
    }

    private fun decode(state: JSONObject): Map<Key, String?> = runCatching {
        val values = state.optJSONArray(KEY) ?: return emptyMap()
        require(values.length() <= LIMIT)
        val result = linkedMapOf<Key, String?>()
        for (index in 0 until values.length()) {
            val value = values.getJSONObject(index)
            fun id(name: String): String = (value.get(name) as String).also {
                require(it.isNotBlank() && it.length <= 128 && it == it.trim() && it.none(Char::isISOControl))
            }
            val device = id("device"); require(canonicalMacDeviceId(device) == device)
            require(value.has("tag") && value.has("version"))
            val tag = if (value.isNull("tag")) null else id("tag")
            val version = if (value.isNull("version")) null else (value.get("version") as String).also { require(it.length <= 1024) }
            val key = Key(id("user"), id("team"), NativeMacIdentity(device, tag))
            require(!result.containsKey(key)); result[key] = version
        }
        result
    }.getOrDefault(emptyMap())

    private fun write(state: JSONObject, values: Map<Key, String?>) {
        if (values.isEmpty()) { state.remove(KEY); return }
        state.put(KEY, JSONArray(values.map { (key, version) ->
            JSONObject().put("user", key.user).put("team", key.team).put("device", key.identity.deviceId)
                .put("tag", key.identity.buildTag ?: JSONObject.NULL).put("version", version ?: JSONObject.NULL)
        }))
    }
}
