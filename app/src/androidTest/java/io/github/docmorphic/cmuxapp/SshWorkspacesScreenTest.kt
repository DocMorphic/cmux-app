package io.github.docmorphic.cmuxapp

import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
    private fun provider() = cmux.state.value.providers.single()
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
    private fun waitText(value: String) {
        val deadline = System.nanoTime() + 10_000_000_000L
        var text = ""
        do {
            compose.onNodeWithTag("ssh.shell.text").performClick()
            onView(withTagValue(`is`("terminal-text-snapshot" as Any))).check { view, error ->
                if (error != null) throw error
                text = (view as TextView).text.toString()
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
        ready("ssh.cmux.create.fixture")
        compose.onNodeWithTag("ssh.cmux.create.fixture").performScrollTo().performClick()
        compose.waitUntil(15000) { provider().state.value.tree!!.workspaces.size == 2 }
        capture("cmux-ssh-workspaces")
        ready("ssh.cmux.end.$key")
        compose.onNodeWithTag("ssh.cmux.end.$key").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(2, provider().state.value.tree!!.workspaces.size)
        compose.onNodeWithTag("ssh.cmux.end.$key").performClick()
        compose.onAllNodesWithText("End Workspace").onLast().performClick()
        compose.waitUntil(15000) { provider().state.value.tree!!.workspaces.none { it.key == key } }
        val tmux = runBlocking { session.tmux.open(hostId) }
        compose.waitUntil(10000) { !tmux.state.value.loading }
        val tw = tmux.state.value.workspaces.single(); val tp = tw.panes.first()
        compose.onNodeWithTag("ssh.tmux.pane.${tw.id}.${tp.id}").performScrollTo().performClick()
        ready(); waitText("Remote tmux"); send("Phone tmux")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("ssh.workspaces.new-shell").performScrollTo().performClick()
        ready(); waitText("Plain shell fixture λ 中"); send("Phone shell")
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
