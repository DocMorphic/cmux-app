package io.github.docmorphic.cmuxapp

internal data class NativeForegroundPaneContext(val mac: NativeCredentialStore.PairedMac?,
    val team: NativeTeamScope?, val macs: List<NativeCredentialStore.PairedMac>, val foreground: Boolean)

/** Presentation scope comes from live admitted authority; cached/saved records cannot create it. */
internal fun nativeForegroundPaneContext(login: String?, selected: NativeTeamScope?,
    selectedMacs: List<NativeCredentialStore.PairedMac>, visibleSaved: List<NativeCredentialStore.PairedMac>,
    code: String, client: MobileRpcClient?, scopeFor: (NativeCredentialStore.PairedMac, MobileRpcClient) -> NativeTeamScope?): NativeForegroundPaneContext {
    val retained = if (login == null || client == null || client.isClosed) null else
        visibleSaved.filter { it.code == code }.mapNotNull { mac -> scopeFor(mac, client)?.takeIf {
            it.login == login && mac.accountUserId == it.userId && mac.accountTeamId == it.teamId
        }?.let { mac to it } }.singleOrNull()
    if (retained != null) {
        val (mac, owner) = retained
        // Same public locators can exist in different teams; the admitted identity wins only in this pane.
        return NativeForegroundPaneContext(mac, owner, listOf(mac) + selectedMacs.filterNot { it.code == code }, true)
    }
    return NativeForegroundPaneContext(selectedMacs.singleOrNull { it.code == code }, selected, selectedMacs, false)
}

/** A late event from the old handle cannot label its snapshot with a newly selected locator. */
internal fun nativeForegroundEventCode(active: MobileRpcClient, client: MobileRpcClient?, code: String,
    connectedCode: String?): String? = connectedCode?.takeIf { client === active && !active.isClosed && code == it }
