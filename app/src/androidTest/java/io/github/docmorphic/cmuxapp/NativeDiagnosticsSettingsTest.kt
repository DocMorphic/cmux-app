package io.github.docmorphic.cmuxapp

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.ZipFile

class NativeDiagnosticsSettingsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root: File
    private lateinit var files: DiagnosticFiles
    private lateinit var recorder: DiagnosticRecorder
    private val exported = AtomicReference<File?>()
    @Before fun setup() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        root = File(context.cacheDir, "diagnostic-exports/test-${UUID.randomUUID()}").apply { mkdirs() }
        files = DiagnosticFiles(File(root, "private"), File(root, "exports"), "UI fixture")
        recorder = DiagnosticRecorder(files, 1, DiagnosticRole.APP)
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { NativeDiagnosticsSettings(recorder) { exported.set(it) } } } }
        compose.waitUntil(5000) { compose.onAllNodes(hasTestTag("diagnostics.verbose") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
    }
    @After fun cleanup() { if (::recorder.isInitialized) runBlocking { recorder.shutdown() }; if (::root.isInitialized) root.deleteRecursively() }
    private fun text(file: File) = ZipFile(file).use { zip -> zip.entries().asSequence().joinToString("\n") { zip.getInputStream(it).bufferedReader().use { reader -> reader.readText() } } }
    @Test fun exportsAReadableZipUsingOnlyAReadGrantAndShowsTheSettings() {
        recorder.record(DebugOperation.SSH_CONNECT, DebugOutcome.SUCCESS)
        compose.onNodeWithTag("diagnostics.export").performClick()
        compose.waitUntil(5000) { exported.get() != null }
        val file = exported.get()!!
        assertTrue(text(file).contains("SSH_CONNECT"))
        val intent = artifactShareIntent(context, file, "application/zip")
        assertEquals(Intent.FLAG_GRANT_READ_URI_PERMISSION, intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
        assertEquals("application/zip", intent.type)
        val uri = intent.clipData!!.getItemAt(0).uri
        assertTrue(context.contentResolver.openInputStream(uri)!!.use { it.readBytes().contentEquals(file.readBytes()) })
        val bitmap = compose.onRoot().captureToImage().asAndroidBitmap()
        val output = File(context.getExternalFilesDir(null), "screenshots/diagnostics-settings.png").apply { parentFile!!.mkdirs() }
        output.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
    }
    @Test fun clearRequiresConfirmationRemovesHistoryAndExportsButKeepsOtherData() {
        recorder.record(DebugOperation.SSH_CONNECT, DebugOutcome.SUCCESS)
        val old = runBlocking { recorder.export() }
        val unrelated = File(root, "saved-data").apply { writeText("preserve") }
        compose.onNodeWithTag("diagnostics.clear").performClick()
        compose.onNodeWithText("Cancel").performClick()
        assertTrue(old.exists()); assertTrue(text(runBlocking { recorder.export() }).contains("SSH_CONNECT"))
        compose.onNodeWithTag("diagnostics.clear").performClick()
        compose.onNodeWithTag("diagnostics.confirm-clear").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Logs cleared").fetchSemanticsNodes().isNotEmpty() }
        assertFalse(old.exists()); assertEquals("preserve", unrelated.readText())
        assertFalse(text(runBlocking { recorder.export() }).contains("SSH_CONNECT"))
    }
    @Test fun failedVerboseEnableDoesNotShowTheSwitchAsEnabled() {
        val state = File(root, "private/state"); state.delete(); state.mkdirs()
        File(state, "blocked").writeText("fixture")
        compose.onNodeWithTag("diagnostics.verbose").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithText("Couldn’t change diagnostic logging. Check available storage and try again.").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("diagnostics.verbose").assertIsOff()
    }
}
