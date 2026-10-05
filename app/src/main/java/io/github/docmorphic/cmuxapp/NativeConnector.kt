package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Connection boundary shared by the UI and its on-device contract tests. */
fun interface NativeConnector {
    suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount): MobileRpcClient
    suspend fun connectIroh(pairing: PairingCode.Iroh, account: NativeAccount): MobileRpcClient =
        error("Native computer discovery is unavailable in this connection provider")
    suspend fun connectTicket(pairing: PairingCode, ticket: MobileAttachTicket, account: NativeAccount): MobileRpcClient =
        error("Ticket pairing is unavailable in this connection provider")
    suspend fun connectSaved(mac: NativeCredentialStore.PairedMac, account: NativeAccount): MobileRpcClient =
        connectPairing(PairingCodeParser.parse(mac.code).getOrThrow(), account)
    fun allowsSaved(pairing: PairingCode): Boolean = true
    fun pairingCompatibilityError(pairing: PairingCode): String? = null
    fun allowsSaved(mac: NativeCredentialStore.PairedMac): Boolean =
        PairingCodeParser.parse(mac.code).getOrNull()?.let(::allowsSaved) == true
    /** Called only by the visible pairing confirmation, never by deep-link receipt or reconnect. */
    fun authorizePairing(pairing: PairingCode.Tailscale) {}
}

internal suspend fun NativeConnector.connectPairing(pairing: PairingCode, account: NativeAccount): MobileRpcClient = when (pairing) {
    is PairingCode.Tailscale -> connect(pairing, account)
    is PairingCode.Iroh -> connectIroh(pairing, account)
}

internal class TailscaleConnector(context: Context, store: NativeCredentialStore, teams: NativeAccountTeams,
    admitCompatibility: suspend (NativeTeamScope, MobileRpcClient, org.json.JSONObject) -> Unit = { _, _, _ -> }) : NativeConnector, AutoCloseable {
    private val authority = TailscalePairingAuthority({ teams.state.value.scope }, teams::isCurrent,
        TailscaleGrantStore(store::load, store::update),
        resolve = { route, permits -> TailscaleRoute.resolvePeer(context, route, permits) },
        dial = { route, permits, token ->
            val transport = TailscaleRoute.resolve(context, route, permits)
            MobileRpcClient(transport, token)
        }, expected = { pairing ->
            val team = teams.state.value.scope
            val grants = TailscaleGrantStore(store::load, store::update)
            store.pairedMacs().singleOrNull { row ->
                team != null && NativePairingRecords.owner(row, grants) == (team.userId to team.teamId) &&
                    PairingCodeParser.parse(row.code).getOrNull() == pairing
            }
        }, admitCompatibility = admitCompatibility,
        manualTicket = { client, route, host, owner ->
            check(teams.isCurrent(owner)) { "Account or team changed" }
            ManualAttachTicketRequest.request(client, route, host, owner, teams.state.value.email)
        })
    override suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount) = connectOwned(pairing, account, null)
    suspend fun connectTicket(pairing: PairingCode.Tailscale, ticket: MobileAttachTicket, account: NativeAccount, team: NativeTeamScope) =
        connectOwned(pairing, account, team, ticket)
    suspend fun connectSaved(pairing: PairingCode.Tailscale, account: NativeAccount, team: NativeTeamScope,
        savedTicket: NativeSavedTicketAdmission? = null) = connectOwned(pairing, account, team, savedTicket = savedTicket)
    private suspend fun connectOwned(pairing: PairingCode.Tailscale, account: NativeAccount, team: NativeTeamScope?,
        ticket: MobileAttachTicket? = null, savedTicket: NativeSavedTicketAdmission? = null): MobileRpcClient {
        var acquired: MobileRpcClient? = null
        return try {
            withContext(Dispatchers.IO) { authority.connect(pairing, team, ticket, savedTicket, account::accessToken).also { acquired = it } }
        } catch (failure: Throwable) {
            // Cancellation can reject the dispatcher return after the socket was acquired.
            acquired?.close(); throw failure
        }
    }
    override fun authorizePairing(pairing: PairingCode.Tailscale) = authority.authorize(pairing)
    override fun allowsSaved(pairing: PairingCode) = pairing is PairingCode.Tailscale && authority.allowsSaved(pairing)
    fun retireInvalid() = authority.retireInvalid()
    override fun close() = authority.close()
}
