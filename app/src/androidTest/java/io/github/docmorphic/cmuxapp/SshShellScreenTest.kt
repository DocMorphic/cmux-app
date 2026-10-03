package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Production Ghostty + SSH channel + UI against the generated loopback fixture. */
class SshShellScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var session: NativeSshSession
    private lateinit var lifetime: CoroutineScope
    private lateinit var host: SshHostRecord
    @Before fun setup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("cmux_ssh_port"))
        root = File(compose.activity.noBackupFilesDir, "ssh-shell-ui-${UUID.randomUUID()}")
        val hosts = SshHostStore({ null }, {})
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        val key = vault.generate("Shell fixture")
        lifetime = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = NativeSshSession(hosts, vault, lifetime) { lifetime.isActive }
        host = SshHostRecord(name = "SSH shell fixture", keyId = key.id,
            endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!))
        hosts.upsert(host)
        assertTrue(hosts.confirmHostKey(hosts.dialPlan(host.id), host.id, hosts.trustSnapshot(host.endpoint),
            SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)))
    }
    @After fun cleanup() {
        if (::session.isInitialized) {
            compose.runOnIdle { session.close(); lifetime.cancel() }
            session.vault.state.value.toList().forEach { session.vault.delete(it.id) }
        }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private fun show() = compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
        SshComputersScreen(session, {})
        SshPromptHost(session)
    } } }
    private fun open(): SshShell {
        show()
        compose.onNodeWithTag("ssh.host.${host.id}.shell").performScrollTo().performClick()
        compose.waitUntil(10000) { session.shells.state.value.singleOrNull()?.state?.value?.phase == SshShellPhase.RUNNING }
        return session.shells.state.value.single()
    }
    private fun command(text: String) {
        compose.onNodeWithTag("ssh.shell.composer").performTextReplacement(text)
        compose.onNodeWithTag("ssh.shell.send").performClick()
    }
    private fun text(shell: SshShell) = compose.runOnIdle { TerminalTextSnapshot.capture(shell.display).text }
    private fun waitText(shell: SshShell, value: String) {
        val until = System.nanoTime() + 8_000_000_000L
        while (System.nanoTime() < until) {
            if (text(shell).contains(value)) return
            Thread.sleep(50)
        }
        assertTrue("Missing $value in terminal output", text(shell).contains(value))
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitForIdle(100, 3000); Thread.sleep(400)
        val bitmap = ui.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
    @Test fun shellRendersAnsiAnswersQueriesAndKeepsSessionAcrossNavigation() {
        val shell = open()
        command("vt-demo"); waitText(shell, "Green λ 中")
        compose.runOnIdle { assertTrue(shell.display.bracketedPaste) }
        command("bracket-check"); waitText(shell, "BRACKET-OK")
        command("vt-query"); waitText(shell, "QUERY-OK")
        command("vt-alt"); waitText(shell, "ALTERNATE")
        compose.runOnIdle { assertEquals("alternate", shell.display.activeScreen) }
        command("vt-primary"); waitText(shell, "Green λ 中")
        compose.runOnIdle { assertEquals("primary", shell.display.activeScreen) }
        command("Hello λ from Android"); waitText(shell, "ECHO Hello λ from Android")
        assertNull(runBlocking { shell.currentDirectory() })
        command("vt-cwd"); waitText(shell, "CWD-REPORTED")
        assertEquals("/Shell files λ +%?#", runBlocking { shell.currentDirectory() })
        // Files is a sheet over the live terminal: opening/closing it must
        // preserve unsent composer text and the exact PTY.
        compose.onNodeWithTag("ssh.shell.composer").performTextReplacement("unsent files draft λ")
        compose.onNodeWithTag("ssh.shell.files").performClick()
        compose.waitUntil(15000) { compose.onAllNodes(hasTestTag("ssh.files.refresh") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("/Shell files λ +%?#").assertIsDisplayed()
        compose.onNodeWithTag("ssh.files.row.cwd-marker.txt").assertIsDisplayed()
        capture("ssh-shell-current-folder")
        compose.onNodeWithTag("ssh.files.actions.'a'.txt").performClick()
        compose.onNodeWithText("Insert Path in Terminal").performClick()
        compose.onNodeWithTag("ssh.shell.composer").assertTextContains("unsent files draft λ")
        assertSame(shell, session.shells.state.value.single())
        val inserted = "'/Shell files λ +%?#/'\\''a'\\''.txt'"
        assertFalse(text(shell).contains("ECHO $inserted"))
        // Insertion does not press Enter or send the unsent composer draft.
        compose.runOnIdle { assertTrue(shell.send("\r")) }
        waitText(shell, "ECHO $inserted")
        compose.onNodeWithTag("ssh.shell.composer").performTextClearance()
        capture("ssh-shell-primary")
        compose.onNodeWithTag("ssh.shell.text").performClick()
        compose.onNodeWithText("Terminal Text").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("ssh.shell.${shell.id}.open").performScrollTo().performClick()
        assertSame(shell, session.shells.state.value.single())
        command("still same shell"); waitText(shell, "ECHO still same shell")
        val connection = runBlocking { session.connections.open(host.id) }
        assertEquals("1", runBlocking { connection.exec("shell-count").stdout.toString(Charsets.UTF_8).trim() })
        compose.runOnIdle { shell.resize(72, 17, TerminalCellMetrics(10f, 20f, 14f)) }
        waitText(shell, "SIZE 72 17")
    }
    @Test fun closingShellReleasesRemoteChannelAndRetiringAccountRejectsInput() {
        val shell = open()
        val connection = runBlocking { session.connections.open(host.id) }
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5000) { session.shells.state.value.isEmpty() }
        runBlocking {
            withTimeout(5000) {
                while (true) {
                    val count = connection.exec("shell-count").stdout.toString(Charsets.UTF_8).trim()
                    if (count == "0") break
                    delay(50)
                }
            }
        }
        compose.runOnIdle { assertFalse(shell.send("do not deliver\n")) }
        compose.onNodeWithTag("ssh.host.${host.id}.shell").performClick()
        compose.waitUntil(10000) { session.shells.state.value.singleOrNull()?.state?.value?.phase == SshShellPhase.RUNNING }
        val second = session.shells.state.value.single()
        compose.runOnIdle { lifetime.cancel(); assertFalse(second.send("do not deliver\n")) }
        compose.waitUntil(5000) { session.shells.state.value.isEmpty() && !connection.isConnected }
    }
    @Test fun plainShellBrowserPreservesDraftAndBothMenusCreateSelectedWorkspaces() {
        val port = InstrumentationRegistry.getArguments().getString("cmux_ssh_browserport")!!.toInt()
        val shell = open()
        command("before browser λ"); waitText(shell, "ECHO before browser λ")
        compose.onNodeWithTag("ssh.shell.composer").performTextReplacement("unsent browser draft λ")
        val network = compose.runOnIdle { session.browsers.network(host.id) }
        val workspace = sshBrowserWorkspace(SshWorkspaceTarget.Shell(shell.id), shell.title)
        compose.runOnIdle {
            network.navigation.restoreRemembered(sshLocalBrowserKey(network, workspace), workspace)
            network.navigation.state.value.local!!.surface.load("http://localhost:$port/page")
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        fun uiText(value: String) = checkNotNull(device.wait(Until.findObject(By.text(value)), 15000)) { "Missing $value" }
        fun browser() {
            compose.onNodeWithTag("ssh.shell.menu").performClick()
            compose.onNodeWithText("New Browser").performClick()
            compose.waitUntil(15000) { !compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        }
        fun selected(current: SshShell) {
            compose.waitUntil(15000) { compose.activity.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED) }
            compose.waitUntil(10000) {
                compose.onAllNodesWithTag("ssh.shell.identity.${current.id}").fetchSemanticsNodes().isNotEmpty() &&
                    current.state.value.phase == SshShellPhase.RUNNING
            }
        }
        browser()
        uiText("SSH routed fixture ▾"); uiText("SSH route verified")
        uiText("Next SSH page").click(); uiText("SSH next ▾")
        val remembered = runBlocking { withContext(Dispatchers.Main) { network.navigation.state.value.local!!.surface } }
        checkNotNull(device.wait(Until.findObject(By.desc("Back to workspaces")), 15000)).click()
        selected(shell)
        compose.onNodeWithTag("ssh.shell.composer").assertTextContains("unsent browser draft λ")
        assertFalse(remembered.state.value.closed)
        browser(); uiText("SSH next ▾").click()
        uiText("Terminals"); uiText("New Workspace"); uiText("New Browser")
        assertFalse(device.hasObject(By.text("New Tab")))
        assertFalse(device.hasObject(By.text("New Window")))
        assertFalse(device.hasObject(By.text("New Screen")))
        assertTrue(device.takeScreenshot(File(compose.activity.getExternalFilesDir(null), "ssh-plain-browser-picker.png")))
        uiText(shell.title).click()
        selected(shell)
        compose.onNodeWithTag("ssh.shell.composer").assertTextContains("unsent browser draft λ")
        assertSame(shell, session.shells.state.value.single())
        assertFalse(text(shell).contains("ECHO unsent browser draft"))
        // Explicit pane selection closes the page, unlike Back. Both preserve
        // the exact PTY and unsent draft. Seed the next independent browser.
        assertTrue(remembered.state.value.closed)
        compose.runOnIdle {
            network.navigation.restoreRemembered(sshLocalBrowserKey(network, workspace), workspace)
            network.navigation.state.value.local!!.surface.load("http://localhost:$port/page")
        }
        browser(); uiText("SSH routed fixture ▾").click(); uiText("New Workspace").click()
        compose.waitUntil(10000) { session.shells.state.value.size == 2 }
        val second = session.shells.state.value.single { it !== shell }
        selected(second)
        command("browser-created shell"); waitText(second, "ECHO browser-created shell")
        assertFalse(text(shell).contains("browser-created shell"))
        compose.onNodeWithTag("ssh.shell.menu").performClick()
        compose.onNodeWithText("New Workspace").performClick()
        compose.waitUntil(10000) { session.shells.state.value.size == 3 }
        val third = session.shells.state.value.single { it !== shell && it !== second }
        selected(third)
        command("terminal-created shell"); waitText(third, "ECHO terminal-created shell")
        assertFalse(text(second).contains("terminal-created shell"))
        assertTrue(session.shells.state.value.all { it.hostId == host.id && it.state.value.phase == SshShellPhase.RUNNING })
        val connection = runBlocking { session.connections.open(host.id) }
        assertEquals("3", runBlocking { connection.exec("shell-count").stdout.toString(Charsets.UTF_8).trim() })
        capture("ssh-plain-created-workspace")
    }
    @Test fun endedShellKeepsItsScreenOnFailedRetryAndReconnectStartsFreshPty() {
        val old = open()
        command("old shell marker"); waitText(old, "ECHO old shell marker")
        command("vt-cwd"); waitText(old, "CWD-REPORTED")
        assertEquals("/Shell files λ +%?#", runBlocking { old.currentDirectory() })
        command("vt-exit")
        compose.waitUntil(10000) { old.state.value.phase == SshShellPhase.ENDED }
        assertNull(runBlocking { old.currentDirectory() })
        compose.onNodeWithTag("ssh.shell.reconnect").assertIsDisplayed()
        // A failed dial answers on the terminal being viewed and does not erase
        // its screen or consume a second shell slot.
        compose.runOnIdle { session.hosts.upsert(host.copy(keyId = null)) }
        compose.onNodeWithTag("ssh.shell.reconnect").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("Choose an available SSH key").fetchSemanticsNodes().isNotEmpty() }
        assertSame(old, session.shells.state.value.single())
        assertTrue(text(old).contains("old shell marker"))
        compose.runOnIdle { session.hosts.upsert(host) }
        compose.onNodeWithTag("ssh.shell.reconnect").performClick()
        compose.waitUntil(10000) { session.shells.state.value.singleOrNull()?.let { it !== old && it.state.value.phase == SshShellPhase.RUNNING } == true }
        val fresh = session.shells.state.value.single()
        assertNotEquals(old.id, fresh.id)
        assertFalse(text(fresh).contains("old shell marker"))
        assertNull(runBlocking { fresh.currentDirectory() })
        command("fresh shell marker"); waitText(fresh, "ECHO fresh shell marker")
        val connection = runBlocking { session.connections.open(host.id) }
        assertEquals("1", runBlocking { connection.exec("shell-count").stdout.toString(Charsets.UTF_8).trim() })
        compose.runOnIdle { assertFalse(old.send("must not replay")) }
        capture("ssh-shell-reconnected")
    }
}
