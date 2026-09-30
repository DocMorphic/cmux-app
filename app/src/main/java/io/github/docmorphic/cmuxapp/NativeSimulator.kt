package io.github.docmorphic.cmuxapp

import org.json.JSONObject
import java.util.UUID

internal data class NativeSimulator(
    val panelId: String, val workspaceId: String, val title: String,
    val selectedDeviceName: String?, val selectedDeviceState: String?, val status: String,
    val ready: Boolean, val supportsTouch: Boolean, val supportsKeyboard: Boolean,
    val supportsHardwareButtons: Boolean, val supportsRotation: Boolean,
    val ownerConnectionId: String?, val ownedByCurrentConnection: Boolean?,
) {
    fun surface() = NativeSurface(panelId, "simulatorStream", title, simulator = this)

    /** Shared updates omit personalization; a removed owner explicitly clears control. */
    fun preservingOwnership(previous: NativeSimulator?) = copy(ownedByCurrentConnection =
        ownedByCurrentConnection ?: if (ownerConnectionId == null) false
        else previous?.takeIf { it.panelId == panelId && it.workspaceId == workspaceId }?.ownedByCurrentConnection)

    companion object {
        fun read(value: JSONObject, workspaceId: String): NativeSimulator? = runCatching {
            fun requiredString(key: String) = (value.get(key) as? String) ?: error("Invalid simulator descriptor")
            fun requiredBoolean(key: String) = (value.get(key) as? Boolean) ?: error("Invalid simulator descriptor")
            fun optionalString(key: String) = if (!value.has(key) || value.isNull(key)) null else requiredString(key)
            val panel = requiredString("panel_id")
            require(UUID.fromString(panel).toString().equals(panel, ignoreCase = true))
            require(requiredString("workspace_id") == workspaceId)
            NativeSimulator(panel, workspaceId, requiredString("title"), optionalString("selected_device_name"),
                optionalString("selected_device_state"), requiredString("status"), requiredBoolean("is_ready"),
                requiredBoolean("supports_touch"), requiredBoolean("supports_keyboard"),
                requiredBoolean("supports_hardware_buttons"), requiredBoolean("supports_rotation"),
                optionalString("owner_connection_id"), if (!value.has("is_owned_by_current_connection") ||
                    value.isNull("is_owned_by_current_connection")) null else requiredBoolean("is_owned_by_current_connection"))
        }.getOrNull()
    }
}

internal data class SimulatorDevice(val udid: String, val name: String, val runtimeName: String,
    val family: String, val state: String, val selected: Boolean) {
    companion object {
        fun read(value: JSONObject): SimulatorDevice {
            fun string(key: String) = (value.get(key) as? String) ?: error("Invalid simulator device")
            val id = string("udid"); require(id.isNotBlank())
            return SimulatorDevice(id, string("name"), string("runtime_name"), string("family"), string("state"),
                value.get("is_selected") as? Boolean ?: error("Invalid simulator selection"))
        }
    }
}

/** Exact panel/workspace scope. The caller checks the current lease before and after each RPC. */
internal class SimulatorActions(val supportsDevices: Boolean, val supportsRecover: Boolean,
    private val workspaceId: String, private val panelId: String,
    private val request: suspend (String, JSONObject) -> JSONObject) {
    private fun parameters() = JSONObject().put("workspace_id", workspaceId).put("panel_id", panelId)
    suspend fun devices(): List<SimulatorDevice> {
        check(supportsDevices)
        val array = request("mobile.simulator.devices.list", parameters()).getJSONArray("devices")
        require(array.length() <= 4096)
        val devices = List(array.length()) { SimulatorDevice.read(array.getJSONObject(it)) }
        require(devices.map { it.udid }.distinct().size == devices.size)
        return devices
    }
    suspend fun select(device: SimulatorDevice) {
        check(supportsDevices && !device.selected)
        request("mobile.simulator.device.select", parameters().put("udid", device.udid))
    }
    suspend fun recover() { check(supportsRecover); request("mobile.simulator.recover", parameters()) }
}

internal fun simulatorUnavailableDetail(token: String?) = when (token) {
    "superseded" -> "Another device took over this Simulator stream."
    "panel_closed", "panel_not_found" -> "The Simulator pane was closed on the Mac."
    "simulator_disabled" -> "Simulator panes are disabled on the Mac."
    else -> "The Mac closed this Simulator stream."
}

internal val SimHostStatus?.needsRecovery get() = this in setOf(
    SimHostStatus.WORKER_CRASHED, SimHostStatus.FAILED, SimHostStatus.DEVICE_UNAVAILABLE)
