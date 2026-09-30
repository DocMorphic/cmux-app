package io.github.docmorphic.cmuxapp

import org.json.JSONObject

/** Pending entry routes, including an explicit empty value after consumption. Not a launch history. */
internal data class NativeLaunchRoutes(val pairing: String? = null, val notification: String? = null) {
    fun handledPairing(value: String) = if (pairing == value) copy(pairing = null) else this
    fun handledNotification(value: String) = if (notification == value) copy(notification = null) else this
    fun encode(): String = JSONObject().put("version", 1).put("pairing", pairing ?: JSONObject.NULL)
        .put("notification", notification ?: JSONObject.NULL).toString()
    companion object {
        const val STATE_KEY = "native_launch_routes"
        fun incoming(pairing: String?, notification: String?): NativeLaunchRoutes {
            val route = notification?.takeIf { runCatching { java.util.UUID.fromString(it).toString() == it }.getOrDefault(false) }
            return if (route != null) NativeLaunchRoutes(notification = route)
            else NativeLaunchRoutes(pairing = pairing?.takeIf { PairingCodeParser.parse(it).isSuccess })
        }
        fun decode(value: String?): NativeLaunchRoutes = runCatching {
            require(value != null && value.length <= 16_384)
            val json = JSONObject(value); require(json.opt("version") == 1)
            incoming(json.opt("pairing") as? String, json.opt("notification") as? String)
        }.getOrDefault(NativeLaunchRoutes())
    }
}

internal sealed interface NativePairingLinkAction {
    data object Wait : NativePairingLinkAction
    data object Consumed : NativePairingLinkAction
    data object Confirm : NativePairingLinkAction
    data object Unavailable : NativePairingLinkAction
    data class Select(val code: String) : NativePairingLinkAction
}

/** Receiving a link never grants Tailscale access or bypasses the current account directory. */
internal fun incomingPairingAction(value: String, signedIn: Boolean, alreadySelected: Boolean,
    team: NativeTeamScope?, computers: NativeComputersState): NativePairingLinkAction {
    if (!signedIn) return NativePairingLinkAction.Wait
    val pairing = PairingCodeParser.parse(value).getOrNull() ?: return NativePairingLinkAction.Unavailable
    if (alreadySelected) return NativePairingLinkAction.Consumed
    if (pairing is PairingCode.Tailscale) return NativePairingLinkAction.Confirm
    pairing as PairingCode.Iroh
    if (team == null) return NativePairingLinkAction.Wait
    if ((pairing.userId != null && pairing.userId != team.userId) ||
        (pairing.teamId != null && pairing.teamId != team.teamId)) return NativePairingLinkAction.Unavailable
    if (!computers.ready || computers.account != team) return NativePairingLinkAction.Wait
    val mac = computers.computers.singleOrNull { it.endpointId == pairing.endpointId &&
        (pairing.macDeviceId == null || pairing.macDeviceId.equals(it.deviceId, ignoreCase = true)) &&
        (pairing.buildTag == null || pairing.buildTag == it.buildTag) }
        ?: return NativePairingLinkAction.Unavailable
    return NativePairingLinkAction.Select(PairingCodeParser.computer(mac, team))
}
