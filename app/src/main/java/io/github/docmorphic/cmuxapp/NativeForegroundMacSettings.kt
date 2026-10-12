package io.github.docmorphic.cmuxapp

/** Mac-local settings use the exact live handle's owner; the picker cannot relabel it. */
internal fun nativeForegroundMacSettingsCurrent(login: String?, owner: NativeTeamScope,
    mac: NativeCredentialStore.PairedMac, active: MobileRpcClient, client: MobileRpcClient?,
    admittedMac: NativeCredentialStore.PairedMac?,
    ready: Boolean, code: String, connectedCode: String?, visibleSaved: List<NativeCredentialStore.PairedMac>,
    selectedCurrent: (NativeTeamScope) -> Boolean, selectedAllowed: (NativeCredentialStore.PairedMac) -> Boolean,
    foregroundScope: (NativeCredentialStore.PairedMac, MobileRpcClient) -> NativeTeamScope?): Boolean {
    if (login != owner.login || mac.accountUserId != owner.userId || mac.accountTeamId != owner.teamId ||
        active !== client || active.isClosed ||
        !nativeForegroundMacSelected(mac, admittedMac, ready, code, connectedCode, true) ||
        !NativeComputerMenuPairing.isCurrent(mac, visibleSaved)) return false
    return foregroundScope(mac, active) == owner || (selectedCurrent(owner) && selectedAllowed(mac))
}
