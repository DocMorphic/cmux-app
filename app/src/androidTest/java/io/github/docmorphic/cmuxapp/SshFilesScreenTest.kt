package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
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
import java.io.ByteArrayInputStream
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import org.json.JSONObject

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
        val visible = mutableStateOf(true)
        val terminal = object : SshTerminal {
            override val id = "files-test"; override val title = "Files test"
            override val state = MutableStateFlow(SshShellState(SshShellPhase.RUNNING))
            override val display: GhosttyVtTerminal get() = error("Files must not inspect terminal pixels")
            override fun send(text: String, paste: Boolean) = false
            override fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {}
            override fun close() {}
            override suspend fun currentDirectory() = directory
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            if (visible.value) SshFilesScreen(session, hostId, terminal) { visible.value = false }
            else Text("Files closed")
        } } }
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
        val content = "Files fixture λ 中".toByteArray()
        runBlocking { remote.upload(directory, "hello.txt", content.size.toLong(), { content.inputStream() }) { _, _ -> } }
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
    private suspend fun waitForTransfer(kind: String) = withTimeout(10000) {
        while (JSONObject(connection.exec("files-transfer-status").stdout.toString(Charsets.UTF_8)).getInt(kind) == 0) delay(30)
    }
    @Test fun closingBrowserCancelsDownloadAndEventuallyRemovesScratchFiles() {
        val bytes = ByteArray(256 * 1024) { 42 }
        runBlocking { remote.upload(directory, "slow-download.bin", bytes.size.toLong(), { bytes.inputStream() }) { _, _ -> } }
        val scratch = File(compose.activity.cacheDir, "ssh-files")
        val existing = scratch.listFiles()?.map { it.name }?.toSet().orEmpty()
        show()
        runBlocking { assertEquals(0, connection.exec("files-transfer-arm").exitStatus) }
        try {
            compose.onNodeWithTag("ssh.files.row.slow-download.bin").performClick()
            runBlocking { waitForTransfer("reads") }
            assertTrue(scratch.listFiles().orEmpty().any { it.name !in existing })
            compose.onNodeWithText("Done").performClick()
            compose.onNodeWithText("Files closed").assertIsDisplayed()
        } finally { runBlocking { connection.exec("files-transfer-release") } }
        runBlocking { withTimeout(10000) {
            while (scratch.listFiles().orEmpty().any { it.name !in existing }) delay(30)
        } }
        assertTrue(connection.isConnected)
        assertEquals(listOf("slow-download.bin"), runBlocking { remote.list(directory) }.map { it.name })
    }
    @Test fun cancelledUploadDoesNotPublishOrBreakTheSharedConnection() = runBlocking {
        remote.mkdir(directory, "slow-upload")
        val folder = "$directory/slow-upload"
        val closed = AtomicBoolean(false)
        val source = object : ByteArrayInputStream(ByteArray(512 * 1024) { 17 }) {
            override fun close() { closed.set(true); super.close() }
        }
        connection.exec("files-transfer-arm")
        val pending = async(Dispatchers.IO) { remote.upload(folder, "never-published.bin", 512L * 1024, { source }) { _, _ -> } }
        try {
            waitForTransfer("writes")
            withTimeout(3000) { pending.cancelAndJoin() }
            assertTrue(pending.isCancelled)
        } finally { connection.exec("files-transfer-release") }
        withTimeout(10000) { while (!closed.get()) delay(30) }
        assertTrue(connection.isConnected)
        val entries = remote.list(folder)
        assertTrue(entries.none { it.name == "never-published.bin" })
        // Cancellation closes the transfer channel. If its best-effort remove
        // loses that race, only the unique staging file may remain, never the
        // requested filename. Remove this private fixture's residue explicitly.
        assertTrue(entries.all { it.name.startsWith(".cmux-upload-") })
        entries.forEach { remote.delete(folder, it) }
        assertEquals("after.txt", remote.upload(folder, "after.txt", 2, { byteArrayOf(1, 2).inputStream() }) { _, _ -> })
    }
    @Test fun failedOrTruncatedSourceClosesAndDoesNotPublishPartialFile() = runBlocking {
        val closed = AtomicBoolean(false)
        val broken = object : ByteArrayInputStream(ByteArray(100000)) {
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                if (pos > 0) throw IOException("Fixture source disappeared")
                return super.read(bytes, offset, length)
            }
            override fun close() { closed.set(true); super.close() }
        }
        assertTrue(runCatching { remote.upload(directory, "failed.bin", 100000, { broken }) { _, _ -> } }.isFailure)
        assertTrue(closed.get())
        for (expected in listOf(1L, 3L)) {
            assertTrue(runCatching { remote.upload(directory, "changed.bin", expected, { byteArrayOf(1, 2).inputStream() }) { _, _ -> } }.isFailure)
        }
        assertTrue(remote.list(directory).isEmpty())
        assertEquals("unknown.bin", remote.upload(directory, "unknown.bin", null, { byteArrayOf(1, 2).inputStream() }) { _, _ -> })
        assertEquals(2L, remote.list(directory).single().size)
    }
}
