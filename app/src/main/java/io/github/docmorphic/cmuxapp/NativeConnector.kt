package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Connection boundary shared by the UI and its on-device contract tests. */
fun interface NativeConnector {
    suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount): MobileRpcClient
    suspend fun connectIroh(pairing: PairingCode.Iroh, account: NativeAccount): MobileRpcClient =
        error("Native computer discovery is unavailable in this connection provider")
    fun allowsSaved(pairing: PairingCode): Boolean = true
    /** Called only by the visible pairing confirmation, never by deep-link receipt or reconnect. */
    fun authorizePairing(pairing: PairingCode.Tailscale) {}
}

internal suspend fun NativeConnector.connectPairing(pairing: PairingCode, account: NativeAccount): MobileRpcClient = when (pairing) {
    is PairingCode.Tailscale -> connect(pairing, account)
    is PairingCode.Iroh -> connectIroh(pairing, account)
}

internal class TailscaleConnector(context: Context, store: NativeCredentialStore, teams: NativeAccountTeams) : NativeConnector, AutoCloseable {
    private val authority = TailscalePairingAuthority({ teams.state.value.scope }, teams::isCurrent,
        TailscaleGrantStore(store::load, store::update),
        resolve = { route, permits -> TailscaleRoute.resolvePeer(context, route, permits) },
        dial = { route, permits, token ->
            val transport = TailscaleRoute.resolve(context, route, permits)
            MobileRpcClient(transport, token)
        }, expected = { pairing -> store.pairedMacs().singleOrNull {
            PairingCodeParser.parse(it.code).getOrNull() == pairing
        } })
    override suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount) =
        withContext(Dispatchers.IO) { authority.connect(pairing, account::accessToken) }
    override fun authorizePairing(pairing: PairingCode.Tailscale) = authority.authorize(pairing)
    override fun allowsSaved(pairing: PairingCode) = pairing is PairingCode.Tailscale && authority.allowsSaved(pairing)
    fun retireInvalid() = authority.retireInvalid()
    override fun close() = authority.close()
}
