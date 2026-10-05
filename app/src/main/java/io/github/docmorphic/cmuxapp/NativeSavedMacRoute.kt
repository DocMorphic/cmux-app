package io.github.docmorphic.cmuxapp

internal data class NativeSavedMacRoute(val code: String, val pairing: PairingCode, val usesPrimaryTicket: Boolean)

/** Connection method selects among previously authenticated routes, never ticket-advertised peers. */
internal fun nativeSavedMacRoute(mac: NativeCredentialStore.PairedMac, method: NativeMacConnectionMethod): NativeSavedMacRoute {
    val primary = PairingCodeParser.parse(mac.code).getOrThrow()
    val native = NativePairingRecords.retainedNativeRoute(mac)
    return if (primary is PairingCode.Tailscale && native != null && method != NativeMacConnectionMethod.TAILSCALE)
        NativeSavedMacRoute(checkNotNull(mac.nativeRouteCode), native, false)
    else NativeSavedMacRoute(mac.code, primary, true)
}
