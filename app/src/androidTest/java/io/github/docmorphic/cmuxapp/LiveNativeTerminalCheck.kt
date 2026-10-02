package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.UUID

/** Opt-in physical acceptance. Only the newly created workspace receives input or is closed.
 * Existing account/pairing/preferences are never reset. Reports omit terminal text and host IDs.
 * This tests real RPC and production replay decoding, not Gboard, screen pixels or output lanes.
 */
class LiveNativeTerminalCheck {
    @Test fun disposableTerminalOutputSurvivesNativeReconnect() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("cmux_live_terminal_fixture") == "true")
        check(!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk")) { "Physical device required" }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        var stage = "existing account"
        var creationAttempted = false
        var fixtureCreated = false
        var fixtureClosed = false
        try {
            withTimeout(120_000) {
                NativeAppConnections.acquire(context).use { handle ->
                    val connections = handle.connections
                    check(connections.account.isSignedIn())
                    val probe = Any()
                    connections.setProbeActive(probe, true)
                    var active: MobileRpcClient? = null
                    var owned: NativeWorkspace? = null
                    try {
                        stage = "account refresh"
                        val team = checkNotNull(connections.teams.refresh().scope)
                        val mac = connections.store.pairedMacs().filter {
                            connections.connector.allowsSaved(it) &&
                                PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Iroh
                        }.single()
                        suspend fun connect(): MobileRpcClient {
                            check(connections.teams.isCurrent(team) && connections.connector.allowsSaved(mac))
                            val next = connections.connector.connectSaved(mac, connections.account)
                            try { mac.requireMatchingHost(next.hostStatus()); return next }
                            catch (failure: Throwable) { next.close(); throw failure }
                        }
                        stage = "native connection"
                        active = connect()
                        val first = checkNotNull(active)
                        val originalIds = parseAuthoritativeWorkspaces(first.workspaces()).map { it.id }.toSet()
                        stage = "create disposable workspace"
                        // Never retry creation or input: a lost reply may already have applied it.
                        creationAttempted = true
                        val created = TaskCreationResult.parse(first.request("workspace.create",
                            JSONObject().put("title", "Android acceptance fixture"), timeoutMillis = 30_000)).created
                        check(created.id !in originalIds)
                        owned = created
                        fixtureCreated = true
                        stage = "prepare lazy terminal"
                        val initial = checkNotNull(created.terminals.firstOrNull())
                        try { first.prepareTerminal(created.id, initial.id) }
                        catch (failure: Exception) { if (failure is CancellationException) throw failure }
                        stage = "new terminal readiness"
                        val terminal = withTimeout(20_000) {
                            var ready: NativeTerminal? = null
                            while (ready == null) {
                                ready = parseAuthoritativeWorkspaces(first.workspaces()).single { it.id == created.id }
                                    .terminals.firstOrNull { it.id == initial.id && it.isReady }
                                if (ready == null) delay(250)
                            }
                            ready
                        }
                        val suffix = UUID.randomUUID().toString().replace("-", "")
                        val marker = "CMUX_OK_" + suffix
                        // The exact output marker never appears in the echoed shell command.
                        stage = "single terminal input"
                        check(connections.teams.isCurrent(team))
                        first.input(created.id, terminal.id, "printf '%s%s\\n' 'CMUX_OK_' '$suffix'\r")

                        suspend fun requireOutput(client: MobileRpcClient): String {
                            val status = client.hostStatus()
                            mac.requireMatchingHost(status)
                            val caps = status.optJSONArray("capabilities")
                            val capabilities = if (caps == null) emptySet() else
                                (0 until caps.length()).map { caps.getString(it) }.toSet()
                            val transport = TerminalTransport.resolve(capabilities)
                            withTimeout(20_000) {
                                while (true) {
                                    check(connections.teams.isCurrent(team))
                                    // No viewport report: retain this fixture's current host dimensions.
                                    val replay = client.request("mobile.terminal.replay", JSONObject()
                                        .put("workspace_id", created.id).put("surface_id", terminal.id)
                                        .put("anchor", "screen").put("max_scrollback_rows", 0))
                                    val found = withContext(Dispatchers.Main) {
                                        TerminalStreamMirror(terminal.id, transport, TerminalViewport(80, 24),
                                            ghosttyTerminalFactory(TerminalCellMetrics(8f, 16f, 14f))).use { mirror ->
                                            check(mirror.replay(replay) == TerminalStreamMirror.Result.APPLIED)
                                            check(mirror.display.columns > 0 && mirror.display.rows > 0)
                                            mirror.display.visibleLines().any { spans ->
                                                spans.joinToString("") { it.text }.trim() == marker
                                            }
                                        }
                                    }
                                    if (found) break
                                    delay(500)
                                }
                            }
                            return transport.mode.name
                        }

                        stage = "real terminal replay decoding"
                        val mode = requireOutput(first)
                        stage = "native disconnect and reconnect"
                        first.close(); active = null
                        active = connect()
                        val second = checkNotNull(active)
                        // Leases can share one wire. A new wrapper alone does not prove reconnect.
                        check(first.events !== second.events) { "The original shared connection is still leased" }
                        check(parseAuthoritativeWorkspaces(second.workspaces()).any {
                            it.id == created.id && it.terminals.any { pane -> pane.id == terminal.id }
                        })
                        stage = "same terminal output after reconnect"
                        check(requireOutput(second) == mode)
                        stage = "close disposable workspace"
                        owned = null // Do not repeat a close even if its acknowledgement is lost.
                        second.closeWorkspace(created.id, created.windowId)
                        withTimeout(10_000) {
                            while (parseAuthoritativeWorkspaces(second.workspaces()).any { it.id == created.id }) delay(250)
                        }
                        fixtureClosed = true
                        println("CMUX_LIVE_TERMINAL_REPORT " + JSONObject()
                            .put("identityVerified", true).put("accountAccessVerified", true)
                            .put("createdWorkspace", true).put("inputRequests", 1)
                            .put("decodedOutput", true).put("reconnectedSameTerminal", true)
                            .put("freshConnection", true)
                            .put("outputAfterReconnect", true).put("outputMode", mode)
                            .put("fixtureClosed", true))
                    } finally {
                        // Cleanup only a positively identified workspace created by this invocation.
                        // No reconnect/retry on an uncertain close; surface the incomplete cleanup.
                        val cleanup = owned
                        if (cleanup != null && active != null) withContext(NonCancellable) {
                            runCatching { withTimeout(10_000) {
                                checkNotNull(active).closeWorkspace(cleanup.id, cleanup.windowId)
                                fixtureClosed = parseAuthoritativeWorkspaces(checkNotNull(active).workspaces())
                                    .none { it.id == cleanup.id }
                            } }
                        }
                        active?.close()
                        connections.setProbeActive(probe, false)
                    }
                }
            }
        } catch (failure: Throwable) {
            throw AssertionError("Live terminal check failed at $stage (${failure.javaClass.simpleName}); " +
                "creationAttempted=$creationAttempted, fixtureCreated=$fixtureCreated, fixtureClosed=$fixtureClosed")
        }
    }
}
