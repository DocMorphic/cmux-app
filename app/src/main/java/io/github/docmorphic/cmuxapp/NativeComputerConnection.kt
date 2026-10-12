package io.github.docmorphic.cmuxapp

internal data class NativeComputerConnection(
    val availability: NativeFeedAvailability = NativeFeedAvailability.OFFLINE,
    val foreground: Boolean = false,
    val workspaceCount: Int? = null,
    val keepAwake: Boolean? = null,
    val transport: MobileConnectionTransport? = null
) {
    val phrase get() = when (availability) {
        NativeFeedAvailability.CONNECTED -> "Connected"
        NativeFeedAvailability.CONNECTING -> "Reconnecting…"
        NativeFeedAvailability.OFFLINE -> "Not connected"
    }
}

private fun nativeComputerConnection(mac: NativeCredentialStore.PairedMac, sources: Map<String, NativeFeedSource>,
    foreground: Boolean, pending: Boolean, workspaces: List<NativeWorkspace>, transport: MobileConnectionTransport?): NativeComputerConnection {
    val source = sources[mac.origin]?.takeIf { NativeComputerMenuPairing.isCurrent(mac, listOf(it.mac)) }
    val availability = when {
        foreground || source?.availability == NativeFeedAvailability.CONNECTED -> NativeFeedAvailability.CONNECTED
        pending || source?.availability == NativeFeedAvailability.CONNECTING -> NativeFeedAvailability.CONNECTING
        else -> NativeFeedAvailability.OFFLINE
    }
    return NativeComputerConnection(availability, foreground, when {
        foreground -> workspaces.size
        source?.hasWorkspaceSnapshot == true -> source.workspaces.size
        else -> null
    }, source?.keepAwake?.takeIf {
        source.availability == NativeFeedAvailability.CONNECTED && "caffeine.control.v1" in source.capabilities
    }, when {
        availability != NativeFeedAvailability.CONNECTED -> null
        foreground -> transport
        else -> source?.connectionTransport
    })
}

/** Display keys include account ownership. They never admit a connection or a settings action. */
internal data class NativeComputerConnectionOwner(val user: String?, val team: String?, val identity: NativeMacIdentity) {
    companion object {
        fun from(mac: NativeCredentialStore.PairedMac) = NativeComputerConnectionOwner(mac.accountUserId, mac.accountTeamId,
            NativeMacIdentity(canonicalMacDeviceId(mac.deviceId), mac.instanceTag))
    }
}
internal class NativeComputerConnectionProjection internal constructor(
    private val records: List<Pair<NativeCredentialStore.PairedMac, NativeComputerConnection>>) {
    operator fun get(mac: NativeCredentialStore.PairedMac): NativeComputerConnection? =
        records.singleOrNull { NativeComputerMenuPairing.isCurrent(mac, listOf(it.first)) }?.second
    /** Adapt selected-team consumers without exposing a foreground sibling's entry by device alone. */
    fun selectedValues(macs: List<NativeCredentialStore.PairedMac>): Map<NativeMacIdentity, NativeComputerConnection> =
        macs.groupBy { NativeMacIdentity(it.deviceId, it.instanceTag) }.mapNotNull { (identity, candidates) ->
            val mac = candidates.singleOrNull() ?: return@mapNotNull null
            get(mac)?.let { identity to it }
        }.toMap()
}

internal fun nativeRecordComputerConnections(allowed: List<NativeCredentialStore.PairedMac>, sources: Map<String, NativeFeedSource>,
    foregroundWorkspaces: List<NativeWorkspace>, foregroundTransport: MobileConnectionTransport?,
    isForeground: (NativeCredentialStore.PairedMac) -> Boolean, isPending: (NativeCredentialStore.PairedMac) -> Boolean): NativeComputerConnectionProjection =
    NativeComputerConnectionProjection(allowed.groupBy(NativeComputerConnectionOwner::from).values.mapNotNull { candidates ->
        val mac = candidates.singleOrNull() ?: return@mapNotNull null
        mac to nativeComputerConnection(mac, sources, isForeground(mac), isPending(mac), foregroundWorkspaces, foregroundTransport)
    })

/** Presentation only: callers supply pairings allowed in the current account/team.
 * Never infer a connection from discovery, a selected filter, or a sibling build.
 */
internal fun nativeComputerConnections(
    allowed: List<NativeCredentialStore.PairedMac>, sources: Map<String, NativeFeedSource>,
    activeCode: String?, pendingCode: String?, foregroundWorkspaces: List<NativeWorkspace>,
    foregroundTransport: MobileConnectionTransport? = null,
    isForeground: (NativeCredentialStore.PairedMac) -> Boolean = { it.code == activeCode }
): Map<NativeMacIdentity, NativeComputerConnection> = allowed
    .groupBy { NativeMacIdentity(it.deviceId, it.instanceTag) }
    .mapNotNull { (identity, candidates) ->
        val mac = candidates.singleOrNull() ?: return@mapNotNull null
        val foreground = mac.code == activeCode && isForeground(mac)
        identity to nativeComputerConnection(mac, sources, foreground, mac.code == pendingCode, foregroundWorkspaces, foregroundTransport)
    }.toMap()
