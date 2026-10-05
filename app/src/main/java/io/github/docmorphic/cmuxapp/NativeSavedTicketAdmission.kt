package io.github.docmorphic.cmuxapp

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
