package io.github.docmorphic.cmuxapp

/** Memory-only captured authority. Neither a ticket nor an Android Intent can construct it. */
class NativeTicketConnectionAdmission internal constructor(
    internal val savedGrant: TailscaleSavedGrant? = null,
    private val current: () -> Boolean
) {
    internal fun requireCurrent() = check(current()) { "The saved route or connection settings changed. Open the link again." }
    override fun toString() = "TicketConnectionAdmission(redacted)"
}

/** External links reuse independently authenticated address grants; they never grant a new address. */
internal class NativeExternalTicketRoutes(
    private val owner: NativeTeamScope,
    private val grants: TailscaleGrantStore,
    private val saved: () -> List<NativeCredentialStore.PairedMac>,
    private val computers: () -> NativeComputersState,
    private val preferences: () -> NativeMacConnectionPreferences,
    private val isCurrent: () -> Boolean
) {
    fun choices(ticket: MobileAttachTicket): Map<NativeTicketPairingRoutes.Choice, NativeTicketConnectionAdmission> {
        // Apply whole-ticket loopback validation before considering any stored authority.
        NativePairingEntryPolicy.choices(ticket, NativePairingEntry.EXTERNAL_LINK)
        val candidates = NativeTicketPairingRoutes.choices(ticket)
        val hasTicketNative = candidates.any { it.pairing is PairingCode.Iroh }
        fun resolved(choice: NativeTicketPairingRoutes.Choice): PairingCode.Iroh? =
            (incomingPairingAction(choice.code, true, false, owner, computers()) as? NativePairingLinkAction.Select)
                ?.code?.let { PairingCodeParser.parse(it).getOrNull() as? PairingCode.Iroh }
        fun nativeTargets() = candidates.filter { it.pairing is PairingCode.Iroh }.mapNotNull { choice ->
            resolved(choice)?.let { native -> native.buildTag?.let { NativeComputerTarget(ticket.deviceId, it, "Mac") } }
        }.distinct()
        val capturedTargets = nativeTargets()
        fun intent(build: String?): NativeMacDialIntent {
            val values = preferences()
            check(!values.error) { "Could not read this phone’s connection settings." }
            return build?.let { values.intent(NativeComputerTarget(ticket.deviceId, it, "Mac")) }
                ?: NativeMacDialIntent(recovery = values.recovery)
        }
        return buildMap {
            for (choice in candidates) {
                when (val pairing = choice.pairing) {
                    is PairingCode.Tailscale -> {
                        if (!NativePairingEntryPolicy.isExactTailscale(pairing)) continue
                        val route = pairing.routes.singleOrNull() ?: continue
                        if (capturedTargets.size > 1) continue
                        val grant = grants.destination(owner, ticket.deviceId, route, capturedTargets.singleOrNull()) ?: continue
                        val captured = intent(grant.build)
                        fun permits() = isCurrent() && nativeTargets() == capturedTargets && intent(grant.build) == captured &&
                            grants.find(owner, grant.source) == grant &&
                            NativeLegacyTailscalePolicy.permits(captured.method, hasTicketNative ||
                                NativeLegacyTailscalePolicy.hasNativeIdentity(grant, owner, saved(), grants))
                        if (permits()) put(choice, NativeTicketConnectionAdmission(grant) { runCatching { permits() }.getOrDefault(false) })
                    }
                    is PairingCode.Iroh -> {
                        // Keep a cold-launch proposal available while discovery loads.
                        // Its first confirmation captures the resolved identity and method;
                        // a ticket's own device claim never supplies the build preference.
                        val lock = Any()
                        var native = resolved(choice)
                        var captured = native?.let { intent(it.buildTag) }
                        if (captured?.method == NativeMacConnectionMethod.TAILSCALE) continue
                        put(choice, NativeTicketConnectionAdmission {
                            runCatching { synchronized(lock) {
                                val now = resolved(choice)
                                if (!isCurrent() || now == null) false else {
                                    val currentIntent = intent(now.buildTag)
                                    if (native == null && currentIntent.method != NativeMacConnectionMethod.TAILSCALE) {
                                        native = now; captured = currentIntent
                                    }
                                    now == native && currentIntent == captured && currentIntent.method != NativeMacConnectionMethod.TAILSCALE
                                }
                            } }.getOrDefault(false)
                        })
                    }
                }
            }
        }
    }
}
