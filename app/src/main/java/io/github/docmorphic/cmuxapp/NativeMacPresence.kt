package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.io.IOException

internal data class NativeComputerPresence(val online: Boolean? = null, val lastSeenAtMillis: Long? = null)
internal data class NativeMacPresenceInstance(val identity: NativeMacIdentity, val bundleId: String?,
    val online: Boolean? = null, val lastSeenAtMillis: Long? = null)
internal data class NativeMacPresenceState(val owner: NativeTeamScope? = null,
    val instances: Map<NativeMacIdentity, NativeMacPresenceInstance> = emptyMap()) {
    private fun instance(deviceId: String, tag: String?): NativeMacPresenceInstance? {
        val device = canonicalMacDeviceId(deviceId)
        return if (tag == null) instances.values.filter { it.identity.deviceId == device }.singleOrNull()
            else instances[NativeMacIdentity(device, tag.trim().takeIf(String::isNotEmpty))]
    }
    fun buildLabel(mac: NativeCredentialStore.PairedMac) = buildLabel(mac.deviceId, mac.instanceTag)
    fun buildLabel(deviceId: String, tag: String?): String? = instance(deviceId, tag)?.let {
        NativeMacBuildLabel.label(it.bundleId, it.identity.buildTag)
    } ?: NativeMacBuildLabel.label(null, tag)

    fun presence(deviceId: String, tag: String?, savedLastSeen: Long? = null): NativeComputerPresence {
        val instance = instance(deviceId, tag)
        return NativeComputerPresence(instance?.online, listOfNotNull(instance?.lastSeenAtMillis, savedLastSeen).maxOrNull())
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
                "seen" -> {
                    val key = NativeMacIdentity(canonicalMacDeviceId(identifier(frame, "deviceId")),
                        identifier(frame, "tag").trim().takeIf(String::isNotEmpty))
                    val time = NativeMacLastSeen.timestamp(frame.opt("lastSeenAt"))
                    val previous = instances[key]
                    if (previous != null && time != null) instances = instances + (key to previous.copy(lastSeenAtMillis = time))
                }
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
        return NativeMacPresenceInstance(identity, bundle, value.opt("online") as? Boolean, NativeMacLastSeen.timestamp(value.opt("lastSeenAt")))
    }
    companion object { const val MAX_FRAME = 2 * 1024 * 1024; const val MAX_INSTANCES = 4096 }
}
