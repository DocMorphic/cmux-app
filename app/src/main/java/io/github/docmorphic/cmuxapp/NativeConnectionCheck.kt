package io.github.docmorphic.cmuxapp

import io.github.docmorphic.cmuxapp.iroh.IrxConnectionDiagnostics
import kotlinx.coroutines.*

internal data class NativeConnectionReport(
    val identity: Boolean = false,
    val accountAccess: Boolean = false,
    val transport: IrxConnectionDiagnostics? = null,
    val responseMillis: Long? = null,
    val failure: Failure? = null
) {
    enum class Failure(val advice: String) {
        DISCOVERY("This Mac is not available in your team. Open cmux on the Mac and enable mobile pairing, then try again."),
        IDENTITY("The Mac identity could not be verified. Reconnect to the intended Mac."),
        ACCOUNT("Confirm both devices use the same cmux account and team."),
        TIMEOUT("The Mac did not answer in time. Check your connection and try again."),
        CONNECTION("Reconnect to your Mac, then run the check again.")
    }
    val route: String get() = when (transport?.route) {
        IrxConnectionDiagnostics.Route.DIRECT -> "Direct Peer-to-Peer"
        IrxConnectionDiagnostics.Route.PRIVATE_NETWORK -> "LAN or Private VPN"
        IrxConnectionDiagnostics.Route.RELAY -> "Relay"
        IrxConnectionDiagnostics.Route.UNAVAILABLE -> "No Live Route"
        null -> "Not Reported"
    }
    val encryption: String get() = if (transport != null && transport.route != IrxConnectionDiagnostics.Route.UNAVAILABLE)
        "Verified (Iroh QUIC)" else "Not Reported"

    /** Only fixed labels, booleans and durations. Never include host names, addresses, tokens or server errors. */
    fun shareText(): String = buildString {
        appendLine("cmux Connection Report")
        appendLine("Active Route: $route")
        appendLine("Encrypted Transport: $encryption")
        appendLine("Mac Identity: ${if (identity) "Verified" else "Not Verified"}")
        appendLine("Account Access: ${if (accountAccess) "Verified" else "Not Verified"}")
        transport?.roundTripMillis?.let { appendLine("Transport RTT: $it ms") }
        responseMillis?.let { appendLine("RPC Response: $it ms") }
        failure?.let { appendLine("Suggested Action: ${it.advice}") }
    }.trimEnd()
}

internal object NativeConnectionCheck {
    suspend fun run(client: MobileRpcClient, mac: NativeCredentialStore.PairedMac,
                    timeoutMillis: Long = 10_000): NativeConnectionReport {
        var identity = false
        var authenticated = false
        try {
            return withTimeout(timeoutMillis) {
                val status = client.request("mobile.host.status", timeoutMillis = timeoutMillis)
                val matches = runCatching {
                    require(mac.deviceId.isNotBlank())
                    mac.requireMatchingHost(status)
                }.isSuccess
                if (!matches) return@withTimeout NativeConnectionReport(failure = NativeConnectionReport.Failure.IDENTITY)
                identity = true
                val started = System.nanoTime()
                client.request("mobile.workspace.list", timeoutMillis = timeoutMillis)
                val elapsed = ((System.nanoTime() - started) / 1_000_000).coerceAtLeast(0)
                authenticated = true
                NativeConnectionReport(true, true, client.transportDiagnostics(), elapsed)
            }
        } catch (failure: Exception) {
            currentCoroutineContext().ensureActive()
            val reason = when {
                failure is TimeoutCancellationException -> NativeConnectionReport.Failure.TIMEOUT
                failure is MobileRpcException && failure.code in setOf("unauthorized", "forbidden", "permission_denied", "team_access_revoked") -> NativeConnectionReport.Failure.ACCOUNT
                else -> NativeConnectionReport.Failure.CONNECTION
            }
            return NativeConnectionReport(identity, authenticated, failure = reason)
        }
    }
}
