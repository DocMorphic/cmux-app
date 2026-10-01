package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Production UI/renderer/controller/SSH against the private real tmux fixture. */
class SshTmuxScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var session: NativeSshSession
    private lateinit var lifetime: CoroutineScope
    private lateinit var provider: SshTmuxHost
    @Before fun setup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.getString("cmux_ssh_nonce") == "tmux")
        root = File(compose.activity.noBackupFilesDir, "tmux-ui-${UUID.randomUUID()}")
        val hosts = SshHostStore({ null }, {})
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        val key = vault.generate("tmux fixture")
        val host = SshHostRecord(name = "tmux fixture", keyId = key.id,
            endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!))
        hosts.upsert(host)
        assertTrue(hosts.confirmHostKey(hosts.dialPlan(host.id), host.id, hosts.trustSnapshot(host.endpoint), SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)))
        lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        session = NativeSshSession(hosts, vault, lifetime) { lifetime.isActive }
        runBlocking {
            val connection = session.connections.open(host.id)
            assertEquals(0, connection.exec("fixture-reset").exitStatus)
            provider = session.tmux.open(host.id)
        }
        compose.waitUntil(10000) { !provider.state.value.loading }
        assertNull(provider.state.value.error); assertTrue(provider.state.value.available)
        assertEquals("desktop", provider.state.value.workspaces.single().name)
    }
    @After fun cleanup() {
        if (::session.isInitialized) {
            compose.runOnIdle { session.close(); lifetime.cancel() }
            session.vault.state.value.toList().forEach { session.vault.delete(it.id) }
        }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private fun show(route: Boolean = false) = compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        if (route) SshTmuxRoute(session, provider.hostId) {} else SshTmuxScreen(provider) {}
    } } }
    private fun remoteWindow(workspace: SshTmuxWorkspace): String = runBlocking {
        val result = provider.connection.exec(SshTmuxInventory.command("/fixture/tmux", "display-message", "-p", "-t", workspace.target, "#{window_id}"))
        assertEquals(0, result.exitStatus); result.stdout.toString(Charsets.UTF_8).trim()
    }
    private fun waitText(terminal: SshTmuxTerminal, value: String) {
        val until = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < until) {
            if (compose.runOnIdle { TerminalTextSnapshot.capture(terminal.display).text.contains(value) }) return
            Thread.sleep(50)
        }
        fail("Missing tmux terminal output: $value")
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitForIdle(100, 3000); Thread.sleep(400)
        val bitmap = ui.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    private fun waitAction(text: String) {
        compose.waitUntil(10000) { compose.onAllNodes(hasText(text) and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    @Test fun realTmuxWorkspaceOpensStreamsSplitsAndEndsWithConfirmation() {
        show()
        val workspace = provider.state.value.workspaces.single()
        val originalWindow = remoteWindow(workspace)
        val pane = workspace.panes.first()
        compose.onNodeWithTag("ssh.tmux.pane.${workspace.id}.${pane.id}").performScrollTo().performClick()
        val terminal = runBlocking { withContext(Dispatchers.Main) { provider.open(workspace, pane) } }
        compose.waitUntil(10000) { terminal.state.value.phase == SshShellPhase.RUNNING }
        waitText(terminal, "Remote λ 中")
        compose.runOnIdle { assertTrue(terminal.display.bracketedPaste) }
        compose.onNodeWithTag("ssh.shell.composer").performTextReplacement("Phone λ 中")
        compose.onNodeWithTag("ssh.shell.send").performClick()
        waitText(terminal, "Phone λ 中")
        capture("tmux-terminal")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("ssh.tmux.pane.${workspace.id}.${pane.id}").performScrollTo().performClick()
        val same = runBlocking { withContext(Dispatchers.Main) { provider.open(workspace, pane) } }
        assertSame(terminal, same)
        compose.onNodeWithText("Back").performClick()
        waitAction("New Terminal")
        compose.onNodeWithText("New Terminal").performScrollTo().performClick()
        compose.waitUntil(10000) { provider.state.value.workspaces.single().panes.size == 3 }
        waitAction("Split Right")
        compose.onAllNodesWithText("Split Right").onFirst().performScrollTo().assertIsEnabled().performClick()
        try { compose.waitUntil(10000) { provider.state.value.workspaces.single().panes.size == 4 } }
        catch (failure: Exception) { capture("tmux-split-failure"); throw AssertionError("Split did not complete: ${provider.state.value}", failure) }
        assertEquals(originalWindow, remoteWindow(workspace))
        waitAction("End Workspace")
        compose.runOnIdle { assertEquals(provider.state.value.workspaces.single().panes.first { it.id == pane.id }.title, terminal.title) }
        capture("tmux-workspaces")
        compose.onNodeWithTag("ssh.tmux.end.${workspace.id}").performScrollTo().performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1, provider.state.value.workspaces.size)
        compose.onNodeWithTag("ssh.tmux.end.${workspace.id}").performClick()
        compose.onAllNodesWithText("End Workspace").onLast().performClick()
        compose.waitUntil(10000) { provider.state.value.workspaces.isEmpty() && !provider.state.value.loading }
        assertNull(provider.state.value.error)
        val remaining = runBlocking { provider.connection.exec(SshTmuxInventory.command("/fixture/tmux", "list-sessions", "-F", "#{session_name}")) }
        assertTrue("Phone group must not keep the ended session alive", remaining.exitStatus != 0 || remaining.stdout.isEmpty())
        waitAction("New tmux Workspace")
        compose.onNodeWithTag("ssh.tmux.create").performClick()
        compose.waitUntil(10000) { provider.state.value.workspaces.singleOrNull()?.name == "cmux-1" }
    }
    @Test fun staleServerGuardRejectsMutationAndAccountRetirementRejectsPaneInput() {
        val workspace = provider.state.value.workspaces.single()
        val stale = workspace.copy(server = workspace.server + 1)
        val refusal = runBlocking { provider.connection.exec(SshTmuxInventory.guarded("/fixture/tmux", stale,
            "kill-session -t ${SshTmuxEncoding.quote(workspace.target)}")) }
        assertEquals(0, refusal.exitStatus)
        assertEquals("CMUX_STALE_TARGET", refusal.stdout.toString(Charsets.UTF_8).trim())
        assertTrue(remoteWindow(workspace).startsWith('@'))
        val terminal = runBlocking { withContext(Dispatchers.Main) { provider.open(workspace, workspace.panes.first()) } }
        compose.waitUntil(10000) { terminal.state.value.phase == SshShellPhase.RUNNING }
        compose.runOnIdle { lifetime.cancel(); assertFalse(terminal.send("must not send")) }
        compose.waitUntil(5000) { terminal.state.value.phase == SshShellPhase.ENDED && !provider.connection.isConnected }
    }
    @Test fun visiblePaneRecoversAfterDropButExplicitDisconnectWaitsForReconnect() {
        show(route = true)
        val workspace = provider.state.value.workspaces.single()
        val pane = workspace.panes.first()
        compose.waitUntil(10000) { compose.onAllNodesWithTag("ssh.tmux.pane.${workspace.id}.${pane.id}").fetchSemanticsNodes().isNotEmpty() }
        waitAction(pane.windowName)
        compose.onNodeWithTag("ssh.tmux.pane.${workspace.id}.${pane.id}").performScrollTo().performClick()
        compose.waitUntil(10000) { compose.onAllNodes(hasTestTag("ssh.shell.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ssh.shell.composer").performTextReplacement("before reconnect")
        compose.onNodeWithTag("ssh.shell.send").performClick()
        val old = runBlocking { withContext(Dispatchers.Main) { provider.open(workspace, pane) } }
        waitText(old, "before reconnect")
        compose.runOnIdle { provider.connection.close() }
        compose.waitUntil(10000) { old.state.value.phase == SshShellPhase.ENDED }
        // Wait for UI-driven recovery before reading the provider. Calling open
        // too soon here would repair a broken auto-connect implementation.
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("ssh.shell.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        provider = runBlocking { session.tmux.open(provider.hostId) }
        val recovered = runBlocking { withContext(Dispatchers.Main) { provider.open(workspace, pane) } }
        assertNotSame(old, recovered)
        waitText(recovered, "before reconnect")
        compose.onNodeWithTag("ssh.shell.composer").performTextReplacement("after reconnect")
        compose.onNodeWithTag("ssh.shell.send").performClick()
        waitText(recovered, "after reconnect")
        compose.runOnIdle { session.connections.disconnect(provider.hostId) }
        compose.waitUntil(10000) { recovered.state.value.phase == SshShellPhase.ENDED }
        waitAction("Reconnect")
        Thread.sleep(600)
        assertEquals(SshConnectionPhase.IDLE, session.connections.statuses.value[provider.hostId]?.phase)
        compose.onNodeWithTag("ssh.shell.composer").assertIsNotEnabled()
        assertTrue(session.hosts.state.value.host(provider.hostId)!!.autoConnectPaused)
        compose.onNodeWithTag("ssh.shell.reconnect").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("ssh.shell.composer") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        assertFalse(session.hosts.state.value.host(provider.hostId)!!.autoConnectPaused)
        capture("tmux-reconnected")
    }
}
