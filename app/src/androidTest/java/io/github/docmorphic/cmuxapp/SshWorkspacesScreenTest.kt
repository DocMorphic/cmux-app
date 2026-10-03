package io.github.docmorphic.cmuxapp

import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withTagValue
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.hamcrest.Matchers.`is`
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Emulator-only, real SSH + cmux-tui + tmux + production Compose/Ghostty. */
class SshWorkspacesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var session: NativeSshSession
    private lateinit var lifetime: CoroutineScope
    private lateinit var hostId: UUID
    private lateinit var cmux: SshCmuxHost
    private var metadata: String? = null
    @Before fun setup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cmux_ssh_nonce") == "cmux")
        root = File(compose.activity.noBackupFilesDir, "cmux-ui-${UUID.randomUUID()}")
        val hosts = SshHostStore({ metadata }, { metadata = it })
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        val key = vault.generate("mixed fixture")
        val host = SshHostRecord(name = "SSH fixture", keyId = key.id,
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
        assertEquals("Desktop cmux", provider().state.value.tree!!.workspaces.single().name)
    }
    @After fun cleanup() {
        if (::session.isInitialized) {
            compose.runOnIdle { session.close(); lifetime.cancel() }
            session.vault.state.value.toList().forEach { session.vault.delete(it.id) }
        }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private fun provider() = cmux.state.value.providers.single { it.session == "fixture" }
    private fun workspace() = provider().state.value.tree!!.workspaces.first { it.name == "Desktop cmux" }
    private fun show() = compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        SshWorkspacesRoute(session, hostId) {}
    } } }
    private fun ready(tag: String = "ssh.shell.composer") {
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun openCmux() {
        val workspace = workspace(); val tab = workspace.tabs.first()
        val tag = "ssh.cmux.terminal.${workspace.key}.${tab.surface}"
        ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick(); ready()
    }
    private fun send(value: String) {
        ready(); compose.onNodeWithTag("ssh.shell.composer").performTextReplacement(value)
        compose.onNodeWithTag("ssh.shell.send").performClick(); waitText(value)
    }
    private fun terminalIdentity(): String? = compose.onAllNodes(SemanticsMatcher("SSH terminal identity") {
        it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("ssh.shell.identity.") == true
    }).fetchSemanticsNodes().singleOrNull()?.config?.get(SemanticsProperties.TestTag)
    private fun selectNewTerminal(action: () -> Unit) {
        val old = checkNotNull(terminalIdentity())
        action()
        compose.waitUntil(15000) { terminalIdentity()?.let { it != old } == true &&
            compose.onAllNodes(hasTestTag("ssh.shell.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    private fun waitText(value: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var text = ""
        do {
            ready("ssh.shell.text")
            compose.onNodeWithTag("ssh.shell.text").performClick()
            if (compose.onAllNodesWithText("No terminal text available").fetchSemanticsNodes().isEmpty()) {
                onView(withTagValue(`is`("terminal-text-snapshot" as Any))).check { view, error ->
                    if (error != null) throw error
                    text = (view as TextView).text.toString()
                }
            }
            compose.onNodeWithText("Done").performClick()
            if (text.contains(value)) return
            Thread.sleep(150)
        } while (System.nanoTime() < deadline)
        fail("Expected fixture output '$value', got '$text'")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitForIdle(100, 3000); Thread.sleep(300)
        val bitmap = ui.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun mixedWorkspacesStreamReopenCreateAndEndWithConfirmation() {
        show(); openCmux(); waitText("Remote cmux λ 中"); send("Phone cmux λ 中")
        capture("cmux-ssh-terminal")
        compose.onNodeWithText("Back").performClick()
        openCmux(); waitText("Phone cmux λ 中")
        compose.onNodeWithText("Back").performClick()
        val key = workspace().key
        compose.onNodeWithTag("ssh.cmux.new-terminal.$key").performScrollTo().performClick()
        compose.waitUntil(15000) { workspace().tabs.size == 2 }
        ready(); compose.onNodeWithText("Back").performClick()
        ready("ssh.cmux.create.fixture")
        compose.onNodeWithTag("ssh.cmux.create.fixture").performScrollTo().performClick()
        compose.waitUntil(15000) { provider().state.value.tree!!.workspaces.size == 2 }
        capture("cmux-ssh-workspaces")
        ready("ssh.cmux.end.$key")
        compose.onNodeWithTag("ssh.cmux.end.$key").performScrollTo().performClick()
        compose.onNodeWithText("End “Desktop cmux” on SSH fixture?").assertIsDisplayed()
        compose.onNodeWithText("Close Workspace").assertIsDisplayed()
        capture("cmux-close-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(2, provider().state.value.tree!!.workspaces.size)
        compose.onNodeWithTag("ssh.cmux.end.$key").performClick()
        compose.onNodeWithText("Close Workspace").performClick()
        compose.waitUntil(15000) { provider().state.value.tree!!.workspaces.none { it.key == key } }
        val tmux = runBlocking { session.tmux.open(hostId) }
        compose.waitUntil(10000) { !tmux.state.value.loading }
        val tw = tmux.state.value.workspaces.single(); val tp = tw.panes.first()
        compose.onNodeWithTag("ssh.tmux.pane.${tw.id}.${tp.id}").performScrollTo().performClick()
        ready(); waitText("Remote tmux"); send("Phone tmux")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("ssh.workspaces.new-shell").performScrollTo().performClick()
        ready(); waitText("Plain shell fixture λ 中"); send("Phone shell")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("ssh.tmux.end.${tw.id}").performScrollTo().performClick()
        compose.onNodeWithText("End “desktop-tmux” on SSH fixture?").assertIsDisplayed()
        compose.onNodeWithText("End Session").assertIsDisplayed()
        capture("tmux-close-confirmation")
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(tmux.state.value.workspaces.any { it.id == tw.id })
        compose.onNodeWithTag("ssh.tmux.end.${tw.id}").performClick()
        compose.onNodeWithText("End Session").performClick()
        compose.waitUntil(10000) { tmux.state.value.workspaces.none { it.id == tw.id } }
        assertEquals(1, session.shells.state.value.size)
    }
    @Test fun createsOnlyPhoneOwnedSessionAndKeepsDesktopWorkspaceIntact() {
        show(); ready("ssh.cmux.create-owned")
        val desktop = workspace(); val original = provider().state.value.tree!!.registry
        compose.onNodeWithTag("ssh.cmux.create-owned").performScrollTo().performClick()
        compose.waitUntil(20000) { cmux.state.value.providers.any { it.session == "cmux-android" && it.state.value.tree?.workspaces?.size == 1 } }
        val owned = cmux.state.value.providers.single { it.session == "cmux-android" }
        val phone = owned.state.value.tree!!.workspaces.single(); val tab = phone.tabs.single()
        val desktopProvider = cmux.state.value.providers.single { it.session == "fixture" }
        assertEquals(original, desktopProvider.state.value.tree!!.registry)
        assertEquals(desktop.key, desktopProvider.state.value.tree!!.workspaces.single().key)
        val tag = "ssh.cmux.terminal.${phone.key}.${tab.surface}"
        ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick()
        send("created from Android")
        compose.onNodeWithText("Back").performClick()
        ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick()
        waitText("created from Android")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("ssh.cmux.end.${phone.key}").performScrollTo().performClick()
        compose.onNodeWithText("Close Workspace").performClick()
        compose.waitUntil(10000) { owned.state.value.tree!!.workspaces.isEmpty() }
        assertEquals(desktop.key, desktopProvider.state.value.tree!!.workspaces.single().key)
        capture("cmux-ssh-created-owner")
    }
    @Test fun newScreenTabAndBothSplitsSelectTheirCreatedTerminal() {
        show(); ready("ssh.cmux.create.fixture")
        val original = workspace(); val key = original.key; val screen = original.screens.single(); val pane = screen.panes.single()
        fun create(tag: String, text: String) {
            ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick()
            send(text) // No second tap: the created terminal must be selected.
            compose.onNodeWithText("Back").performClick()
        }
        create("ssh.cmux.new-terminal.$key", "new screen λ 中")
        assertEquals(2, workspace().screens.size)
        create("ssh.cmux.new-tab.$key.${pane.id}", "new tab λ 中")
        assertEquals(2, workspace().screens.first { it.id == screen.id }.panes.single().tabs.size)
        create("ssh.cmux.split-right.$key.${pane.id}", "right split λ 中")
        assertEquals(2, workspace().screens.first { it.id == screen.id }.panes.size)
        create("ssh.cmux.split-down.$key.${pane.id}", "down split λ 中")
        val updated = workspace().screens.first { it.id == screen.id }
        assertEquals(3, updated.panes.size)
        fun directions(layout: SshCmuxLayout?): Set<Boolean> = when (layout) {
            is SshCmuxLayout.Split -> directions(layout.a) + directions(layout.b) + layout.right
            else -> emptySet()
        }
        assertEquals(setOf(true, false), directions(updated.layout))
        assertEquals(5, workspace().tabs.size)
        assertTrue(workspace().tabs.any { it.terminal == original.tabs.single().terminal })
        capture("cmux-ssh-layout-actions")
        compose.onNodeWithTag("ssh.cmux.end.$key").performScrollTo().performClick()
        compose.onNodeWithText("Close Workspace").performClick()
        compose.waitUntil(15000) { provider().state.value.tree!!.workspaces.none { it.key == key } }
    }
    @Test fun groupedCmuxPickerCreatesSelectsAndReturnsToExactTerminal() {
        show(); openCmux(); send("original picker terminal")
        val original = workspace(); val screen = original.screens.single()
        val originalTarget = SshWorkspaceTarget.Cmux(SshCmuxSelection.capture(provider().session, provider().state.value.tree!!, original, original.tabs.single()))
        fun action(tag: String, marker: String) {
            ready("ssh.shell.menu"); compose.onNodeWithTag("ssh.shell.menu").performClick()
            selectNewTerminal { compose.onNodeWithTag(tag).performScrollTo().performClick() }
            send(marker)
        }
        action("ssh.picker.action.${screen.id}.NEW_TAB", "picker new tab")
        assertEquals(2, workspace().tabs.size)
        action("ssh.picker.action.${screen.id}.SPLIT_RIGHT", "picker right split")
        action("ssh.picker.action.${screen.id}.SPLIT_DOWN", "picker down split")
        assertEquals(3, workspace().screens.single().panes.size)
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onAllNodesWithText("Pane 1").onFirst().assertExists()
        capture("ssh-cmux-grouped-picker")
        compose.onNodeWithTag("ssh.picker.row.${originalTarget.encode()}").performScrollTo().performClick()
        waitText("original picker terminal")
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onNodeWithTag("ssh.picker.row.${originalTarget.encode()}").assertIsSelected()
        selectNewTerminal { compose.onNodeWithText("New Screen").performScrollTo().performClick() }
        send("picker new screen")
        assertEquals(2, workspace().screens.size)
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        selectNewTerminal { compose.onNodeWithText("New Workspace").performScrollTo().performClick() }
        send("picker new workspace")
        assertEquals(2, provider().state.value.tree!!.workspaces.size)
    }
    @Test fun groupedTmuxPickerSelectsReturnedPaneForWindowSplitAndWorkspace() {
        show(); ready("ssh.cmux.create.fixture")
        val tmux = runBlocking { session.tmux.open(hostId) }
        compose.waitUntil(10000) { !tmux.state.value.loading }
        val original = tmux.state.value.workspaces.single(); val pane = original.panes.first()
        compose.onNodeWithTag("ssh.tmux.pane.${original.id}.${pane.id}").performScrollTo().performClick()
        send("original tmux picker")
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        selectNewTerminal { compose.onNodeWithText("New Window").performScrollTo().performClick() }
        send("picker created window")
        val created = tmux.state.value.workspaces.single().panes.single { next -> original.panes.none { it.id == next.id } }
        for (action in listOf(SshPaneAction.SPLIT_RIGHT, SshPaneAction.SPLIT_DOWN)) {
            compose.onNodeWithTag("ssh.shell.menu").performClick()
            selectNewTerminal { compose.onNodeWithTag("ssh.picker.action.${created.window}.${action.name}").performScrollTo().performClick() }
            send("picker tmux ${action.name}")
        }
        assertEquals(3, tmux.state.value.workspaces.single().panes.count { it.window == created.window })
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        capture("ssh-tmux-grouped-picker")
        val first = SshWorkspaceTarget.Tmux(original.id, pane.window, pane.id)
        compose.onNodeWithTag("ssh.picker.row.${first.encode()}").performScrollTo().performClick()
        waitText("original tmux picker")
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onNodeWithTag("ssh.picker.row.${first.encode()}").assertIsSelected()
        selectNewTerminal { compose.onNodeWithText("New Workspace").performScrollTo().performClick() }
        send("picker created tmux workspace")
        assertEquals(2, tmux.state.value.workspaces.size)
    }
    @Test fun ownerRestartRestoresSameTerminalWhileListingNeverRestartsDesktopOrPhone() {
        show(); ready("ssh.cmux.create-owned")
        compose.onNodeWithTag("ssh.cmux.create-owned").performScrollTo().performClick()
        compose.waitUntil(15000) { cmux.state.value.providers.any { it.session == "cmux-android" && it.state.value.tree?.workspaces?.size == 1 } }
        val before = cmux.state.value.providers.single { it.session == "cmux-android" }
        val tree = before.state.value.tree!!; val workspace = tree.workspaces.single(); val tab = workspace.tabs.single()
        val tag = "ssh.cmux.terminal.${workspace.key}.${tab.surface}"
        ready(tag); compose.onNodeWithTag(tag).performScrollTo().performClick(); send("before owner stop λ 中")
        fun status() = runBlocking { org.json.JSONObject(cmux.connection.exec("fixture-owner-status").stdout.toString(Charsets.UTF_8)) }
        val ensures = status().getInt("phoneEnsures")
        assertEquals(0, runBlocking { cmux.connection.exec("fixture-stop-phone-owner").exitStatus })
        ready("ssh.shell.reconnect")
        assertTrue(cmux.connection.isConnected); assertFalse(status().getBoolean("phone"))
        // A separate list consumer must not restart the stopped owner. The
        // visible route still retains its ended provider until user Reconnect.
        val observer = compose.runOnIdle { SshCmuxHost(hostId, cmux.connection, lifetime, { lifetime.isActive }) }
        try {
            compose.waitUntil(15000) { !observer.state.value.loading }
            assertTrue(observer.state.value.providers.none { it.session == "cmux-android" })
            assertFalse(status().getBoolean("phone")); assertEquals(ensures, status().getInt("phoneEnsures"))
        } finally { compose.runOnIdle { observer.close() } }
        compose.onNodeWithTag("ssh.shell.reconnect").performClick()
        ready(); waitText("before owner stop λ 中"); send("after owner restart λ 中")
        // Read the current registry only after UI-driven recovery and input
        // succeed. A retired SSH host is not the route's current provider.
        cmux = runBlocking { session.cmux.open(hostId) }
        val after = cmux.state.value.providers.single { it.session == "cmux-android" }
        assertNotSame(before, after)
        assertNotEquals(tree.generation, after.state.value.tree!!.generation)
        assertEquals(tree.registry, after.state.value.tree!!.registry)
        assertEquals(tab.terminal, after.state.value.tree!!.tabs.single().terminal)
        assertEquals(ensures + 1, status().getInt("phoneEnsures"))
        capture("cmux-ssh-owner-restarted")
        compose.onNodeWithText("Back").performClick()
        openCmux(); waitText("Remote cmux λ 中")
        assertEquals(0, runBlocking { cmux.connection.exec("fixture-stop-desktop-owner").exitStatus })
        ready("ssh.shell.reconnect"); compose.onNodeWithTag("ssh.shell.reconnect").performClick()
        val error = "This desktop cmux-tui session is not running. Start it on the computer, then reconnect."
        compose.waitUntil(15000) { compose.onAllNodesWithText(error).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(status().getBoolean("desktop")); assertTrue(cmux.connection.isConnected)
        compose.onNodeWithTag("ssh.shell.composer").assertIsNotEnabled()
    }
    @Test fun droppedConnectionReattachesButExplicitDisconnectWaitsForUser() {
        show(); openCmux(); send("before connection loss")
        val old = cmux.connection
        compose.runOnIdle { old.close() }
        compose.waitUntil(10000) { !old.isConnected }
        ready(); waitText("before connection loss"); send("after connection loss")
        assertTrue(session.connections.statuses.value[hostId]?.phase == SshConnectionPhase.CONNECTED)
        compose.runOnIdle { session.connections.disconnect(hostId) }
        ready("ssh.shell.reconnect")
        Thread.sleep(600)
        assertEquals(SshConnectionPhase.IDLE, session.connections.statuses.value[hostId]?.phase)
        compose.onNodeWithTag("ssh.shell.composer").assertIsNotEnabled()
        assertTrue(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
        compose.onNodeWithTag("ssh.shell.reconnect").performClick()
        ready(); waitText("after connection loss"); send("explicit reconnect")
        assertFalse(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
    }
    @Test fun savedCmuxSelectionRestoresAcrossRuntimeAndPreservesDisconnect() {
        val restore = StateRestorationTester(compose)
        restore.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            SshWorkspacesRoute(session, hostId) {}
        } } }
        openCmux(); send("saved cmux runtime")
        fun replaceRuntime() = compose.runOnIdle {
            session.close(); lifetime.cancel()
            val hosts = SshHostStore({ metadata }, { metadata = it })
            val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
            lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            session = NativeSshSession(hosts, vault, lifetime) { lifetime.isActive }
        }
        replaceRuntime(); restore.emulateSavedInstanceStateRestore()
        ready(); waitText("saved cmux runtime"); send("restored cmux runtime")
        compose.runOnIdle { session.connections.disconnect(hostId) }
        ready("ssh.shell.reconnect")
        replaceRuntime(); restore.emulateSavedInstanceStateRestore()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Reconnect").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
        assertTrue(session.connections.statuses.value.isEmpty())
        compose.onNodeWithText("Reconnect").performClick()
        ready(); waitText("restored cmux runtime")
        assertFalse(session.hosts.state.value.host(hostId)!!.autoConnectPaused)
        capture("cmux-ssh-restored")
    }
}
