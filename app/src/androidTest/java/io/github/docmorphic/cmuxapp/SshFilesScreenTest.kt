package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import java.io.File
import java.util.UUID

/** Isolated loopback SFTP; runner refuses physical devices. */
class SshFilesScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var session: NativeSshSession
    private lateinit var lifetime: CoroutineScope
    private lateinit var root: File
    private lateinit var hostId: UUID
    private lateinit var remote: SshFiles
    private lateinit var connection: SshTransport
    private lateinit var directory: String
    @Before fun setup() {
        val args = InstrumentationRegistry.getArguments()
        assumeTrue(args.containsKey("cmux_ssh_port") && args.getString("cmux_ssh_nonce") != "cmux")
        root = File(compose.activity.noBackupFilesDir, "ssh-files-test-${UUID.randomUUID()}")
        var metadata: String? = null
        val hosts = SshHostStore({ metadata }, { metadata = it })
        val vault = SshKeyVault(root, { true }, hosts::removeKeyReferences)
        val key = vault.generate("Files fixture")
        val host = SshHostRecord(name = "Files fixture", keyId = key.id,
            endpoint = SshEndpoint("127.0.0.1", args.getString("cmux_ssh_port")!!.toInt(), args.getString("cmux_ssh_user")!!))
        hostId = host.id; hosts.upsert(host)
        assertTrue(hosts.confirmHostKey(hosts.dialPlan(hostId), hostId, hosts.trustSnapshot(host.endpoint), SshHostKey.parse(args.getString("cmux_ssh_hostkey")!!)))
        lifetime = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        session = NativeSshSession(hosts, vault, lifetime) { lifetime.isActive }
        connection = runBlocking { session.connections.open(hostId) }
        remote = SshFiles { check(session.isOpen); checkNotNull(session.connections.autoConnect(hostId)) }
        directory = "/files-${UUID.randomUUID()}"
        runBlocking { remote.mkdir("/", directory.drop(1)) }
    }
    @After fun cleanup() {
        if (::session.isInitialized) { compose.runOnIdle { session.close(); lifetime.cancel() }; session.vault.state.value.toList().forEach { session.vault.delete(it.id) } }
        if (::root.isInitialized) root.deleteRecursively()
    }
    private fun ready(tag: String) { compose.waitUntil(15000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
    private fun shown(text: String) { compose.waitUntil(15000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() } }
    private fun show() {
        val terminal = object : SshTerminal {
            override val id = "files-test"; override val title = "Files test"
            override val state = MutableStateFlow(SshShellState(SshShellPhase.RUNNING))
            override val display: GhosttyVtTerminal get() = error("Files must not inspect terminal pixels")
            override fun send(text: String, paste: Boolean) = false
            override fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {}
            override fun close() {}
            override suspend fun currentDirectory() = directory
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) { SshFilesScreen(session, hostId, terminal) {} } } }
        ready("ssh.files.refresh")
    }
    @Test fun literalTransfersRenameLinksAndNonemptyDeleteUseRealSftp() = runBlocking {
        val name = "a *?\\ 中.txt"; val bytes = ByteArray(170_013) { (it % 239).toByte() }
        val progress = mutableListOf<Long>()
        assertEquals(name, remote.upload(directory, name, bytes.size.toLong(), { bytes.inputStream() }) { count, _ -> progress += count })
        assertEquals(bytes.size.toLong(), progress.last())
        assertEquals("a *?\\ 中 2.txt", remote.upload(directory, name, bytes.size.toLong(), { bytes.inputStream() }) { _, _ -> })
        val downloaded = File(root, "download/result")
        assertEquals(bytes.size.toLong(), remote.download("$directory/$name", downloaded) { _, _ -> })
        assertArrayEquals(bytes, downloaded.readBytes())
        remote.mkdir(directory, "folder *?\\")
        val entry = remote.list(directory).first { it.name == name }
        assertTrue(runCatching { remote.rename(directory, entry, "a *?\\ 中 2.txt") }.isFailure)
        remote.rename(directory, entry, "renamed *?.txt")
        assertTrue(remote.list(directory).any { it.name == "renamed *?.txt" })
        assertEquals(0, connection.exec("files-fixture-link").exitStatus)
        assertTrue(remote.directory("/files-link"))
        remote.delete("/", remote.list("/").single { it.name == "files-link" })
        assertTrue(remote.directory("/files-link-target *?\\"))
        remote.upload("$directory/folder *?\\", "inside", 1, { byteArrayOf(1).inputStream() }) { _, _ -> }
        assertTrue(runCatching { remote.delete(directory, remote.list(directory).single { it.directory }) }.isFailure)
        assertTrue(remote.list(directory).none { it.name.startsWith(".cmux-upload-") })
        assertEquals(listOf("/", directory, "$directory/folder *?\\"), remote.start("$directory/folder *?\\"))
    }
    @Test fun folderActionsAndPreviewAreUsableFromTheBrowser() {
        runBlocking { remote.upload(directory, "hello.txt", 18, { "Files fixture λ 中".byteInputStream() }) { _, _ -> } }
        show(); ready("ssh.files.row.hello.txt")
        compose.onNodeWithTag("ssh.files.row.hello.txt").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithContentDescription("Raw text preview").fetchSemanticsNodes().isNotEmpty() }
        onView(withText("Files fixture λ 中")).check(matches(isDisplayed()))
        capture("ssh-files-preview")
        compose.onNodeWithText("Back").performClick(); ready("ssh.files.new-folder")
        compose.onNodeWithTag("ssh.files.new-folder").performClick()
        compose.onNodeWithTag("ssh.files.name").performTextInput("New λ folder")
        compose.onNodeWithText("Create").performClick(); ready("ssh.files.row.New λ folder")
        compose.onNodeWithTag("ssh.files.actions.New λ folder").performClick()
        compose.onNodeWithText("Rename…").performClick()
        compose.onNodeWithTag("ssh.files.name").performTextReplacement("Renamed 中")
        compose.onNode(hasText("Rename") and hasClickAction()).performClick(); ready("ssh.files.row.Renamed 中")
        compose.onNodeWithTag("ssh.files.actions.Renamed 中").performClick(); compose.onNodeWithText("Delete…").performClick()
        compose.onNodeWithText("Cancel").performClick(); ready("ssh.files.row.Renamed 中")
        compose.onNodeWithTag("ssh.files.actions.Renamed 中").performClick(); compose.onNodeWithText("Delete…").performClick()
        compose.onNodeWithText("Delete").performClick()
        compose.waitUntil(15000) { compose.onAllNodesWithTag("ssh.files.row.Renamed 中").fetchSemanticsNodes().isEmpty() }
        assertEquals(listOf("hello.txt"), runBlocking { remote.list(directory) }.map { it.name })
        capture("ssh-files-browser")
    }
    private fun capture(name: String) {
        val ui = InstrumentationRegistry.getInstrumentation().uiAutomation
        ui.waitForIdle(100, 3000)
        val bitmap = ui.takeScreenshot()
        File(compose.activity.getExternalFilesDir(null), "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun retiredAccountCannotReopenFileTransport() {
        show(); compose.runOnIdle { session.close() }
        compose.onNodeWithTag("ssh.files.refresh").performClick()
        shown("This account session ended")
        assertFalse(connection.isConnected)
    }
}
