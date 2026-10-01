package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** The loopback fixture is generated for this run; no account storage is touched. */
class SshComputersScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var root: File
    private lateinit var session: NativeSshSession
    private lateinit var owner: CoroutineScope
    private lateinit var endpoint: SshEndpoint
    private lateinit var expected: SshHostKey
    @Before fun setup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("cmux_ssh_port"))
        root = File(compose.activity.noBackupFilesDir, "ssh-host-ui-${UUID.randomUUID()}")
        val hosts = SshHostStore({ null }, {})
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        vault.generate("Host UI fixture")
        owner = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        session = NativeSshSession(hosts, vault, owner, { owner.isActive })
        endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!)
        expected = SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)
    }
    @After fun cleanup() {
        if (::session.isInitialized) {
            session.close(); owner.cancel()
            session.vault.state.value.toList().forEach { session.vault.delete(it.id) }
        }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private fun show() = compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
        SshComputersScreen(session, {})
        SshPromptHost(session)
    } } }
    private fun saved(): SshHostRecord = SshHostRecord(name = "Loopback computer", endpoint = endpoint,
        keyId = session.vault.state.value.single().id).also { session.hosts.upsert(it) }
    private fun waitPrompt() = compose.waitUntil(10000) { compose.onAllNodesWithTag("ssh.trust").fetchSemanticsNodes().size == 1 }
    private fun waitConnected(id: UUID) = compose.waitUntil(10000) { session.connections.statuses.value[id]?.phase == SshConnectionPhase.CONNECTED }
    private fun capture(name: String) {
        compose.waitForIdle()
        // Compose idleness does not wait for the platform dialog/IME animation.
        // Settle that window transition before retaining visual evidence.
        InstrumentationRegistry.getInstrumentation().uiAutomation.waitForIdle(100, 3000)
        Thread.sleep(400)
        val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use {
            screenshot.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }
    @Test fun restoredEditorRejectsAChangedRouteWithoutOverwritingIt() {
        val host = saved()
        val restoration = StateRestorationTester(compose)
        restoration.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
            SshComputersScreen(session, {})
            SshPromptHost(session)
        } } }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("ssh.host.name").performTextReplacement("Stale draft")
        val newer = host.copy(endpoint = endpoint.copy(host = "localhost"))
        session.hosts.upsert(newer)
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("ssh.host.name").assertTextContains("Stale draft")
        compose.onNodeWithTag("ssh.host.save").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText(SshHostEditConflict().message!!).fetchSemanticsNodes().size == 1 }
        assertEquals(newer, session.hosts.state.value.host(host.id))
        assertTrue(session.connections.statuses.value.isEmpty())
    }

    @Test fun addValidatesAddressDefaultsNameAndConnectsWithVisibleTrust() {
        show(); compose.onNodeWithTag("ssh.computers.add").performClick()
        compose.onNodeWithTag("ssh.host.save").assertIsNotEnabled()
        compose.onNodeWithTag("ssh.host.address").performTextInput("[${endpoint.host}]")
        compose.onNodeWithTag("ssh.host.port").performTextReplacement(endpoint.port.toString())
        compose.onNodeWithTag("ssh.host.username").performTextInput(endpoint.username)
        compose.onNodeWithTag("ssh.host.username").assertTextContains(endpoint.username)
        compose.onNodeWithTag("ssh.host.save").assertIsEnabled()
        capture("ssh-host-editor")
        compose.onNodeWithTag("ssh.host.save").performClick()
        waitPrompt(); assertEquals(endpoint.host, session.hosts.state.value.hosts.single().name)
        compose.onNodeWithText("Verify SSH computer").assertIsDisplayed()
        compose.onNodeWithText("Presented: ${expected.algorithm}\n${expected.sha256Fingerprint}").assertExists()
        capture("ssh-host-trust")
        compose.onNodeWithTag("ssh.trust.accept").performClick()
        val host = session.hosts.state.value.hosts.single(); waitConnected(host.id)
        assertEquals(expected, session.hosts.trustSnapshot(endpoint).pinned)
        capture("ssh-host-connected")
        compose.onNodeWithText("Disconnect").performClick()
        compose.waitUntil(5000) { session.hosts.state.value.host(host.id)!!.autoConnectPaused }
        assertTrue(session.connections.prompts.value.isEmpty())
    }
    @Test fun changedIdentityCancellationPreservesPinAndExplicitRetryCanReplace() {
        val host = saved()
        val prior = session.vault.state.value.single().publicKey
        assertTrue(session.hosts.confirmHostKey(session.hosts.dialPlan(host.id), host.id, session.hosts.trustSnapshot(endpoint), prior))
        show(); compose.onNodeWithTag("ssh.host.${host.id}.connect").performClick(); waitPrompt()
        compose.onNodeWithText("SSH host key changed").assertIsDisplayed()
        capture("ssh-host-changed-key")
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5000) { session.hosts.state.value.host(host.id)!!.autoConnectPaused }
        assertEquals(prior, session.hosts.trustSnapshot(endpoint).pinned)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("ssh.host.${host.id}.connect").fetchSemanticsNodes().size == 1 }
        compose.onNodeWithTag("ssh.host.${host.id}.connect").performClick(); waitPrompt()
        compose.onNodeWithTag("ssh.trust.accept").performClick(); waitConnected(host.id)
        assertEquals(expected, session.hosts.trustSnapshot(endpoint).pinned)
        assertFalse(session.hosts.state.value.host(host.id)!!.autoConnectPaused)
    }
    @Test fun editSurvivesKeyScreenAndLabelChangeKeepsConnectionUntilConfirmedDelete() {
        val host = saved()
        assertTrue(session.hosts.confirmHostKey(session.hosts.dialPlan(host.id), host.id, session.hosts.trustSnapshot(endpoint), expected))
        show(); compose.onNodeWithTag("ssh.host.${host.id}.connect").performClick(); waitConnected(host.id)
        val originalConnection = runBlocking { session.connections.open(host.id) }
        compose.onNodeWithText("Edit").performClick()
        compose.onNodeWithTag("ssh.host.name").performTextReplacement("Renamed computer")
        compose.onNodeWithText("Manage SSH Keys").performScrollTo().performClick()
        compose.onNodeWithTag("ssh.keys.back").performClick()
        compose.onNodeWithTag("ssh.host.name").assertTextContains("Renamed computer")
        compose.onNodeWithTag("ssh.host.save").performScrollTo().performClick()
        compose.waitUntil(5000) { session.hosts.state.value.host(host.id)?.name == "Renamed computer" }
        assertSame(originalConnection, runBlocking { session.connections.open(host.id) })
        compose.onNodeWithText("Delete").performClick(); compose.onNodeWithText("Cancel").performClick()
        assertTrue(originalConnection.isConnected)
        compose.onNodeWithText("Delete").performClick()
        compose.onAllNodesWithText("Delete").onLast().performClick()
        compose.waitUntil(5000) { session.hosts.state.value.host(host.id) == null && !originalConnection.isConnected }
    }
}
