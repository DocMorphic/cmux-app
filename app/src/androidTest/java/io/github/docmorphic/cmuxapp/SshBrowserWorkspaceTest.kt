package io.github.docmorphic.cmuxapp

import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Real SSH, cmux-tui provider, private Chrome, Compose pixels and routed WebView. */
class SshBrowserWorkspaceTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var session: NativeSshSession
    private lateinit var lifetime: CoroutineScope
    private lateinit var hostId: UUID
    private lateinit var cmux: SshCmuxHost
    private val args get() = InstrumentationRegistry.getArguments()
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    @Before fun setup() {
        assumeTrue(args.getString("cmux_ssh_nonce") == "cmux-browser")
        root = File(compose.activity.noBackupFilesDir, "cmux-browser-ui-${UUID.randomUUID()}")
        var metadata: String? = null
        val hosts = SshHostStore({ metadata }, { metadata = it })
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        val key = vault.generate("Private Chrome fixture")
        val host = SshHostRecord(name = "SSH Chrome fixture", keyId = key.id,
            endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!))
        hostId = host.id; hosts.upsert(host)
        assertTrue(hosts.confirmHostKey(hosts.dialPlan(hostId), hostId, hosts.trustSnapshot(host.endpoint), SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)))
        lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        session = NativeSshSession(hosts, vault, lifetime) { lifetime.isActive }
        runBlocking {
            assertEquals(0, session.connections.open(hostId).exec("fixture-reset").exitStatus)
            cmux = session.cmux.open(hostId)
        }
        compose.waitUntil(15000) { !cmux.state.value.loading }
        assertEquals(emptyList<String>(), cmux.state.value.errors)
    }
    @After fun cleanup() {
        if (::session.isInitialized) {
            runCatching { capture("ssh-real-chrome-last-state") }
            runCatching { File(compose.activity.getExternalFilesDir(null), "ssh-real-chrome-semantics.txt").writeText(compose.onRoot().printToString()) }
            compose.runOnIdle { session.close(); lifetime.cancel() }
            session.vault.state.value.toList().forEach { session.vault.delete(it.id) }
        }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private fun workspace() = cmux.state.value.providers.single { it.session == "fixture" }.state.value.tree!!.workspaces.single()
    private fun ready(description: String) {
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun background(red: Int, green: Int, blue: Int) {
        compose.waitUntil(15000) {
            // Reconnect replaces the composition after the old image was found.
            // A missing node during that transition is not a rendered frame.
            val image = try { compose.onNodeWithContentDescription("SSH browser page").captureToImage() }
            catch (failure: AssertionError) {
                if (failure.message?.contains("could not find any node") == true) return@waitUntil false
                throw failure
            }
            val pixels = image.toPixelMap()
            val pixel = pixels[pixels.width / 2, pixels.height / 10]
            kotlin.math.abs(pixel.red - red / 255f) < .04f && kotlin.math.abs(pixel.green - green / 255f) < .04f &&
                kotlin.math.abs(pixel.blue - blue / 255f) < .04f
        }
    }
    private fun events() = runBlocking {
        JSONObject(cmux.connection.exec("fixture-browser-status").stdout.toString(Charsets.UTF_8)).getJSONArray("events").toString()
    }
    private fun open() {
        val workspace = workspace(); val tab = workspace.tabs.single { it.isBrowser }
        val tag = "ssh.cmux.terminal.${workspace.key}.${tab.surface}"
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
    }
    private fun uiText(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 15000)) { "Missing $value" }
    private fun uiDescription(value: String) = checkNotNull(device.wait(Until.findObject(By.desc(value)), 15000)) { "Missing $value" }
    private fun capture(name: String) {
        assertTrue(device.takeScreenshot(File(compose.activity.getExternalFilesDir(null), "$name.png")))
    }
    @Test fun opensRealChromeInteractsAndRestoresBothBrowserModes() {
        val original = workspace().tabs.single { it.isBrowser }.resource
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        open(); ready("SSH browser page")
        // External providers can report a URL as title. Verify real page pixels
        // and DOM side effects independently of that provider metadata behavior.
        val page = compose.onNodeWithContentDescription("SSH browser page").assertIsDisplayed()
        background(18, 95, 55)
        capture("ssh-real-chrome-stream")
        page.performTouchInput { click(Offset(width * .5f, height * .33f)) }
        compose.waitUntil(10000) { events().contains("\"click\":[\"1\"]") }
        page.performTouchInput { click(Offset(width * .5f, height * .58f)) }
        compose.onNodeWithContentDescription("Show browser keyboard").performClick()
        ready("Hide browser keyboard")
        device.pressKeyCode(KeyEvent.KEYCODE_A)
        compose.waitUntil(10000) { events().contains("\"text\":[\"a\"]") }
        compose.onNodeWithContentDescription("Hide browser keyboard").performClick()
        ready("Show browser keyboard")
        val next = "http://127.0.0.1:${args.getString("cmux_ssh_browserport")}/next"
        compose.onNodeWithContentDescription("Browser address").performTextReplacement(next)
        compose.onNodeWithContentDescription("Browser address").performImeAction()
        background(30, 60, 120)
        compose.onNodeWithContentDescription("Browser mode").performClick()
        compose.onNodeWithText("On Android").performClick()
        compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        uiText("SSH Chrome next ▾"); uiText("Next Chrome page")
        capture("ssh-real-chrome-on-android")
        uiDescription("Back to workspaces").click()
        compose.waitUntil(15000) { compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        open()
        compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        uiText("SSH Chrome next ▾")
        uiDescription("Browser mode").click(); uiText("Streamed").click()
        compose.waitUntil(15000) { compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        ready("SSH browser page"); background(30, 60, 120)
        assertEquals(original, workspace().tabs.single { it.isBrowser }.resource)
        compose.onNodeWithText("‹  Workspaces").performClick()
        open(); ready("SSH browser page"); background(30, 60, 120)
        capture("ssh-real-chrome-reopened")
    }
    @Test fun providerRegistrationRestartRecoversSamePageWithoutStaleOrReplayedClicks() {
        val original = workspace().tabs.single { it.isBrowser }.resource
        val connection = cmux.connection
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        open(); ready("SSH browser page"); background(18, 95, 55)
        fun status() = runBlocking {
            JSONObject(connection.exec("fixture-browser-status").stdout.toString(Charsets.UTF_8))
        }
        fun clicks(): Int = status().getJSONArray("events").let { items ->
            (0 until items.length()).count { items.getJSONObject(it).has("click") }
        }
        fun click() = compose.onNodeWithContentDescription("SSH browser page").performTouchInput {
            click(Offset(width * .5f, height * .33f))
        }
        click(); compose.waitUntil(10000) { clicks() == 1 }; background(90, 40, 110)
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Browser loading").fetchSemanticsNodes().isEmpty() }
        val before = status()
        assertEquals(0, runBlocking { connection.exec("fixture-browser-provider-detach").exitStatus })
        // cmux-tui reports STARTING while the external provider is absent, not
        // FAILED. The old pixels remain visible but lose pointer authority.
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Browser loading").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(status().getBoolean("registered")); assertTrue(connection.isConnected)
        click(); compose.waitForIdle(); assertEquals(1, clicks())
        capture("ssh-provider-disconnected")
        assertEquals(0, runBlocking { connection.exec("fixture-browser-provider-reconnect").exitStatus })
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Browser loading").fetchSemanticsNodes().isEmpty() }
        ready("SSH browser page"); background(90, 40, 110)
        val after = status()
        assertEquals(before.getString("target"), after.getString("target"))
        assertEquals(before.getInt("registrations") + 1, after.getInt("registrations"))
        assertEquals(original, workspace().tabs.single { it.isBrowser }.resource)
        assertSame(connection, cmux.connection); assertTrue(connection.isConnected)
        assertEquals(1, clicks())
        click(); compose.waitUntil(10000) { clicks() == 2 }
        capture("ssh-provider-recovered")
    }

    @Test fun browserProcessReplacementShowsFreshPageAndDoesNotReplayOldInput() {
        val original = workspace().tabs.single { it.isBrowser }.resource
        val connection = cmux.connection
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        open(); ready("SSH browser page"); background(18, 95, 55)
        fun status() = runBlocking {
            JSONObject(connection.exec("fixture-browser-status").stdout.toString(Charsets.UTF_8))
        }
        fun clicks(): Int = status().getJSONArray("events").let { items ->
            (0 until items.length()).count { items.getJSONObject(it).has("click") }
        }
        fun click() = compose.onNodeWithContentDescription("SSH browser page").performTouchInput {
            click(Offset(width * .5f, height * .33f))
        }
        click(); compose.waitUntil(10000) { clicks() == 1 }; background(90, 40, 110)
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Browser loading").fetchSemanticsNodes().isEmpty() }
        val before = status()
        assertEquals(0, runBlocking { connection.exec("fixture-browser-process-stop").exitStatus })
        assertTrue(status().getBoolean("chromeExited")); assertTrue(connection.isConnected)
        compose.waitUntil(15000) { compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().isNotEmpty() }
        click(); compose.waitForIdle(); assertEquals(1, clicks())
        capture("ssh-chrome-process-stopped")
        assertEquals(0, runBlocking { connection.exec("fixture-browser-process-restart").exitStatus })
        compose.waitUntil(15000) { compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().isEmpty() }
        // Replacing Chrome loses its old DOM. Require actual new green pixels,
        // not the last purple image retained during the disconnect.
        ready("SSH browser page"); background(18, 95, 55)
        val after = status()
        assertNotEquals(before.getInt("chromePid"), after.getInt("chromePid"))
        assertFalse(after.getBoolean("chromeExited"))
        assertNotEquals(before.getString("target"), after.getString("target"))
        assertEquals(original, workspace().tabs.single { it.isBrowser }.resource)
        assertSame(connection, cmux.connection); assertTrue(connection.isConnected)
        assertEquals(1, clicks())
        click(); compose.waitUntil(10000) { clicks() == 2 }; background(90, 40, 110)
        capture("ssh-chrome-process-recovered")
    }

    @Test fun liveConnectionLossRestoresSameBrowserAndDoesNotReplayCompletedClick() {
        val original = workspace().tabs.single { it.isBrowser }.resource
        val oldProvider = cmux.state.value.providers.single { it.session == "fixture" }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        open(); ready("SSH browser page"); background(18, 95, 55)
        val next = "http://127.0.0.1:${args.getString("cmux_ssh_browserport")}/next"
        compose.onNodeWithContentDescription("Browser address").performTextReplacement(next)
        compose.onNodeWithContentDescription("Browser address").performImeAction()
        background(30, 60, 120)
        fun click() = compose.onNodeWithContentDescription("SSH browser page").performTouchInput {
            click(Offset(width * .5f, height * .33f))
        }
        fun clicks(): Int {
            val records = JSONObject("{\"events\":${events()}}").getJSONArray("events")
            return (0 until records.length()).count { records.getJSONObject(it).has("click") }
        }
        click(); compose.waitUntil(10000) { clicks() == 1 }; background(90, 40, 110)
        val oldConnection = cmux.connection
        // Close the actual transport, leaving its remote owner, tab, Chrome page
        // and registration wire alive. The visible route owns all reconnection.
        compose.runOnIdle { oldConnection.close() }
        compose.waitUntil(20000) {
            oldProvider.state.value.ended && session.connections.statuses.value[hostId]?.phase == SshConnectionPhase.CONNECTED
        }
        cmux = runBlocking { session.cmux.open(hostId) }
        assertNotSame(oldConnection, cmux.connection)
        assertTrue(cmux.connection.isConnected)
        compose.waitUntil(15000) { !cmux.state.value.loading }
        ready("SSH browser page"); background(90, 40, 110)
        assertEquals(original, workspace().tabs.single { it.isBrowser }.resource)
        compose.onNodeWithContentDescription("Browser address").assertTextEquals(next)
        assertEquals(1, clicks())
        click(); compose.waitUntil(10000) { clicks() == 2 }
        capture("ssh-real-chrome-reconnected")
    }

}
