package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/** Explicit opt-in, read-only Mac inspection using the existing signed-in account.
 * Never clears stores, changes pairings, creates workspaces or sends terminal input.
 * Ordinary CI/test-suite invocation skips this test; output contains fixed labels/counts only.
 */
class LiveNativeBrowserCheck {
    @Test fun reportExistingMacBrowserCapability() = runBlocking<Unit> {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString("cmux_live_read_only") == "true")
        val requestedBuild = arguments.getString("cmux_live_build")
        val discovered = arguments.getString("cmux_live_discovered") == "true"
        val fixturePort = arguments.getString("cmux_live_browser_fixture_port")?.toInt()
        val fixtureMarker = arguments.getString("cmux_live_browser_fixture_marker")
        require((fixturePort == null) == (fixtureMarker == null))
        require(fixturePort == null || fixturePort in 1..65535)
        require(fixtureMarker == null || fixtureMarker.matches(Regex("CMUX_BROWSER_[0-9a-f]{32}")))
        require(!discovered || requestedBuild != null) { "Discovery requires an explicit build selector" }
        require(requestedBuild == null || requestedBuild.matches(Regex("[a-z0-9][a-z0-9._-]{0,63}"))) {
            "Invalid build selector"
        }
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
                        val mac = if (discovered) {
                            stage = "account discovery for requested build"
                            val directory = withTimeout(30_000) {
                                connections.native.state.first { it.account == team && it.ready }
                            }
                            check(connections.teams.isCurrent(team))
                            val matches = directory.computers.filter { it.buildTag == requestedBuild }
                            println("CMUX_LIVE_DISCOVERY_REPORT " + JSONObject()
                                .put("directoryReady", true).put("computerCount", directory.computers.size)
                                .put("requestedBuildCount", matches.size))
                            stage = "single discovered Mac selection for requested build"
                            val selected = matches.single()
                            // In-memory expected identity only; no persisted pairing is added.
                            NativeCredentialStore.PairedMac(PairingCodeParser.computer(selected, team),
                                selected.deviceId, selected.name, selected.buildTag,
                                accountUserId = team.userId, accountTeamId = team.teamId)
                        } else {
                            stage = "single saved Mac selection for requested build"
                            connections.store.pairedMacs().filter {
                                (requestedBuild == null || it.instanceTag == requestedBuild) &&
                                    connections.connector.allowsSaved(it) && PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Iroh
                            }.single()
                        }
                        stage = "native Mac connection"
                        val active = if (discovered) connections.connector.connectPairing(
                            PairingCodeParser.parse(mac.code).getOrThrow(), connections.account)
                        else connections.connector.connectSaved(mac, connections.account)
                        active.use { client ->
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
                                .put("discoveredSelection", discovered)
                                .put("requestedBuildMatched", requestedBuild != null && mac.instanceTag == requestedBuild)
                                .put("identifiedInputAdvertised", capabilities != null && (0 until capabilities.length()).any {
                                    capabilities.optString(it) == "terminal.input.exactly_once.v1"
                                })
                                .put("nativeBrowserLaneTransport", native).put("browserCapabilityAdvertised", supported)
                                .put("browserListingRead", ports != null)
                            ports?.let { report.put("listeningPortCount", it.ports.size).put("allowsNonLoopbackHosts", it.allowsNonLoopbackHosts) }
                            if (fixturePort != null) {
                                stage = "owned Mac loopback HTTP fixture through native browser lane"
                                check(supported && native)
                                withTimeout(20_000) {
                                    check(client.useBrowserTunnel("127.0.0.1", fixturePort) { lane ->
                                        check(connections.teams.isCurrent(team))
                                        lane.write("GET /$fixtureMarker HTTP/1.1\r\nHost: 127.0.0.1:$fixturePort\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII))
                                        val bytes = ByteArrayOutputStream()
                                        while (true) {
                                            val part = lane.read(4096) ?: break
                                            check(bytes.size() + part.size <= 32 * 1024)
                                            bytes.write(part)
                                        }
                                        val response = bytes.toString("UTF-8")
                                        check(response.startsWith("HTTP/1.1 200 ") || response.startsWith("HTTP/1.0 200 "))
                                        check(response.substringAfter("\r\n\r\n", "") == fixtureMarker)
                                        // Browser payload must not consume/corrupt the authenticated control lane.
                                        mac.requireMatchingHost(client.hostStatus())
                                        check(connections.teams.isCurrent(team))
                                    })
                                }
                                report.put("ownedLoopbackHttpVerified", true).put("controlAfterBrowserVerified", true)
                            }
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
