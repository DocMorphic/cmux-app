package io.github.docmorphic.cmuxapp

import java.util.Locale
import org.json.JSONObject

/** Display-only endpoint metadata. Never pass this projection to a connector. */
internal data class NativeComputerRouteLabel(val method: NativeMacConnectionMethod?, val endpoint: String?) {
    val section: String get() = when (method) {
        NativeMacConnectionMethod.IROH -> "Iroh"
        NativeMacConnectionMethod.TAILSCALE -> "Tailscale"
        NativeMacConnectionMethod.DIRECT -> "Direct"
        null -> "Connection settings unavailable"
    }
}

internal data class NativeComputerListRow(
    val mac: NativeCredentialStore.PairedMac, val name: String, val presence: NativeComputerPresence,
    val route: NativeComputerRouteLabel, val olderPairing: Boolean = false
)

internal object NativeComputerList {
    fun tailscaleRoutes(state: JSONObject?, team: NativeTeamScope?, targets: List<NativeComputerTarget>): Map<NativeMacIdentity, String> {
        if (state == null || team == null || state.optString("task_session") != team.login ||
            state.optString("refresh_token").isBlank()) return emptyMap()
        val grants = TailscaleGrantStore({ state }, { error("Display-only grant snapshot") })
        return targets.mapNotNull { target ->
            grants.computer(team, target).firstOrNull()?.route?.let { route ->
                NativeMacConnectionPreferences.identity(target.deviceId, target.buildTag) to hostPort(route)
            }
        }.toMap()
    }

    fun route(device: String, tag: String?, preferences: NativeMacConnectionPreferences,
        directory: List<IrohV2Computer>, tailscale: Map<NativeMacIdentity, String>,
        savedCode: String? = null): NativeComputerRouteLabel {
        val parsed = savedCode?.let { PairingCodeParser.parse(it).getOrNull() }
        // Legacy TCP pairings bypass native per-Mac method preferences when dialing.
        if (parsed is PairingCode.Tailscale) return NativeComputerRouteLabel(
            NativeMacConnectionMethod.TAILSCALE, parsed.routes.firstOrNull()?.let(::hostPort))
        if (preferences.error) return NativeComputerRouteLabel(null, null)
        val identity = NativeMacIdentity(canonicalMacDeviceId(device), tag)
        val preference = preferences.values[identity] ?: NativeMacConnectionPreference()
        val peer = directory.singleOrNull { canonicalMacDeviceId(it.deviceId) == identity.deviceId && it.buildTag == tag }
            ?.endpointId ?: (parsed as? PairingCode.Iroh)?.takeIf {
                (it.macDeviceId == null || canonicalMacDeviceId(it.macDeviceId) == identity.deviceId) &&
                    (it.buildTag == null || it.buildTag == tag)
            }?.endpointId
        val endpoint = when (preference.method) {
            NativeMacConnectionMethod.DIRECT -> preference.addresses.firstOrNull { it.enabled }?.address
            NativeMacConnectionMethod.TAILSCALE -> tailscale[identity]
            NativeMacConnectionMethod.IROH -> peer?.takeIf { it.isNotBlank() && it.none(Char::isISOControl) }
                ?.let { if (it.length > 12) it.take(12) + "…" else it } ?: tailscale[identity]
        }
        return NativeComputerRouteLabel(preference.method, endpoint)
    }

    fun rows(macs: List<NativeCredentialStore.PairedMac>, appearances: NativeMacAppearances,
        presence: NativeMacPresenceState, history: Map<String, Long>, preferences: NativeMacConnectionPreferences,
        directory: List<IrohV2Computer>, tailscale: Map<NativeMacIdentity, String>): List<NativeComputerListRow> {
        val ordered = macs.map { mac ->
            NativeComputerListRow(mac, appearances.name(mac),
                presence.presence(mac.deviceId, mac.instanceTag, NativeMacLastSeen.read(history, mac)),
                route(mac.deviceId, mac.instanceTag, preferences, directory, tailscale, mac.code))
        }.sortedByDescending { it.presence.lastSeenAtMillis ?: Long.MIN_VALUE }
        val seenNames = mutableSetOf<String>()
        return ordered.map { row ->
            val name = row.name.trim().lowercase(Locale.ROOT)
            row.copy(olderPairing = !seenNames.add(name) && row.presence.online == false)
        }
    }

    /** Preserve newest-first order within each nonempty method section. */
    fun sections(rows: List<NativeComputerListRow>): List<Pair<String, List<NativeComputerListRow>>> =
        (NativeMacConnectionMethod.entries.map { it as NativeMacConnectionMethod? } + listOf(null)).mapNotNull { method ->
            rows.filter { it.route.method == method }.takeIf { it.isNotEmpty() }?.let { it.first().route.section to it }
        }

    private fun hostPort(route: PairingCode.Route): String =
        "${if (':' in route.host) "[${route.host}]" else route.host}:${route.port}"
}
