package io.github.docmorphic.cmuxapp

import java.util.concurrent.atomic.AtomicReference

/** Captured encrypted context and its current saved-record admission. Never grants a route by itself. */
internal class NativeSavedTicketAdmission(
    val mac: NativeCredentialStore.PairedMac,
    val context: MobileAttachTicketContext,
    private val admitted: () -> Unit
) {
    fun requireCurrent() = admitted()
    fun requireBinding(pairing: PairingCode, owner: NativeTeamScope) {
        requireCurrent()
        check(mac.ticketRevision != null && mac.accountUserId == owner.userId && mac.accountTeamId == owner.teamId &&
            PairingCodeParser.parse(mac.code).getOrNull() == pairing) { "Saved pairing ticket does not cover this connection" }
    }
    override fun toString() = "SavedTicketAdmission(redacted)"
}

/** A saved ticket may continue only on its one returned handle, under live runtime ownership. */
internal class NativeSavedTicketSession(
    mac: NativeCredentialStore.PairedMac,
    context: MobileAttachTicketContext,
    private val selected: () -> Boolean,
    private val retainsForeground: (MobileRpcClient) -> Boolean,
    private val authorizeSavedRecord: () -> Unit
) {
    private val client = AtomicReference<MobileRpcClient?>(null)
    val admission = NativeSavedTicketAdmission(mac, context, ::requireCurrent)

    private fun allowed(handle: MobileRpcClient?) = (handle == null || !handle.isClosed) &&
        (selected() || (handle != null && retainsForeground(handle)))

    fun requireCurrent() {
        val handle = client.get()
        check(allowed(handle)) { "Account or foreground ticket connection changed" }
        authorizeSavedRecord()
        check(allowed(handle)) { "Account or foreground ticket connection changed" }
    }

    /** Binding cannot use an old foreground exception to admit another connection. */
    fun bind(handle: MobileRpcClient) {
        check(selected() && !handle.isClosed && client.compareAndSet(null, handle)) { "Saved ticket admission changed" }
        try { requireCurrent() }
        catch (failure: Throwable) { client.compareAndSet(handle, null); throw failure }
    }
}
