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
    selected: () -> Boolean,
    retainsForeground: (MobileRpcClient) -> Boolean,
    authorizeSavedRecord: () -> Unit
) {
    private val session = NativeTicketSession(selected, retainsForeground, authorizeSavedRecord)
    val admission = NativeSavedTicketAdmission(mac, context, session::requireCurrent)
    fun requireCurrent() = session.requireCurrent()
    fun bind(handle: MobileRpcClient) = session.bind(handle)
}

/** Shared initial/saved ticket boundary. Continuation never supplies selected-scope admission. */
internal class NativeTicketSession(
    private val selected: () -> Boolean,
    private val retainsForeground: (MobileRpcClient) -> Boolean,
    private val authorizeRoute: () -> Unit
) {
    private val client = AtomicReference<MobileRpcClient?>(null)

    private fun allowed(handle: MobileRpcClient?) = (handle == null || !handle.isClosed) &&
        (selected() || (handle != null && retainsForeground(handle)))

    fun requireCurrent() {
        val handle = client.get()
        check(allowed(handle)) { "Account or foreground ticket connection changed" }
        authorizeRoute()
        check(allowed(handle)) { "Account or foreground ticket connection changed" }
    }

    /** Binding cannot use an old foreground exception to admit another connection. */
    fun bind(handle: MobileRpcClient) {
        check(selected() && !handle.isClosed && client.compareAndSet(null, handle)) { "Ticket admission changed" }
        try { requireCurrent() }
        catch (failure: Throwable) { client.compareAndSet(handle, null); throw failure }
    }
}

/** Transfers the acquired handle to its ticket view, closing it if final admission fails. */
internal fun attachAdmittedTicket(client: MobileRpcClient, context: MobileAttachTicketContext,
    selected: () -> Boolean, retainsForeground: (MobileRpcClient) -> Boolean,
    authorizeRoute: () -> Unit): MobileRpcClient {
    val session = NativeTicketSession(selected, retainsForeground, authorizeRoute)
    var view: MobileRpcClient? = null
    try {
        view = client.withAttachTicket(context, session::requireCurrent)
        session.bind(view)
        return view
    } catch (failure: Throwable) { view?.close(); client.close(); throw failure }
}
