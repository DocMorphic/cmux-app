package io.github.docmorphic.cmuxapp

import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Connection boundary shared by the UI and its on-device contract tests. */
fun interface NativeConnector {
    suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount): MobileRpcClient
    suspend fun connectIroh(pairing: PairingCode.Iroh, account: NativeAccount): MobileRpcClient =
        error("Native computer discovery is unavailable in this connection provider")
    fun allowsSaved(pairing: PairingCode): Boolean = true
}

internal suspend fun NativeConnector.connectPairing(pairing: PairingCode, account: NativeAccount): MobileRpcClient = when (pairing) {
    is PairingCode.Tailscale -> connect(pairing, account)
    is PairingCode.Iroh -> connectIroh(pairing, account)
}

class TailscaleConnector(private val context: Context) : NativeConnector {
    override suspend fun connect(pairing: PairingCode.Tailscale, account: NativeAccount): MobileRpcClient {
        pairing.stackUserId?.let { expected ->
            require(account.userId() == expected) { "This Mac is signed in to a different cmux account" }
        }
        var lastError: Throwable? = null
        for (route in pairing.routes) {
            val transport = try { withContext(Dispatchers.IO) { TailscaleRoute.resolve(context, route) } }
                catch (failure: Exception) {
                    if (failure is CancellationException) throw failure
                    lastError = failure
                    continue
                }
            val candidate = MobileRpcClient(transport, account::accessToken)
            try {
                candidate.connect()
                return candidate
            } catch (failure: Exception) {
                candidate.close()
                if (failure is CancellationException) throw failure
                lastError = failure
            }
        }
        throw lastError ?: IllegalStateException("No Tailscale route is reachable")
    }
}
