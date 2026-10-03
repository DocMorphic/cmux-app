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
    @get:Rule val testName = org.junit.rules.TestName()
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
            runCatching { capture("ssh-real-chrome-${testName.methodName}-last-state") }
            runCatching { File(compose.activity.getExternalFilesDir(null), "ssh-real-chrome-${testName.methodName}-semantics.txt").writeText(compose.onRoot().printToString()) }
            compose.runOnIdle { session.close(); lifetime.cancel() }
            session.vault.state.value.toList().forEach { session.vault.delete(it.id) }
        }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private fun workspace() = cmux.state.value.providers.single { it.session == "fixture" }.state.value.tree!!.workspaces.single()
    private fun ready(description: String) {
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription(description).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun pointerReady() {
        compose.waitUntil(15000) {
            compose.onAllNodes(hasContentDescription("SSH browser page") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
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
        pointerReady(); page.performTouchInput { click(Offset(width * .5f, height * .33f)) }
        compose.waitUntil(10000) { events().contains("\"click\":[\"1\"]") }
        pointerReady(); page.performTouchInput { click(Offset(width * .5f, height * .58f)) }
        compose.onNodeWithContentDescription("Show browser keyboard").performClick()
        ready("Hide browser keyboard")
        device.pressKeyCode(KeyEvent.KEYCODE_A)
        compose.waitUntil(10000) { events().contains("\"text\":[\"a\"]") }
        compose.onNodeWithContentDescription("Hide browser keyboard").performClick()
        ready("Show browser keyboard")
        val next = "http://127.0.0.1:${args.getString("cmux_ssh_browserport")}/next"
        // Enter through the real focus action before editing: a semantics-only
        // replacement can race the address field's focus initialization.
        compose.onNodeWithContentDescription("Browser address").performClick().assertIsFocused()
        compose.onNodeWithContentDescription("Browser address").performTextReplacement(next)
        compose.onNodeWithContentDescription("Browser address").assertTextEquals(next)
        compose.onNodeWithContentDescription("Browser address").performImeAction()
        ready("Show browser keyboard")
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
    @Test fun groupedPickerCrossesBrowserProcessAndCreatesExactSelectedTerminals() {
        fun provider() = cmux.state.value.providers.single { it.session == "fixture" }
        fun terminalTarget(tab: SshCmuxTab): SshWorkspaceTarget.Cmux {
            val tree = provider().state.value.tree!!
            val owner = tree.workspaces.single { tab in it.tabs }
            return SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(provider().session, tree, owner, tab))
        }
        fun selected(tab: SshCmuxTab) {
            val tag = "ssh.shell.identity.cmux-ssh-$hostId\n${terminalTarget(tab).encode()}"
            compose.waitUntil(15000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("ssh.shell.composer").assertIsEnabled()
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        open(); ready("SSH browser page")
        val original = workspace(); val terminal = original.tabs.first { it.isTerminal }
        val browser = original.tabs.single { it.isBrowser }
        val browserTarget = SshWorkspaceTarget.Browser(SshCmuxBrowserSelection.capture(provider().session, provider().state.value.tree!!, original, browser))
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onNodeWithText("Screen 1").assertExists(); compose.onNodeWithText("Browsers").assertExists()
        compose.onNodeWithTag("ssh.picker.row.${terminalTarget(terminal).encode()}").performScrollTo().performClick()
        selected(terminal)
        var visitedPhone = false
        fun openPhoneMenu() {
            compose.onNodeWithTag("ssh.shell.menu").performClick()
            compose.onNodeWithTag("ssh.picker.row.${browserTarget.encode()}").performScrollTo().performClick()
            if (!visitedPhone) {
                ready("Browser mode")
                compose.onNodeWithContentDescription("Browser mode").performClick()
                compose.onNodeWithText("On Android").performClick()
            }
            compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            visitedPhone = true
            uiText("SSH Chrome start ▾").click()
            uiText("Screen 1"); uiText("Browsers")
        }
        openPhoneMenu(); capture("ssh-browser-grouped-picker")
        uiText("New Screen").click()
        compose.waitUntil(15000) { compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && workspace().screens.size == 2 }
        val newScreen = workspace().screens.single { it.id !in original.screens.map { s -> s.id } }
        selected(newScreen.panes.single().tabs.single())
        openPhoneMenu(); uiText("Screen 2")
        val before = workspace().tabs.map { it.surface }.toSet()
        uiText("New Tab").click()
        compose.waitUntil(15000) { compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && workspace().tabs.size == before.size + 1 }
        selected(workspace().tabs.single { it.surface !in before })
        openPhoneMenu(); uiText("New Workspace").click()
        compose.waitUntil(15000) { compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && provider().state.value.tree!!.workspaces.size == 2 }
        val created = provider().state.value.tree!!.workspaces.single { it.key != original.key }
        selected(created.tabs.single())
        assertEquals(browser.resource, provider().state.value.tree!!.workspaces.single { it.key == original.key }.tabs.single { it.isBrowser }.resource)
    }
    @Test fun tmuxBrowserMenuCreatesWindowsSplitsAndWorkspacesInExactTargets() {
        val originalCmux = workspace()
        val tmux = runBlocking { session.tmux.open(hostId) }
        compose.waitUntil(10000) { !tmux.state.value.loading }
        val original = tmux.state.value.workspaces.single()
        val originalPane = original.panes.single()
        val network = compose.runOnIdle { session.browsers.network(hostId) }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        val tag = "ssh.tmux.pane.${original.id}.${originalPane.id}"
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag(tag).performScrollTo().performClick()
        fun selected(workspace: SshTmuxWorkspace, pane: SshTmuxPaneRow) {
            compose.waitUntil(15000) { compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            compose.waitUntil(15000) {
                compose.onAllNodesWithTag("ssh.shell.identity.cmux-ssh-$hostId:tmux:${workspace.id}/%${pane.id}").fetchSemanticsNodes().isNotEmpty() &&
                    compose.onAllNodes(hasTestTag("ssh.shell.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
            }
        }
        fun phoneMenu(workspace: SshTmuxWorkspace, pane: SshTmuxPaneRow) {
            selected(workspace, pane)
            val browserWorkspace = sshBrowserWorkspace(SshWorkspaceTarget.Tmux(workspace.id, pane.window, pane.id), pane.title)
            compose.runOnIdle {
                network.navigation.restoreRemembered(sshLocalBrowserKey(network, browserWorkspace), browserWorkspace)
                network.navigation.state.value.local!!.surface.load("http://localhost:${args.getString("cmux_ssh_browserport")}/next")
            }
            compose.onNodeWithTag("ssh.shell.menu").performClick(); compose.onNodeWithText("New Browser").performClick()
            compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
            uiText("SSH Chrome next ▾").click(); uiText("New Window")
            assertFalse(device.hasObject(By.text("New Tab")))
            assertFalse(device.hasObject(By.text("New Screen")))
        }
        phoneMenu(original, originalPane)
        capture("ssh-tmux-browser-picker")
        uiText("New Window").click()
        compose.waitUntil(15000) { tmux.state.value.workspaces.single().panes.size == 2 }
        var current = tmux.state.value.workspaces.single()
        val newWindow = current.panes.single { it.id != originalPane.id }
        assertNotEquals(originalPane.window, newWindow.window)
        selected(current, newWindow)
        phoneMenu(current, newWindow)
        // Choose the first window's split action while viewing the second.
        // Routing must honor that section, not whichever pane was previously open.
        uiText("Split Right").click()
        compose.waitUntil(15000) { tmux.state.value.workspaces.single().panes.size == 3 }
        current = tmux.state.value.workspaces.single()
        val split = current.panes.single { it.id !in original.panes.map { p -> p.id } && it.id != newWindow.id }
        assertEquals(originalPane.window, split.window)
        selected(current, split)
        val splitTerminal = runBlocking { tmux.open(current, split) }
        compose.onNodeWithTag("ssh.shell.composer").performTextReplacement("browser selected split λ")
        compose.onNodeWithTag("ssh.shell.send").performClick()
        compose.waitUntil(10000) { compose.runOnIdle { TerminalTextSnapshot.capture(splitTerminal.display).text.contains("browser selected split λ") } }
        phoneMenu(current, split); uiText("New Workspace").click()
        compose.waitUntil(15000) { tmux.state.value.workspaces.size == 2 }
        val created = tmux.state.value.workspaces.single { it.id != original.id }
        selected(created, created.panes.single())
        assertEquals(3, tmux.state.value.workspaces.single { it.id == original.id }.panes.size)
        assertEquals(originalCmux.key, workspace().key)
        assertEquals(originalCmux.tabs.map { it.resource }, workspace().tabs.map { it.resource })
        capture("ssh-tmux-browser-created-workspace")
    }
    @Test fun linkedPhoneBrowserCreatesIndependentBrowserAndReturnsToOriginalPage() {
        val original = workspace()
        val browserTab = original.tabs.single { it.isBrowser }
        val provider = cmux.state.value.providers.single { it.session == "fixture" }
        val network = compose.runOnIdle { session.browsers.network(hostId) }
        val browserTitle = sshCmuxPicker(provider.session, provider.state.value.tree!!, original).browsers.single().title
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        open(); ready("SSH browser page")
        compose.onNodeWithContentDescription("Browser mode").performClick(); compose.onNodeWithText("On Android").performClick()
        compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) }
        uiText("SSH Chrome start ▾")
        val linked = runBlocking { withContext(Dispatchers.Main) { network.navigation.state.value.local!!.surface } }
        assertNotNull(linked.linkedStreamPanelId)
        // Give the phone-linked page its own URL, independently of Chrome's page.
        uiText("Next Chrome page").click()
        uiText("SSH Chrome next ▾").click(); uiText("New Browser").click()
        compose.waitUntil(15000) {
            network.navigation.state.value.local?.surface?.let { it !== linked && it.linkedStreamPanelId == null } == true &&
                !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        val independent = runBlocking { withContext(Dispatchers.Main) { network.navigation.state.value.local!!.surface } }
        // The browser process owns its live WebView. Navigate through its actual
        // address field rather than changing the parent's detached page model.
        val selector = By.clazz("android.widget.EditText").hasDescendant(By.desc("Browser address"))
        val address = checkNotNull(device.wait(Until.findObject(By.copy(selector).text("https://duckduckgo.com/")), 15000))
        address.click()
        assertTrue(device.wait(Until.hasObject(By.copy(selector).focused(true)), 5000))
        val url = "http://localhost:${args.getString("cmux_ssh_browserport")}/"
        address.text = url
        assertTrue(device.wait(Until.hasObject(By.copy(selector).text(url)), 5000))
        device.pressEnter()
        uiText("SSH Chrome start ▾").click()
        uiText("New Browser")
        capture("ssh-linked-to-independent-browser")
        uiText("New Browser").click() // Already selected: must not create another page.
        assertSame(independent, network.navigation.state.value.local!!.surface)
        uiText("SSH Chrome start ▾").click(); uiText(browserTitle).click()
        compose.waitUntil(15000) {
            network.navigation.state.value.local?.surface === linked && !compose.activity.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        uiText("SSH Chrome next ▾")
        assertFalse(linked.state.value.closed)
        assertTrue(independent.state.value.closed)
        assertEquals(browserTab.resource, workspace().tabs.single { it.isBrowser }.resource)
        uiText("Fixture click").click(); uiText("SSH Chrome clicked ▾")
        compose.waitUntil(10000) { events().contains("\"click\":[\"1\"]") }
        capture("ssh-returned-linked-browser")
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
        pointerReady(); click(); compose.waitUntil(10000) { clicks() == 1 }; background(90, 40, 110)
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
        pointerReady(); click(); compose.waitUntil(10000) { clicks() == 2 }
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
        pointerReady(); click(); compose.waitUntil(10000) { clicks() == 1 }; background(90, 40, 110)
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
        pointerReady(); click(); compose.waitUntil(10000) { clicks() == 2 }; background(90, 40, 110)
        capture("ssh-chrome-process-recovered")
    }

    @Test fun desktopDaemonRestartExplainsRecoveryAndRestoresTheSameBrowser() {
        val before = cmux.state.value.providers.single { it.session == "fixture" }
        val oldTree = before.state.value.tree!!
        val original = workspace().tabs.single { it.isBrowser }
        val connection = cmux.connection
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        open(); ready("SSH browser page"); background(18, 95, 55)
        fun owner() = runBlocking {
            JSONObject(connection.exec("fixture-owner-status").stdout.toString(Charsets.UTF_8))
        }
        fun clicks(): Int = runBlocking {
            val items = JSONObject(connection.exec("fixture-browser-status").stdout.toString(Charsets.UTF_8)).getJSONArray("events")
            (0 until items.length()).count { items.getJSONObject(it).has("click") }
        }
        fun click() = compose.onNodeWithContentDescription("SSH browser page").performTouchInput {
            click(Offset(width * .5f, height * .33f))
        }
        pointerReady(); click(); compose.waitUntil(10000) { clicks() == 1 }; background(90, 40, 110)
        assertEquals(0, runBlocking { connection.exec("fixture-stop-desktop-owner").exitStatus })
        compose.waitUntil(15000) { compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(before.state.value.ended); assertTrue(connection.isConnected)
        assertFalse(owner().getBoolean("desktop"))
        click(); compose.waitForIdle(); assertEquals(1, clicks())
        compose.onNodeWithText("Reconnect").performClick()
        val explanation = "This desktop cmux-tui session is not running. Start it on the computer, then reconnect."
        compose.waitUntil(15000) { compose.onAllNodesWithText(explanation).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(owner().getBoolean("desktop")); assertTrue(connection.isConnected)
        capture("ssh-browser-daemon-stopped")
        // Only the fixture starts its own desktop service. Android must not
        // create a new desktop owner or substitute a different browser tab.
        assertEquals(0, runBlocking { connection.exec("fixture-restart-browser-owner").exitStatus })
        compose.onNodeWithText("Reconnect").performClick()
        ready("SSH browser page"); background(90, 40, 110)
        compose.waitUntil(15000) { compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().isEmpty() }
        val after = cmux.state.value.providers.single { it.session == "fixture" }
        val tree = after.state.value.tree!!
        assertNotSame(before, after)
        assertNotEquals(oldTree.generation, tree.generation); assertEquals(oldTree.registry, tree.registry)
        val tab = workspace().tabs.single { it.isBrowser }
        assertEquals(original.resource, tab.resource); assertEquals(original.content, tab.content)
        assertSame(connection, cmux.connection); assertTrue(connection.isConnected)
        assertEquals(1, clicks())
        pointerReady(); click(); compose.waitUntil(10000) { clicks() == 2 }
        capture("ssh-browser-daemon-recovered")
    }

    @Test fun lostReplyAfterRealClickPausesAndReconnectsWithoutReplay() {
        val before = cmux.state.value.providers.single { it.session == "fixture" }
        val original = workspace().tabs.single { it.isBrowser }.resource
        val generation = before.state.value.tree!!.generation
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
        assertEquals(0, runBlocking { connection.exec("fixture-browser-drop-next-click-reply").exitStatus })
        pointerReady(); click()
        compose.waitUntil(15000) { status().getInt("replyLosses") == 1 && clicks() == 1 }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(15000) { compose.onAllNodesWithText("Delivery was not confirmed", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(before.state.value.ended); assertTrue(connection.isConnected)
        assertEquals(1, status().getInt("mouseReleases"))
        capture("ssh-browser-unconfirmed-click")
        compose.onNodeWithText("Reconnect").performClick()
        ready("SSH browser page"); background(90, 40, 110)
        compose.waitUntil(15000) { compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().isEmpty() }
        val after = cmux.state.value.providers.single { it.session == "fixture" }
        assertNotSame(before, after); assertEquals(generation, after.state.value.tree!!.generation)
        assertEquals(original, workspace().tabs.single { it.isBrowser }.resource)
        assertSame(connection, cmux.connection); assertTrue(connection.isConnected)
        assertEquals(1, status().getInt("replyLosses")); assertFalse(status().getBoolean("replyLossArmed"))
        assertEquals(1, status().getInt("mouseReleases")); assertEquals(1, clicks())
        pointerReady(); click(); compose.waitUntil(10000) { clicks() == 2 }
        assertEquals(2, status().getInt("mouseReleases"))
        capture("ssh-browser-unconfirmed-recovered")
    }

    @Test fun liveConnectionLossRestoresSameBrowserAndDoesNotReplayCompletedClick() {
        val original = workspace().tabs.single { it.isBrowser }.resource
        val oldProvider = cmux.state.value.providers.single { it.session == "fixture" }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        open(); ready("SSH browser page"); background(18, 95, 55)
        val next = "http://127.0.0.1:${args.getString("cmux_ssh_browserport")}/next"
        // Enter through the real focus action before editing: a semantics-only
        // replacement can race the address field's focus initialization.
        compose.onNodeWithContentDescription("Browser address").performClick().assertIsFocused()
        compose.onNodeWithContentDescription("Browser address").performTextReplacement(next)
        compose.onNodeWithContentDescription("Browser address").assertTextEquals(next)
        compose.onNodeWithContentDescription("Browser address").performImeAction()
        ready("Show browser keyboard")
        background(30, 60, 120)
        fun click() = compose.onNodeWithContentDescription("SSH browser page").performTouchInput {
            click(Offset(width * .5f, height * .33f))
        }
        fun clicks(): Int {
            val records = JSONObject("{\"events\":${events()}}").getJSONArray("events")
            return (0 until records.length()).count { records.getJSONObject(it).has("click") }
        }
        pointerReady(); click(); compose.waitUntil(10000) { clicks() == 1 }; background(90, 40, 110)
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
        pointerReady(); click(); compose.waitUntil(10000) { clicks() == 2 }
        capture("ssh-real-chrome-reconnected")
    }

}
