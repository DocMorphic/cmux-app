package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Inspect the private browser-test receipt; only explicit cleanup may close its exact workspace. */
class LiveNativeBrowserUiRecoveryCheck {
    @Test fun inspectOrCloseRecordedBrowserFixture() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cmux_live_browser_ui_recovery") == "true")
        check(!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk"))
        val cleanup = args.getString("cmux_live_browser_ui_cleanup") == "true"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receiptFile = File(context.filesDir, "live-browser-ui-fixture.json")
        check(receiptFile.exists()) { "No browser fixture receipt; do not infer an owned workspace" }
        val receipt = JSONObject(receiptFile.readText())
        val title = receipt.getString("title")
        check(Regex("Android browser UI check [0-9a-f]{8}").matches(title))
        check(receipt.getString("build") == "nightly")
        var stage = "account refresh"
        try { withTimeout(60_000) {
            NativeAppConnections.acquire(context).use { handle ->
                val connections = handle.connections
                val probe = Any()
                connections.setProbeActive(probe, true)
                try {
                    check(connections.account.isSignedIn())
                    val team = checkNotNull(connections.teams.refresh().scope)
                    if (receipt.has("accountUserId")) check(receipt.getString("accountUserId") == team.userId)
                    if (receipt.has("accountTeamId")) check(receipt.getString("accountTeamId") == team.teamId)
                    stage = "single verified nightly host"
                    val directory = connections.native.state.first { it.account == team && it.ready }
                    val selected = directory.computers.single { it.buildTag == "nightly" }
                    if (receipt.has("deviceId")) check(canonicalMacDeviceId(receipt.getString("deviceId")) == canonicalMacDeviceId(selected.deviceId))
                    val expected = NativeCredentialStore.PairedMac(PairingCodeParser.computer(selected, team),
                        selected.deviceId, selected.name, selected.buildTag,
                        accountUserId = team.userId, accountTeamId = team.teamId)
                    connections.connector.connectPairing(PairingCodeParser.parse(expected.code).getOrThrow(), connections.account).use { client ->
                        expected.requireMatchingHost(client.hostStatus())
                        check(connections.teams.isCurrent(team))
                        stage = "exact recorded workspace ownership"
                        val fixture = parseAuthoritativeWorkspaces(client.workspaces()).singleOrNull { it.id == receipt.getString("id") }
                        if (fixture == null) {
                            if (cleanup) check(receiptFile.delete())
                            println("CMUX_LIVE_BROWSER_UI_RECOVERY " + JSONObject().put("fixtureAlreadyAbsent", true)
                                .put("receiptRemoved", cleanup))
                        } else {
                            check(fixture.title == title && fixture.windowId.orEmpty() == receipt.optString("windowId"))
                            if (cleanup) {
                                check(!receipt.optBoolean("cleanupAttempted")) { "Prior close outcome is uncertain; do not repeat it" }
                                check(connections.teams.isCurrent(team))
                                stage = "single recorded workspace close"
                                receiptFile.writeText(receipt.put("cleanupAttempted", true).toString())
                                client.closeWorkspace(fixture.id, fixture.windowId)
                                stage = "verify recorded workspace removed"
                                while (parseAuthoritativeWorkspaces(client.workspaces()).any { it.id == fixture.id }) delay(250)
                                check(receiptFile.delete())
                            }
                            println("CMUX_LIVE_BROWSER_UI_RECOVERY " + JSONObject().put("ownershipVerified", true)
                                .put("fixtureClosed", cleanup).put("receiptRemoved", cleanup))
                        }
                    }
                } finally { connections.setProbeActive(probe, false) }
            }
        } } catch (failure: Throwable) {
            throw AssertionError("Browser UI fixture recovery failed at $stage (${failure.javaClass.simpleName})")
        }
    }
}
