package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test

/** Explicit opt-in, read-only Mac inspection using the existing signed-in account.
 * Never clears stores, changes pairings, creates workspaces or sends terminal input.
 * Ordinary CI/test-suite invocation skips this test; output contains fixed labels/counts only.
 */
class LiveNativeBrowserCheck {
    @Test fun reportExistingMacBrowserCapability() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cmux_live_read_only") == "true")
        check(!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk")) { "Physical device required" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var stage = "existing sign-in"
        try {
            withTimeout(90_000) {
                NativeAppConnections.acquire(context).use { handle ->
                    val connections = handle.connections
                    check(connections.account.isSignedIn())
                    val probe = Any()
                    connections.setProbeActive(probe, true)
                    try {
                        stage = "account team refresh"
                        val team = checkNotNull(connections.teams.refresh().scope)
                        stage = "single saved Mac selection"
                        val mac = connections.store.pairedMacs().filter {
                            connections.connector.allowsSaved(it) && PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Iroh
                        }.single()
                        stage = "native Mac connection"
                        connections.connector.connectSaved(mac, connections.account).use { client ->
                            stage = "host identity"
                            val status = client.hostStatus()
                            mac.requireMatchingHost(status)
                            check(connections.teams.isCurrent(team))
                            val capabilities = status.optJSONArray("capabilities")
                            val supported = capabilities != null && (0 until capabilities.length()).any {
                                capabilities.optString(it) == BrowserTunnelProtocol.CAPABILITY
                            }
                            stage = "authenticated workspace read"
                            client.request("mobile.workspace.list")
                            check(connections.teams.isCurrent(team))
                            val native = client.supportsBrowserTunnels
                            stage = "browser listening-port read"
                            val ports = if (supported && native) checkNotNull(client.browserListeningPorts()) else null
                            check(connections.teams.isCurrent(team))
                            val report = JSONObject().put("identityVerified", true).put("accountAccessVerified", true)
                                .put("nativeBrowserLaneTransport", native).put("browserCapabilityAdvertised", supported)
                                .put("browserListingRead", ports != null)
                            ports?.let { report.put("listeningPortCount", it.ports.size).put("allowsNonLoopbackHosts", it.allowsNonLoopbackHosts) }
                            runCatching { client.transportDiagnostics() }.getOrNull()?.let { report.put("route", it.route.label) }
                            println("CMUX_LIVE_BROWSER_REPORT $report")
                        }
                    } finally { connections.setProbeActive(probe, false) }
                }
            }
        } catch (failure: Throwable) {
            // Server errors/route objects can contain identifying information. Do not print them.
            throw AssertionError("Live read-only check failed at $stage (${failure.javaClass.simpleName})")
        }
    }
}
