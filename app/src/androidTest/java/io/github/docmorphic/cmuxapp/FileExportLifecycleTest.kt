package io.github.docmorphic.cmuxapp

import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class FileExportLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun guard() = check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
    private fun find(text: String) = checkNotNull(device.wait(Until.findObject(By.text(text)), 15_000)) { "Missing $text" }
    private fun await(test: () -> Boolean) {
        val until = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < until) { if (test()) return; Thread.sleep(50) }
        fail("Export did not reach the expected state")
    }
    private fun launch(source: File, id: String) = ActivityScenario.launch<ArtifactPreviewTestActivity>(
        Intent(context, ArtifactPreviewTestActivity::class.java).putExtra("path", source.absolutePath)
            .putExtra("route", ChangesPreviewRoute.TEXT.name).putExtra("mime", "text/plain").putExtra("export_id", id))
    private fun screenshot(name: String) {
        val folder = File(context.getExternalFilesDir(null), "file-export-lifecycle").apply { mkdirs() }
        device.takeScreenshot(File(folder, "$name.png"))
    }
    private fun store() = FileExportStore(File(context.noBackupFilesDir, "file-export-state"), File(context.filesDir, "file-exports"))
    private fun prepare(id: String): LocalFilePreview = runBlocking {
        val folder = File(context.cacheDir, "export-fixture-$id").apply { mkdirs() }
        val file = File(folder, "report.txt").apply { writeText("Exact restored export 日本語\n") }
        store().create(id, "report.txt", FileExportAction.SHARE)
        store().adopt(id, LocalFilePreview(file, file.length(), "text/plain", ChangesPreviewRoute.TEXT))
    }
    @Test fun restoredReadyActionRequiresConfirmationUsesRealChooserAndCannotReplayCompletedReceipt() {
        guard()
        val id = UUID.randomUUID().toString(); val artifact = prepare(id); val expected = artifact.file.readBytes()
        val preview = File(context.cacheDir, "export-preview-$id.txt").apply { writeText("Preview stays open") }
        val chooser = AtomicReference<Intent>(); val calls = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CHOOSER) { chooser.set(Intent(intent)); calls.incrementAndGet() }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        try {
            launch(preview, id).use { scenario ->
                find("Continue file action?"); assertEquals(0, calls.get()); screenshot("restored-confirmation")
                scenario.recreate(); find("Continue file action?"); assertEquals(0, calls.get())
                find("Continue").click(); await { calls.get() == 1 && store().read(id)?.phase == FileExportReceiptPhase.HANDED_OFF }
                @Suppress("DEPRECATION") val target = chooser.get().getParcelableExtra<Intent>(Intent.EXTRA_INTENT)!!
                @Suppress("DEPRECATION") val uri = target.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)!!
                assertEquals(Intent.ACTION_SEND, target.action); assertEquals("text/plain", target.type)
                assertTrue(target.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
                assertTrue(uri.path!!.startsWith("/file-exports/"))
                assertArrayEquals(expected, context.contentResolver.openInputStream(uri)!!.use { it.readBytes() })
                assertEquals(uri, target.clipData!!.getItemAt(0).uri)
                await { device.currentPackageName != context.packageName }; screenshot("restored-system-chooser")
                device.pressBack(); await { device.currentPackageName == context.packageName }
            }
            launch(preview, id).use { scenario ->
                await {
                    var idle = false
                    scenario.onActivity { idle = ViewModelProvider(it)[FileExportModel::class.java].controller.state.value.phase == null }
                    idle
                }
                assertEquals(1, calls.get()); assertArrayEquals(expected, artifact.file.readBytes())
                screenshot("completed-receipt-no-replay")
            }
        } finally { instrumentation.removeMonitor(monitor); preview.delete(); artifact.file.parentFile!!.deleteRecursively() }
    }
    @Test fun uncertainHandoffShowsRecoveryMessageAndPreservesPotentialReceiverFile() {
        guard()
        val id = UUID.randomUUID().toString(); val artifact = prepare(id); store().presenting(id)
        val preview = File(context.cacheDir, "export-preview-$id.txt").apply { writeText("Preview") }
        try { launch(preview, id).use {
            find("This file action may already have opened. Open its preview if you want to try again.")
            assertTrue(artifact.file.exists()); screenshot("uncertain-handoff")
            find("OK").click(); assertEquals(context.packageName, device.currentPackageName)
            assertTrue(artifact.file.exists()); assertEquals(FileExportReceiptPhase.PRESENTING, store().read(id)?.phase)
        } } finally { preview.delete(); artifact.file.parentFile!!.deleteRecursively() }
    }
}
