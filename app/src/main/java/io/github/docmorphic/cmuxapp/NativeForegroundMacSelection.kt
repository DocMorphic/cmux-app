package io.github.docmorphic.cmuxapp

/** The locator alone cannot identify an admitted device/build/account/ticket record. */
internal fun nativeForegroundMacSelected(requested: NativeCredentialStore.PairedMac,
    admitted: NativeCredentialStore.PairedMac?, ready: Boolean, code: String, connectedCode: String?,
    permitted: Boolean): Boolean = ready && permitted && admitted != null &&
    code == requested.code && connectedCode == requested.code &&
    NativeComputerMenuPairing.isCurrent(admitted, listOf(requested))
