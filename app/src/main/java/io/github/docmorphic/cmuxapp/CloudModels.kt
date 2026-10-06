package io.github.docmorphic.cmuxapp

import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

internal enum class CloudMachineKind(val wire: String) { BASE("base"), DESKTOP("desktop") }
internal enum class CloudTunnelPurpose(val wire: String) { TERMINAL("terminal"), BROWSER("browser") }
internal enum class CloudMachineLifecycle {
    PROVISIONING, RUNNING, PAUSED, FAILED, DESTROYED, UNKNOWN;
    val canPause get() = this == RUNNING
    val canResume get() = this == PAUSED
    val canDelete get() = this in setOf(PROVISIONING, RUNNING, PAUSED, FAILED)
}
internal data class CloudMachineResources(val vcpus: Int, val memoryMb: Int)
internal data class CloudResourcePool(val vcpus: Int, val memoryMb: Int, val usedVcpus: Int, val usedMemoryMb: Int) {
    val freeVcpus get() = (vcpus.toLong() - usedVcpus).coerceAtLeast(0).toInt()
    val freeMemoryMb get() = (memoryMb.toLong() - usedMemoryMb).coerceAtLeast(0).toInt()
    fun fits(vcpus: Int, memoryMb: Int) = vcpus > 0 && memoryMb > 0 && vcpus <= freeVcpus && memoryMb <= freeMemoryMb
}
internal data class CloudMachine(val id: String, val provider: String, val status: String,
    val displayName: String?, val slug: String?, val resources: CloudMachineResources?) {
    val preferredName get() = displayName ?: slug ?: if (id.startsWith("vm-") && id.length > 11) id.take(11) else id
    val lifecycle get() = CloudMachineLifecycle.entries.firstOrNull { it.name.lowercase(Locale.ROOT) == status.lowercase(Locale.ROOT) }
        ?: CloudMachineLifecycle.UNKNOWN
}
internal data class CloudMachineLimits(val maxActiveMachines: Int?, val activeMachineCount: Int?, val planId: String?,
    val memoryOptionsMb: List<Int>, val lockedMemoryOptionsMb: List<Int>?, val memoryUpgradePlanId: String?,
    val memoryUpgradePlansByMb: Map<String, String>?, val pool: CloudResourcePool?)
internal data class CloudMachineCatalog(val machines: List<CloudMachine>, val availableKinds: Set<CloudMachineKind>?,
    val limits: CloudMachineLimits?)
internal data class CloudMachineCreateOptions(val kind: CloudMachineKind = CloudMachineKind.BASE,
    val provider: String? = null, val image: String? = null, val persistentHome: Boolean = false,
    val perMachineHome: Boolean = false, val memoryMb: Int? = null)

/** Carries private tunnel configuration; never include it in logs or ordinary object descriptions. */
internal class CloudTunnelEnrollment(val tunnelId: String, val provider: String, val fingerprint: String,
    val clientConfig: String, val serverPublicKey: String, val endpointHost: String?, val endpointPort: Int,
    val routes: List<String>, val addressV4: String?, val addressV6: String?, val created: Boolean, val rotated: Boolean) {
    override fun toString() = "CloudTunnelEnrollment(redacted)"
}
internal class CloudAttachEndpoint(val route: String, val session: String, val invitation: Invitation?, val trustedCarrier: Boolean) {
    class Invitation(val uri: String, val id: String) { override fun toString() = "CloudInvitation(redacted)" }
    override fun toString() = "CloudAttachEndpoint(redacted)"
}

/** Parsing never upgrades an absent invitation to private-carrier trust. */
internal object CloudResponseDecoding {
    private fun JSONObject.text(key: String): String? = (opt(key) as? String)?.takeIf { it.isNotEmpty() }
    private fun JSONObject.required(key: String) = requireNotNull(text(key)) { "Cloud response is missing $key" }
    private fun number(raw: Any?): Int? = (raw as? Number)?.toDouble()?.let {
        if (it.isFinite() && it >= Int.MIN_VALUE && it <= Int.MAX_VALUE) it.toInt() else null
    }
    private fun JSONObject.number(key: String) = number(opt(key))
    private fun ints(raw: Any?): List<Int> = (raw as? JSONArray)?.let { values ->
        (0 until values.length()).mapNotNull { number(values.opt(it))?.takeIf { n -> n > 0 } }
    }.orEmpty()
    fun machine(value: JSONObject): CloudMachine {
        val resources = value.optJSONObject("resources")?.let { resource ->
            val cpu = resource.number("vcpus"); val ram = resource.number("memoryMb")
            if (cpu != null && ram != null && cpu > 0 && ram > 0) CloudMachineResources(cpu, ram) else null
        }
        return CloudMachine(value.required("id"), value.required("provider"),
            value.text("status")?.trim()?.takeIf { it.isNotEmpty() } ?: "unknown", value.text("displayName"), value.text("slug"), resources)
    }
    fun catalog(value: JSONObject): CloudMachineCatalog {
        val rows = value.getJSONArray("vms")
        require(rows.length() <= 4096) { "Too many Cloud machines" }
        val machines = (0 until rows.length()).map { machine(rows.getJSONObject(it)) }
        require(machines.map { it.id }.distinct().size == machines.size) { "Duplicate Cloud machine identity" }
        val limits = value.optJSONObject("limits")
        val kinds = limits?.optJSONArray("imageKinds")?.let { entries ->
            (0 until entries.length()).mapNotNull { i ->
                CloudMachineKind.entries.firstOrNull { it.wire == entries.optJSONObject(i)?.text("kind") }
            }.toSet()
        }
        val decoded = limits?.let {
            val cpus = it.number("poolVcpus"); val memory = it.number("poolMemoryMb")
            val pool = if (cpus != null && memory != null && cpus > 0 && memory > 0)
                CloudResourcePool(cpus, memory, (it.number("usedVcpus") ?: 0).coerceAtLeast(0), (it.number("usedMemoryMb") ?: 0).coerceAtLeast(0)) else null
            val upgrades = it.optJSONObject("memoryUpgradePlansByMb")?.let { map ->
                val keys = map.keys().asSequence().toList()
                if (keys.all { key -> map.opt(key) is String }) keys.associateWith { key -> map.getString(key) } else null
            }
            CloudMachineLimits(it.number("maxActiveVms"), it.number("activeVmCount"), it.text("planId"), ints(it.opt("memoryOptionsMb")),
                if (it.isNull("lockedMemoryOptionsMb")) null else ints(it.opt("lockedMemoryOptionsMb")),
                it.text("memoryUpgradePlanId"), upgrades, pool)
        }
        return CloudMachineCatalog(machines, kinds, decoded)
    }
    fun enrollment(value: JSONObject): CloudTunnelEnrollment {
        val port = requireNotNull(value.number("endpointPort")) { "Missing Cloud tunnel port" }
        require(port in 1..65535) { "Invalid Cloud tunnel port" }
        val address = value.optJSONObject("address")
        val routes = value.optJSONArray("routes")?.let { rows -> (0 until rows.length()).map { rows.getString(it) } }.orEmpty()
        return CloudTunnelEnrollment(value.required("tunnelId"), value.required("provider"), value.required("deviceFingerprint"),
            requireNotNull(value.opt("clientConfig") as? String) { "Missing Cloud client configuration" },
            value.required("serverPublicKey"), value.text("endpointHost"), port, routes,
            address?.text("ipv4"), address?.text("ipv6"), value.opt("created") == true, value.opt("rotated") == true)
    }
    fun attach(value: JSONObject): CloudAttachEndpoint {
        require(value.opt("transport") == "cmux-remote") { "Unsupported Cloud terminal transport" }
        val trust = value.opt("trustedCarrier")
        require(trust == null || trust == JSONObject.NULL || trust is Boolean) { "Invalid Cloud carrier trust" }
        val invitation = value.optJSONObject("invitation")?.let {
            val uri = it.text("uri"); val id = it.text("invitationId")
            if (uri != null && id != null) CloudAttachEndpoint.Invitation(uri, id) else null
        }
        val session = requireNotNull(value.opt("session") as? String) { "Missing Cloud session" }
        return CloudAttachEndpoint(value.required("route"), session, invitation, trust == true)
    }
    fun approved(value: JSONObject) = value.opt("approved") == true
}
