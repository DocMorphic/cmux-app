package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.io.IOException

internal data class NativeMacPresenceInstance(val identity: NativeMacIdentity, val bundleId: String?)
internal data class NativeMacPresenceState(val owner: NativeTeamScope? = null,
    val instances: Map<NativeMacIdentity, NativeMacPresenceInstance> = emptyMap()) {
    fun buildLabel(mac: NativeCredentialStore.PairedMac): String? {
        val device = canonicalMacDeviceId(mac.deviceId)
        val instance = if (mac.instanceTag == null) instances.values.filter { it.identity.deviceId == device }.singleOrNull()
            else instances[NativeMacIdentity(device, mac.instanceTag.trim().takeIf(String::isNotEmpty))]
        return instance?.let { NativeMacBuildLabel.label(it.bundleId, it.identity.buildTag) }
            ?: NativeMacBuildLabel.label(null, mac.instanceTag)
    }
}

/** Bounded snapshot-first projection of the official presence stream for display metadata.
 * Routes, online claims and device lists here never authorize a connection or create pairings.
 */
internal class NativeMacPresenceReducer(private val team: NativeTeamScope) {
    private var snapshotReceived = false
    private var instances = emptyMap<NativeMacIdentity, NativeMacPresenceInstance>()

    fun apply(text: String): NativeMacPresenceState {
        require(text.length <= MAX_FRAME) { "Presence frame too large" }
        val frame = JSONObject(text)
        val type = frame.getString("type")
        if (type == "snapshot") {
            require(frame.getString("teamId") == team.teamId) { "Presence team changed" }
            val devices = frame.getJSONArray("devices")
            require(devices.length() <= MAX_INSTANCES) { "Presence capacity exceeded" }
            val next = mutableMapOf<NativeMacIdentity, NativeMacPresenceInstance>()
            var count = 0
            for (i in 0 until devices.length()) {
                val device = devices.getJSONObject(i)
                val deviceId = canonicalMacDeviceId(identifier(device, "deviceId"))
                val rows = device.getJSONArray("instances")
                count += rows.length()
                require(count <= MAX_INSTANCES) { "Presence capacity exceeded" }
                for (j in 0 until rows.length()) {
                    val raw = rows.getJSONObject(j)
                    require(canonicalMacDeviceId(identifier(raw, "deviceId")) == deviceId) { "Presence identity mismatch" }
                    if (raw.getString("platform") != "mac") continue
                    val instance = instance(raw)
                    require(next.put(instance.identity, instance) == null) { "Ambiguous presence instance" }
                }
            }
            instances = next; snapshotReceived = true
        } else {
            check(snapshotReceived) { "Presence snapshot required" }
            when (type) {
                "online", "offline", "routes" -> {
                    val raw = frame.getJSONObject("instance")
                    if (raw.getString("platform") == "mac") {
                        val value = instance(raw)
                        require(value.identity in instances || instances.size < MAX_INSTANCES) { "Presence capacity exceeded" }
                        instances = instances + (value.identity to value)
                    }
                }
                "seen" -> Unit // Heartbeats carry no display metadata.
                else -> throw IOException("Unknown presence event")
            }
        }
        return NativeMacPresenceState(team, instances)
    }

    private fun identifier(value: JSONObject, key: String): String = (value.get(key) as String).also {
        require(it.length in 1..256 && it.none(Char::isISOControl)) { "Invalid presence identity" }
    }
    private fun instance(value: JSONObject): NativeMacPresenceInstance {
        val identity = NativeMacIdentity(canonicalMacDeviceId(identifier(value, "deviceId")),
            identifier(value, "tag").trim().takeIf(String::isNotEmpty))
        // Cosmetic malformed metadata cannot invalidate an otherwise usable pairing.
        val bundle = (value.opt("bundleId") as? String)?.takeIf { it.length <= 512 && it.none(Char::isISOControl) }
        return NativeMacPresenceInstance(identity, bundle)
    }
    companion object { const val MAX_FRAME = 2 * 1024 * 1024; const val MAX_INSTANCES = 4096 }
}
