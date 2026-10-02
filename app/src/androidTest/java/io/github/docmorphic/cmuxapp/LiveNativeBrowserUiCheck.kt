package io.github.docmorphic.cmuxapp

import android.app.KeyguardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONObject
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

/** Real pairing link, MainActivity and routed browser process; retains the intended NIGHTLY pairing. */
@OptIn(ExperimentalTestApi::class)
class LiveNativeBrowserUiCheck {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    @Test fun nightlyPairingAndRoutedBrowserNavigation() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cmux_live_browser_ui") == "true")
        check(!Build.FINGERPRINT.contains("generic") && !Build.MODEL.contains("sdk"))
        val port = checkNotNull(args.getString("cmux_live_browser_fixture_port")).toInt()
        val marker = checkNotNull(args.getString("cmux_live_browser_fixture_marker"))
        require(port in 1..65535 && marker.matches(Regex("CMUX_BROWSER_UI_[0-9a-f]{32}")))
        val context = instrumentation.targetContext
        check(!context.getSystemService(KeyguardManager::class.java).isKeyguardLocked) { "Unlock the physical device" }
        val receipt = File(context.filesDir, "live-browser-ui-fixture.json")
        check(!receipt.exists()) { "Inspect the previous browser UI fixture receipt before rerunning" }
        val device = UiDevice.getInstance(instrumentation)
        val handle = NativeAppConnections.acquire(context)
        val connections = handle.connections
        val probe = Any()
        connections.setProbeActive(probe, true)
        var stage = "existing account"
        var client: MobileRpcClient? = null
        var owned: NativeWorkspace? = null
        var scenario: ActivityScenario<MainActivity>? = null
        var activity: MainActivity? = null
        var failure: Throwable? = null
        var closed = false
        var creationAttempted = false
        try {
            val login = checkNotNull(connections.store.taskSession())
            val previous = connections.store.pairedMacs()
            val (team, expected, fixture) = runBlocking { withTimeout(60_000) {
                check(connections.account.isSignedIn())
                val team = checkNotNull(connections.teams.refresh().scope)
                stage = "nightly account discovery"
                val directory = connections.native.state.first { it.account == team && it.ready }
                val selected = directory.computers.single { it.buildTag == "nightly" }
                val code = PairingCodeParser.computer(selected, team)
                val expected = NativeCredentialStore.PairedMac(code, selected.deviceId, selected.name,
                    selected.buildTag, accountUserId = team.userId, accountTeamId = team.teamId)
                val active = connections.connector.connectPairing(PairingCodeParser.parse(code).getOrThrow(), connections.account)
                client = active
                val status = active.hostStatus()
                expected.requireMatchingHost(status)
                val caps = status.getJSONArray("capabilities")
                check((0 until caps.length()).any { caps.getString(it) == BrowserTunnelProtocol.CAPABILITY })
                check((0 until caps.length()).any { caps.getString(it) == "browser.stream.create.v1" })
                val existing = parseAuthoritativeWorkspaces(active.workspaces()).map { it.id }.toSet()
                stage = "create owned browser workspace"
                val title = "Android browser UI check " + UUID.randomUUID().toString().take(8)
                creationAttempted = true
                val created = TaskCreationResult.parse(active.request("workspace.create",
                    JSONObject().put("title", title), timeoutMillis = 30_000)).created
                check(created.id !in existing)
                owned = created
                receipt.writeText(JSONObject().put("id", created.id).put("windowId", created.windowId)
                    .put("title", title).put("build", "nightly").toString())
                check(created.title == title)
                Triple(team, expected, created)
            } }
            stage = "production account pairing link"
            scenario = ActivityScenario.launch(Intent(context, MainActivity::class.java)
                .setAction(Intent.ACTION_VIEW).setData(Uri.parse(expected.code)))
            scenario.onActivity {
                activity = it
                it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            compose.waitUntil(35_000) {
                connections.store.pairedMacs().any { it.deviceId == expected.deviceId && it.instanceTag == "nightly" &&
                    it.accountUserId == team.userId && it.accountTeamId == team.teamId }
            }
            check(connections.store.pairedMacs().containsAll(previous))
            stage = "open owned workspace through MainActivity"
            compose.waitUntil(30_000) { compose.onAllNodesWithText(fixture.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(fixture.title).performClick()
            compose.waitUntil(35_000) { compose.onAllNodesWithTag("native-terminal").fetchSemanticsNodes().isNotEmpty() }
            stage = "create streamed browser from terminal menu"
            compose.onNode(hasText(" ▾", substring = true) and hasClickAction()).performClick()
            compose.onNodeWithText("New Browser").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Browser mode").fetchSemanticsNodes().isNotEmpty() }
            stage = "switch real browser to On Android"
            compose.onNodeWithContentDescription("Browser mode").performClick()
            compose.onNodeWithText("On Android").assertIsEnabled().performClick()
            fun awaitBrowserProcess() {
                compose.waitUntil(20_000) { checkNotNull(activity).lifecycle.currentState < Lifecycle.State.STARTED }
            }
            fun text(value: String): UiObject2 = checkNotNull(device.wait(Until.findObject(By.text(value)), 25_000))
            fun desc(value: String): UiObject2 = checkNotNull(device.wait(Until.findObject(By.desc(value)), 15_000))
            fun page(title: String) { text("$title ▾"); text(marker) }
            awaitBrowserProcess()
            stage = "type owned Mac fixture address in routed browser"
            val selector = By.clazz("android.widget.EditText").hasDescendant(By.desc("Browser address"))
            val address = checkNotNull(device.wait(Until.findObject(selector), 15_000))
            address.click()
            check(device.wait(Until.hasObject(By.copy(selector).focused(true)), 5_000))
            val url = "http://localhost:$port/$marker/start"
            address.text = url
            check(device.wait(Until.hasObject(By.copy(selector).text(url)), 5_000))
            device.pressEnter()
            stage = "render fixture through production WebView and Mac route"
            page("Mac browser fixture")
            stage = "navigate real page and browser history"
            text("Open next Mac page").click(); page("Next Mac page")
            desc("Browser Back").click(); page("Mac browser fixture")
            desc("Browser Forward").click(); page("Next Mac page")
            val shots = File(context.getExternalFilesDir(null), "live-browser-ui-check").apply { mkdirs() }
            check(device.takeScreenshot(File(shots, "native-browser-next.png")))
            stage = "return to workspaces and reopen committed browser"
            desc("Back to workspaces").click()
            compose.waitUntil(20_000) { compose.onAllNodesWithText(fixture.title).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText(fixture.title).performClick()
            awaitBrowserProcess(); page("Next Mac page")
            check(device.takeScreenshot(File(shots, "native-browser-reopened.png")))
            desc("Back to workspaces").click(); compose.waitForIdle()
            check(connections.store.taskSession() == login && connections.account.isSignedIn())
            check(connections.store.pairedMacs().containsAll(previous))
            println("CMUX_LIVE_BROWSER_UI_REPORT " + JSONObject().put("nightlyPairingSaved", true)
                .put("streamedBrowserCreated", true).put("onAndroidMode", true).put("ownedMacPageRendered", true)
                .put("backForwardVerified", true).put("committedPageReopened", true).put("loginPreserved", true))
        } catch (problem: Throwable) {
            failure = AssertionError("Live browser UI failed at $stage (${problem.javaClass.simpleName})")
        } finally {
            try { scenario?.close(); compose.waitForIdle() }
            catch (problem: Throwable) { if (failure == null) failure = AssertionError("Browser Activity cleanup failed (${problem.javaClass.simpleName})") }
            val fixture = owned
            if (fixture != null && client != null) runBlocking { withContext(NonCancellable) {
                try { withTimeout(15_000) {
                    checkNotNull(client).closeWorkspace(fixture.id, fixture.windowId)
                    while (parseAuthoritativeWorkspaces(checkNotNull(client).workspaces()).any { it.id == fixture.id }) delay(250)
                    closed = true
                    check(receipt.delete())
                } } catch (_: Exception) { /* Keep receipt; inspect before retrying any uncertain close. */ }
            } }
            client?.close()
            connections.setProbeActive(probe, false)
            handle.close()
        }
        println("CMUX_LIVE_BROWSER_UI_CLEANUP " + JSONObject().put("creationAttempted", creationAttempted).put("fixtureClosed", closed))
        failure?.let { throw it }
        check(closed) { "Browser UI fixture cleanup was not verified" }
    }
}
