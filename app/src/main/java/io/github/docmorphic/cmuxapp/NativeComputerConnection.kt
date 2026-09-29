package io.github.docmorphic.cmuxapp

internal data class NativeComputerConnection(
    val availability: NativeFeedAvailability = NativeFeedAvailability.OFFLINE,
    val foreground: Boolean = false,
    val workspaceCount: Int? = null
) {
    val phrase get() = when (availability) {
        NativeFeedAvailability.CONNECTED -> "Connected"
        NativeFeedAvailability.CONNECTING -> "Reconnecting…"
        NativeFeedAvailability.OFFLINE -> "Not connected"
    }
}

/** Presentation only: callers supply pairings allowed in the current account/team.
 * Never infer a connection from discovery, a selected filter, or a sibling build.
 */
internal fun nativeComputerConnections(
    allowed: List<NativeCredentialStore.PairedMac>, sources: Map<String, NativeFeedSource>,
    activeCode: String?, pendingCode: String?, foregroundWorkspaces: List<NativeWorkspace>
): Map<NativeMacIdentity, NativeComputerConnection> = allowed
    .groupBy { NativeMacIdentity(it.deviceId, it.instanceTag) }
    .mapNotNull { (identity, candidates) ->
        val mac = candidates.singleOrNull() ?: return@mapNotNull null
        val source = sources[mac.origin]?.takeIf { it.mac == mac }
        val foreground = mac.code == activeCode
        val availability = when {
            foreground || source?.availability == NativeFeedAvailability.CONNECTED -> NativeFeedAvailability.CONNECTED
            mac.code == pendingCode || source?.availability == NativeFeedAvailability.CONNECTING -> NativeFeedAvailability.CONNECTING
            else -> NativeFeedAvailability.OFFLINE
        }
        val count = when {
            foreground -> foregroundWorkspaces.size
            source?.hasWorkspaceSnapshot == true -> source.workspaces.size
            else -> null
        }
        identity to NativeComputerConnection(availability, foreground, count)
    }.toMap()
