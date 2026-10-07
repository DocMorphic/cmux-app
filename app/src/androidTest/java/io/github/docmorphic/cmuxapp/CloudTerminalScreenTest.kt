package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicLong

/** Real Ghostty + common terminal UI; injected daemon link, no account or VM request. */
class CloudTerminalScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var host: CloudTerminalHost? = null
    private class Link : CloudTerminalLink {
        private val counter = AtomicLong()
        private val events = ConcurrentHashMap<Long, Channel<CloudTerminalOutput>>()
        val input = CopyOnWriteArrayList<Pair<Long, String>>()
        var beforeSend: (String) -> Unit = {}
        @Volatile var attachFails = false
        @Volatile var live = 0L
        override fun attach(terminal: String): Long {
            if (attachFails) throw java.io.IOException("fixture attach failure")
            val token = counter.incrementAndGet()
            events[token] = Channel(Channel.UNLIMITED)
            live = token
            events.getValue(token).trySend(CloudTerminalOutput(1, "\u001b[34mCloud $terminal 日本語\u001b[0m\r\n$ ".toByteArray(), 80, 24))
            return token
        }
        override fun detach(attachment: Long) { if (live == attachment) live = 0 }
        override fun send(attachment: Long, bytes: ByteArray): Boolean {
            if (live != attachment) return false
            val text = bytes.toString(Charsets.UTF_8)
            beforeSend(text); input += attachment to text; return true
        }
        override fun resize(attachment: Long, columns: Int, rows: Int) = if (live == attachment) 1L else 0L
        override suspend fun output(attachment: Long) = events.getValue(attachment).receive()
    }
    @After fun stop() { compose.runOnIdle { host?.close(); lifetime.cancel() } }
    @Test fun failedAttachmentKeepsComposerUsableAndNewSubmissionReconnectsWithoutExplicitRetry() {
        val link = Link().apply { attachFails = true }
        val machine = CloudMachine("fixture", "fixture", "running", "Cloud fixture", null, null)
        val catalog = CloudWorkspaceCatalog(listOf(CloudWorkspaceSummary("ws_a", "Workspace")),
            listOf(CloudTerminalSummary("term_a", "First", "ws_a")))
        val snapshot = CloudWorkspaceSnapshot(machine, catalog, NativeFeedAvailability.CONNECTED, true)
        val row = snapshot.rows.single()
        lateinit var terminal: CloudRenderedTerminal
        compose.runOnUiThread {
            host = CloudTerminalHost(lifetime, machine.id, { true }, { link }).also {
                it.reconcile(snapshot, true, true)
                terminal = it.select(row.workspace, row.workspace.terminals.single())
            }
        }
        compose.setContent { CmuxTheme { Surface { SshShellScreen(terminal, onReconnect = {
            fail("Fresh input must not require the explicit Reconnect action")
        }, onBack = {}) } } }
        compose.waitUntil(5000) { terminal.state.value.phase == SshShellPhase.ENDED }
        compose.onNodeWithText("Could not reach this machine's terminal service.").assertIsDisplayed()
        compose.onNodeWithTag("ssh.shell.reconnect").assertIsDisplayed()
        compose.onNodeWithTag("ssh.shell.composer").assertIsEnabled().performTextInput("new command")
        compose.runOnIdle { link.attachFails = false }
        compose.onNodeWithTag("ssh.shell.send").assertIsEnabled().performClick()
        compose.waitUntil(5000) { terminal.state.value.phase == SshShellPhase.RUNNING && link.input.any { it.second == "new command\r" } }
        assertEquals(1, link.input.count { it.second == "new command\r" })
        compose.onNodeWithText("Could not reach this machine's terminal service.").assertDoesNotExist()
        compose.runOnIdle {
            host!!.reconcile(snapshot, false, false)
            assertFalse(terminal.retriesAttachmentOnInput)
            assertFalse(terminal.send("background"))
        }
    }
    @Test fun explicitReconnectWaitsForNewVerifiedCatalogAndKeepsTheSelectedDisplay() {
        val link = Link()
        val machine = CloudMachine("fixture", "fixture", "running", "Cloud fixture", null, null)
        val catalog = CloudWorkspaceCatalog(listOf(CloudWorkspaceSummary("ws_a", "Workspace")),
            listOf(CloudTerminalSummary("term_a", "First", "ws_a")))
        val snapshot = CloudWorkspaceSnapshot(machine, catalog, NativeFeedAvailability.CONNECTED, true, catalogRevision = 10)
        val row = snapshot.rows.single()
        lateinit var terminal: CloudRenderedTerminal
        compose.runOnUiThread {
            host = CloudTerminalHost(lifetime, machine.id, { true }, { link }).also {
                it.reconcile(snapshot, true, true)
                terminal = it.select(row.workspace, row.workspace.terminals.single())
            }
        }
        compose.setContent { CmuxTheme { Surface { SshShellScreen(terminal, onBack = {}) } } }
        compose.waitUntil(5000) { terminal.state.value.phase == SshShellPhase.RUNNING }
        val originalToken = link.live
        compose.runOnIdle {
            host!!.awaitFreshCatalog(snapshot.catalogRevision)
            assertEquals(SshShellPhase.OPENING, terminal.state.value.phase)
            assertSame(terminal, host!!.selected.value)
            // A stale successful catalog and a newer unverified one cannot reopen the link.
            host!!.reconcile(snapshot, true, true)
            host!!.reconcile(snapshot.copy(catalogRevision = 11, authoritative = false), true, true)
            assertTrue(terminal.send("typed during reconnect"))
        }
        compose.waitUntil(5000) { link.live == 0L }
        assertTrue(link.input.isEmpty())
        compose.runOnIdle { host!!.reconcile(snapshot.copy(catalogRevision = 12), true, true) }
        compose.waitUntil(5000) { link.input.any { it.second == "typed during reconnect" } }
        assertEquals(originalToken + 1, link.live)
        assertEquals(1, link.input.count { it.second == "typed during reconnect" })
        compose.runOnIdle {
            assertSame(terminal, host!!.selected.value)
            terminal.connection(CloudAttachmentState("term_a", CloudAttachmentPhase.FAILED,
                failure = CloudSessionFailure("private-native-diagnostic", kind = CloudFailureKind.LINK)))
        }
        compose.onNodeWithText("Could not reach this machine's terminal service.").assertIsDisplayed()
        compose.onNodeWithText("private-native-diagnostic").assertDoesNotExist()
    }
    @Test fun commonRendererComposerAndTerminalSwitchUseTheCloudSlot() {
        val link = Link()
        val machine = CloudMachine("fixture", "fixture", "running", "Cloud fixture", null, null)
        val catalog = CloudWorkspaceCatalog(listOf(CloudWorkspaceSummary("ws_a", "Workspace")),
            listOf(CloudTerminalSummary("term_a", "First", "ws_a"), CloudTerminalSummary("term_b", "Second", "ws_a")))
        val snapshot = CloudWorkspaceSnapshot(machine, catalog, NativeFeedAvailability.CONNECTED, true)
        val row = snapshot.rows.single()
        val drafts = TerminalDrafts()
        val saved = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val draftOwner = NativeTeamScope("fixture-login", "fixture-user", "fixture-team", 1)
        link.beforeSend = { text ->
            val persisted = org.json.JSONArray(checkNotNull(saved.get()) { "Input preceded draft persistence" })
            check((0 until persisted.length()).any { index ->
                val draft = persisted.getJSONObject(index)
                draft.getBoolean("delivery_unconfirmed") && text.contains(draft.getString("text")) && draft.getString("text").isNotEmpty()
            }) { "No pending draft was persisted before sending" }
        }
        lateinit var first: CloudRenderedTerminal
        compose.runOnUiThread {
            host = CloudTerminalHost(lifetime, machine.id, { true }, { link },
                SshComposerPool(drafts, { cloudDraftTarget(draftOwner, machine.id, it) }, { saved.set(drafts.saved().toString()) })).also {
                it.reconcile(snapshot, true, true)
                first = it.select(row.workspace, row.workspace.terminals.first())
            }
        }
        compose.setContent { CmuxTheme { Surface {
            val selected by host!!.selected.collectAsState()
            selected?.let { terminal -> key(terminal) { SshShellScreen(terminal, onBack = {}) } }
        } } }
        compose.waitUntil(5000) { compose.runOnIdle { TerminalTextSnapshot.capture(first.display).text.contains("Cloud term_a 日本語") } }
        captureCloudScreen("cloud-terminal-first")
        compose.onNodeWithTag("ssh.shell.composer").performTextInput("printf cloud")
        compose.onNodeWithTag("ssh.shell.send").performClick()
        compose.waitUntil(5000) { link.input.any { it.second.contains("printf cloud") && it.second.endsWith("\r") } }
        lateinit var second: CloudRenderedTerminal
        compose.runOnIdle {
            second = host!!.select(row.workspace, row.workspace.terminals.last())
            assertEquals(SshShellPhase.ENDED, first.state.value.phase)
            assertFalse(SshTerminalInteraction(first).focus(false))
            assertFalse(first.send("must-not-send"))
        }
        compose.waitUntil(5000) { compose.runOnIdle { TerminalTextSnapshot.capture(second.display).text.contains("Cloud term_b 日本語") } }
        compose.onNodeWithTag("ssh.shell.composer").performTextInput("second command")
        compose.onNodeWithTag("ssh.shell.send").performClick()
        compose.waitUntil(5000) { link.input.any { it.second.contains("second command") } }
        assertFalse(link.input.any { it.second.contains("must-not-send") })
        assertNotEquals(link.input.first { it.second.contains("printf cloud") }.first, link.input.first { it.second.contains("second command") }.first)
        compose.runOnIdle {
            host!!.reconcile(snapshot.copy(authoritative = false, availability = NativeFeedAvailability.CONNECTING), false, false)
            assertFalse(second.send("background"))
        }
    }
}
