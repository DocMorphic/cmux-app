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
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.graphics.Bitmap
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.io.ByteArrayOutputStream
import java.util.regex.Pattern

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
    private fun ready(tag: String) {
        try { compose.waitUntil(15000) { compose.onAllNodes(hasTestTag(tag) and isEnabled()).fetchSemanticsNodes().isNotEmpty() } }
        catch (failure: Throwable) {
            capture("ssh-files-ready-failure")
            device.dumpWindowHierarchy(File(compose.activity.getExternalFilesDir(null), "ssh-files-ready-failure.xml"))
            android.util.Log.e("TestRunner", "Files wait failed: $tag\n" + compose.onRoot().printToString())
            throw failure
        }
    }
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
        compose.onNodeWithText("Back").performClick(); ready("ssh.files.add"); compose.onNodeWithTag("ssh.files.add").performClick()
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
    private val device get() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private fun media(name: String, mime: String, bytes: ByteArray, image: Boolean = false): Uri {
        val resolver = compose.activity.contentResolver
        val uri = checkNotNull(resolver.insert(if (image) MediaStore.Images.Media.EXTERNAL_CONTENT_URI else MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, if (image) "Pictures/" else "Download/")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
                if (image) put(MediaStore.Images.ImageColumns.DATE_TAKEN, System.currentTimeMillis())
            }))
        try {
            resolver.openOutputStream(uri)!!.use { it.write(bytes) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
            return uri
        } catch (failure: Throwable) { resolver.delete(uri, null, null); throw failure }
    }
    private fun picker(photos: Boolean = false) {
        compose.onNodeWithTag("ssh.files.add").performClick()
        compose.onNodeWithText(if (photos) "Upload from Photos…" else "Upload from Files…").performClick()
        val packages = if (photos) listOf("com.google.android.photopicker", "com.android.photopicker", "com.google.android.providers.media.module", "com.android.providers.media.module")
            else listOf("com.google.android.documentsui", "com.android.documentsui")
        assertTrue("Real Android system picker", packages.any { device.wait(Until.hasObject(By.pkg(it)), 5000) })
        device.dumpWindowHierarchy(File(compose.activity.getExternalFilesDir(null), if (photos) "ssh-photo-picker.xml" else "ssh-document-picker.xml"))
        capture(if (photos) "ssh-photo-picker" else "ssh-document-picker")
    }
    private fun documentRow(name: String): androidx.test.uiautomator.UiObject2 {
        var row = device.wait(Until.findObject(By.text(name)), 1500)
        if (row == null) {
            device.findObject(By.desc("Show roots"))?.click()
            checkNotNull(device.wait(Until.findObject(By.text("Downloads")), 5000)) { "Downloads root missing" }.click()
            row = device.wait(Until.findObject(By.text(name)), 5000)
        }
        return checkNotNull(row) { "Generated document missing from picker: $name" }
    }
    private fun verifyRemote(name: String, bytes: ByteArray) = runBlocking {
        val local = File(root, "picker-verify/${UUID.randomUUID()}")
        assertEquals(bytes.size.toLong(), remote.download("$directory/$name", local) { _, _ -> })
        assertArrayEquals(bytes, local.readBytes())
    }
    @Test fun documentPickerCancelsReopensAndUploadsMultipleFilesWithoutReplacingExistingFile() {
        assumeTrue(Build.VERSION.SDK_INT >= 29)
        val first = "cmux-doc-${UUID.randomUUID()}.txt"
        val second = "cmux-doc-${UUID.randomUUID()}.bin"
        val firstBytes = "Picker document λ 中\nSecond line\n".toByteArray()
        val secondBytes = ByteArray(73019) { (it % 251).toByte() }
        val uris = mutableListOf<Uri>()
        try {
            uris += media(first, "text/plain", firstBytes)
            uris += media(second, "application/octet-stream", secondBytes)
            show(); picker()
            device.pressBack(); ready("ssh.files.refresh")
            assertTrue(runBlocking { remote.list(directory) }.isEmpty())
            picker()
            documentRow(first).longClick()
            checkNotNull(device.wait(Until.findObject(By.text(second)), 5000)).click()
            checkNotNull(device.wait(Until.findObject(By.text(Pattern.compile("(?i)open|select"))), 5000)) { "Multiple-selection confirm button missing" }.click()
            ready("ssh.files.row.$first"); ready("ssh.files.row.$second")
            verifyRemote(first, firstBytes); verifyRemote(second, secondBytes)
            // Select the same source again: the existing remote copy survives.
            picker(); documentRow(first).click()
            val duplicate = first.removeSuffix(".txt") + " 2.txt"
            ready("ssh.files.row.$duplicate")
            verifyRemote(first, firstBytes); verifyRemote(duplicate, firstBytes)
            assertEquals(3, runBlocking { remote.list(directory) }.size)
            capture("ssh-document-uploaded")
        } finally { uris.forEach { compose.activity.contentResolver.delete(it, null, null) } }
    }
    @Test fun photoPickerCancelsReopensAndUploadsAnImageWhichPreviews() {
        assumeTrue(Build.VERSION.SDK_INT >= 33)
        val bitmap = Bitmap.createBitmap(48, 32, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(android.graphics.Color.rgb(31, 165, 96))
        val bytes = ByteArrayOutputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it); it.toByteArray() }
        bitmap.recycle()
        val uri = media("cmux-photo-${UUID.randomUUID()}.png", "image/png", bytes, image = true)
        try {
            show(); picker(photos = true)
            device.pressBack(); ready("ssh.files.refresh")
            assertTrue(runBlocking { remote.list(directory) }.isEmpty())
            picker(photos = true)
            checkNotNull(device.wait(Until.findObject(By.descContains("Photo taken")), 10000)) { "Generated photo missing from picker" }.click()
            // Android 17's modular picker uses Done; earlier versions use Add.
            val confirm = checkNotNull(device.wait(Until.findObject(
                By.pkg(Pattern.compile("com\\.(google\\.)?android\\.(photopicker|providers\\.media\\.module)"))
                    .text(Pattern.compile("(?i)add.*|done"))), 5000)) { "Photo selection confirm button missing" }
            device.dumpWindowHierarchy(File(compose.activity.getExternalFilesDir(null), "ssh-photo-selected.xml"))
            capture("ssh-photo-selected")
            confirm.click()
            runBlocking { withTimeout(15000) { while (remote.list(directory).none { !it.name.startsWith(".cmux-upload-") }) delay(50) } }
            ready("ssh.files.refresh")
            val entry = runBlocking { remote.list(directory) }.single()
            assertTrue(entry.name.matches(Regex("Photo-[0-9]{8}-[0-9]{6}\\.png")))
            verifyRemote(entry.name, bytes)
            ready("ssh.files.row.${entry.name}")
            compose.onNodeWithTag("ssh.files.row.${entry.name}").performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription("Image preview", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNode(hasContentDescription("Image preview", substring = true)).assertIsDisplayed()
            capture("ssh-photo-preview")
        } finally { compose.activity.contentResolver.delete(uri, null, null) }
    }
}
