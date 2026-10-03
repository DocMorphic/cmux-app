package io.github.docmorphic.cmuxapp

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** Inspect generated UI fixture names; close only an explicitly selected exact fixture title. */
class LiveNativeUiRecoveryCheck {
    @Test fun inspectOrCloseSelectedUiFixture() = runBlocking<Unit> {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cmux_live_ui_recovery") == "true")
        check(!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk"))
        val pattern = Regex("Android UI check [0-9a-f]{8}")
        val title = args.getString("cmux_ui_fixture_cleanup_title")
        require(title == null || pattern.matches(title))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val receiptFile = File(context.filesDir, "live-ui-fixture.json")
        var stage = "account refresh"
        try { withTimeout(60_000) {
            NativeAppConnections.acquire(context).use { handle ->
                val connections = handle.connections
                val probe = Any()
                connections.setProbeActive(probe, true)
                try {
                    val team = checkNotNull(connections.teams.refresh().scope)
                    val mac = connections.store.pairedMacs().filter {
                        connections.connector.allowsSaved(it) && PairingCodeParser.parse(it.code).getOrNull() is PairingCode.Iroh
                    }.single()
                    stage = "verified native connection"
                    connections.connector.connectSaved(mac, connections.account).use { client ->
                        mac.requireMatchingHost(client.hostStatus())
                        check(connections.teams.isCurrent(team))
                        val fixtures = parseAuthoritativeWorkspaces(client.workspaces()).filter { pattern.matches(it.title) }
                        if (title == null) {
                            println("CMUX_LIVE_UI_FIXTURES " + JSONArray(fixtures.map { it.title }))
                        } else {
                            stage = "exact fixture selection"
                            val fixture = fixtures.single { it.title == title }
                            if (receiptFile.exists()) {
                                stage = "private ownership receipt"
                                val receipt = JSONObject(receiptFile.readText())
                                check(receipt.getString("title") == fixture.title && receipt.getString("id") == fixture.id)
                                check(receipt.optString("windowId") == fixture.windowId.orEmpty())
                            }
                            stage = "single close attempt"
                            client.closeWorkspace(fixture.id, fixture.windowId)
                            stage = "verify fixture removed"
                            withTimeout(10_000) {
                                while (parseAuthoritativeWorkspaces(client.workspaces()).any { it.id == fixture.id }) delay(250)
                            }
                            if (receiptFile.exists()) check(receiptFile.delete())
                            println("CMUX_LIVE_UI_RECOVERY " + JSONObject().put("fixtureClosed", true))
                        }
                    }
                } finally { connections.setProbeActive(probe, false) }
            }
        } } catch (failure: Throwable) {
            throw AssertionError("UI fixture recovery failed at $stage (${failure.javaClass.simpleName})")
        }
    }
}
