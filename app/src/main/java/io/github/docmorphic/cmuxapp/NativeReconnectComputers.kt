package io.github.docmorphic.cmuxapp

/** Saved records remain the reconnect source even during discovery outages.
 * Discovery can add a new exact device/build, never replace a saved route. */
internal data class NativeReconnectComputers(
    val saved: List<NativeComputerListRow>, val discovered: List<IrohV2Computer>
) {
    val isEmpty: Boolean get() = saved.isEmpty() && discovered.isEmpty()

    companion object {
        fun merge(saved: List<NativeComputerListRow>, directory: List<IrohV2Computer>, hidden: List<NativeCredentialStore.PairedMac> = emptyList()): NativeReconnectComputers {
            val known = (saved.map { it.mac } + hidden).map { NativeMacIdentity(canonicalMacDeviceId(it.deviceId), it.instanceTag) }.toSet()
            val discovered = directory.distinct().groupBy {
                NativeMacIdentity(canonicalMacDeviceId(it.deviceId), it.buildTag)
            }.filterKeys { it !in known }.values.mapNotNull { it.singleOrNull() }
            return NativeReconnectComputers(saved, discovered)
        }

        fun currentDiscovery(mac: IrohV2Computer, state: NativeComputersState, owner: NativeTeamScope?): Boolean =
            owner != null && state.account == owner && state.ready && state.computers.distinct().filter {
                canonicalMacDeviceId(it.deviceId) == canonicalMacDeviceId(mac.deviceId) && it.buildTag == mac.buildTag
            }.singleOrNull() == mac
    }
}

/** Keep account and persisted locator changes in the foreground connection's effect key. */
internal data class NativeForegroundReconnectKey(val owner: NativeTeamScope?, val nativeRoute: String?, val connection: String?)

internal fun nativeForegroundReconnectKey(state: NativeComputersState,
    saved: List<NativeCredentialStore.PairedMac>, team: NativeTeamScope?, code: String): NativeForegroundReconnectKey {
    val mac = saved.singleOrNull { it.code == code }
    val target = if (team == null || mac == null) null else NativeComputerTarget.from(mac, team)
    return NativeForegroundReconnectKey(team, mac?.nativeRouteCode,
        state.connectionKey(PairingCodeParser.parse(code).getOrNull() as? PairingCode.Iroh, target))
}
