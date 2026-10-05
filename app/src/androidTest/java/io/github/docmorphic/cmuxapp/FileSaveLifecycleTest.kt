package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.provider.DocumentsContract
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.File
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.regex.Pattern
import org.junit.Assert.*
import org.junit.Test

class FileSaveLifecycleTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val device = UiDevice.getInstance(instrumentation)
    private fun guard() = check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
    private fun find(selector: BySelector): UiObject2 {
        val found = device.wait(Until.findObject(selector), 20_000)
        if (found == null) screenshot("missing-node")
        return checkNotNull(found) { "Missing $selector" }
    }
    private fun await(message: String, test: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + 15_000
        while (SystemClock.elapsedRealtime() < deadline) { if (test()) return; Thread.sleep(75) }
        screenshot("failure"); fail(message)
    }
    private fun screenshot(name: String) {
        val out = File(context.getExternalFilesDir(null), "file-save-lifecycle").apply { mkdirs() }
        device.takeScreenshot(File(out, "$name.png"))
    }
    private fun launch(file: File) = ActivityScenario.launch<ArtifactPreviewTestActivity>(
        Intent(context, ArtifactPreviewTestActivity::class.java).putExtra("path", file.absolutePath)
            .putExtra("route", ChangesPreviewRoute.TEXT.name).putExtra("mime", "text/plain"))
    private fun <T> ActivityScenario<ArtifactPreviewTestActivity>.read(block: (ArtifactPreviewTestActivity) -> T): T {
        var result: T? = null; onActivity { result = block(it) }
        @Suppress("UNCHECKED_CAST") return result as T
    }
    private fun ActivityScenario<ArtifactPreviewTestActivity>.model() = read { ViewModelProvider(it)[FileSaveModel::class.java] }
    private fun save() { find(By.desc("Viewer actions")).click(); find(By.text("Save")).click() }
    private fun cache(value: FileSaveSnapshot) = File(context.cacheDir, "file-saves/${value.id}/content")
    private fun picker() = find(By.pkg("com.google.android.documentsui"))

    @Test fun realPickerResultSurvivesLoadingReplacementRecreationAndPreviewDeletion() {
        guard()
        val source = File(context.cacheDir, "cmux-save-${System.nanoTime()}.txt").also { it.writeText("Exact saved bytes 日本語\nline two\n") }
        val expected = source.readBytes(); val launched = AtomicReference<Intent>(); val count = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action == Intent.ACTION_CREATE_DOCUMENT) { launched.set(Intent(intent)); count.incrementAndGet() }
                return null
            }
        }
        instrumentation.addMonitor(monitor)
        var savedUri: Uri? = null
        try { launch(source).use { scenario -> try {
            save(); picker()
            val original = scenario.model()
            val snapshot = checkNotNull(scenario.read { original.pending })
            assertEquals(FileSavePhase.WAITING, snapshot.phase); assertTrue(cache(snapshot).isFile)
            assertEquals("text/plain", launched.get().type); assertEquals(source.name, launched.get().getStringExtra(Intent.EXTRA_TITLE))
            val oldActivity = scenario.read { it }
            scenario.onActivity { it.loading = true; it.recreate() }; assertTrue(source.delete())
            picker(); assertEquals(1, count.get())
            screenshot("picker-after-recreation")
            find(By.text(Pattern.compile("save", Pattern.CASE_INSENSITIVE))).click()
            await("Save result was lost after preview replacement") { scenario.read { it !== oldActivity && original.lastSavedUri != null && !original.busy } }
            assertSame(original, scenario.model())
            savedUri = scenario.read { original.lastSavedUri }
            assertArrayEquals(expected, context.contentResolver.openInputStream(checkNotNull(savedUri))!!.use { it.readBytes() })
            assertTrue(scenario.read { it.loading }); find(By.text("Loading replacement preview"))
            await("Save snapshot was not released") { !cache(snapshot).parentFile!!.exists() }
            assertNull(scenario.read { original.failure }); assertEquals(1, count.get())
            screenshot("saved-without-preview")
        } finally {
            // CREATE_DOCUMENT's temporary grant ends when this Activity's task closes.
            savedUri?.let { DocumentsContract.deleteDocument(context.contentResolver, it) }
        } } } finally {
            instrumentation.removeMonitor(monitor); source.delete()
        }
    }

    @Test fun realPickerCancellationAfterRecreationReleasesSnapshotAndAllowsAnotherSave() {
        guard()
        val source = File(context.cacheDir, "cmux-cancel-${System.nanoTime()}.txt").also { it.writeText("Cancel keeps the preview") }
        try { launch(source).use { scenario ->
            save(); picker(); val owner = scenario.model()
            val original = checkNotNull(scenario.read { owner.pending })
            val oldActivity = scenario.read { it }
            scenario.onActivity { it.recreate() }; picker()
            device.pressBack()
            await("Cancellation did not release the pending picker") { scenario.read { it !== oldActivity && !owner.busy } }
            assertSame(owner, scenario.model())
            await("Cancelled save left private bytes") { !cache(original).parentFile!!.exists() }
            assertTrue(source.isFile); assertNull(scenario.read { owner.failure })
            save(); picker(); val second = checkNotNull(scenario.read { owner.pending })
            assertNotEquals(original.id, second.id); device.pressBack()
            await("Second cancellation did not finish") { scenario.read { !owner.busy } }
            await("Second cancelled snapshot leaked") { !cache(second).parentFile!!.exists() }
            screenshot("cancelled-picker")
        } } finally { source.delete() }
    }

    @Test fun failedDestinationRetainsRetrySnapshotAcrossRecreationAndCancelCleansIt() {
        guard()
        val source = File(context.cacheDir, "cmux-retry-${System.nanoTime()}.txt").also { it.writeText("Retry this exact snapshot") }
        val count = AtomicInteger()
        val monitor = object : Instrumentation.ActivityMonitor() {
            override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                if (intent.action != Intent.ACTION_CREATE_DOCUMENT) return null
                return if (count.incrementAndGet() == 1) Instrumentation.ActivityResult(Activity.RESULT_OK,
                    Intent().setData(Uri.parse("content://io.github.docmorphic.cmuxapp.missing-provider/save")))
                else Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
            }
        }
        instrumentation.addMonitor(monitor)
        try { launch(source).use { scenario ->
            save(); find(By.text("Couldn't save file")); val owner = scenario.model()
            val pending = checkNotNull(scenario.read { owner.pending })
            assertEquals(FileSavePhase.FAILED, pending.phase); assertEquals(source.readText(), cache(pending).readText())
            assertFalse(scenario.read { owner.failure.orEmpty().contains("content://") })
            scenario.recreate(); find(By.text("Couldn't save file")); assertSame(owner, scenario.model())
            assertTrue(scenario.read { owner.canRetry }); screenshot("failed-save-recreated")
            find(By.text("Try again")).click()
            await("Retry cancellation did not clear state") { scenario.read { !owner.busy && owner.failure == null } }
            await("Retry cancellation left private bytes") { !cache(pending).parentFile!!.exists() }
            assertEquals(2, count.get()); assertTrue(source.isFile)
        } } finally { instrumentation.removeMonitor(monitor); source.delete() }
    }
}
