package io.github.docmorphic.cmuxapp

internal data class NativeSavedMacRoute(val code: String, val pairing: PairingCode, val usesPrimaryTicket: Boolean)

/** Connection method selects among locally retained native identities and address grants, never ticket-advertised peers. */
internal fun nativeSavedMacRoute(mac: NativeCredentialStore.PairedMac, method: NativeMacConnectionMethod): NativeSavedMacRoute {
    val primary = PairingCodeParser.parse(mac.code).getOrThrow()
    val native = NativePairingRecords.retainedNativeRoute(mac)
    return if (native != null && ((primary is PairingCode.Tailscale && method != NativeMacConnectionMethod.TAILSCALE) ||
            (primary is PairingCode.Iroh && mac.instanceTag == null)))
        NativeSavedMacRoute(checkNotNull(mac.nativeRouteCode), native, false)
    else NativeSavedMacRoute(mac.code, primary, true)
}
