package io.github.docmorphic.cmuxapp

import android.content.Intent
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.UUID

class NativeTaskAttachmentPreviewRuntimeTest {
    @Test fun disabledEditingStillPreviewsExactPdfAndKeepsPageThroughActivityRecreation() {
        // This fixture replaces the local account; never run it against the user's physical phone.
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val credentials = NativeCredentialStore(context)
        val output = File(context.getExternalFilesDir(null), "task-attachment-viewer").apply { mkdirs() }
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
        fun await(message: String, predicate: () -> Boolean) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) { if (predicate()) return; Thread.sleep(75) }
            device.takeScreenshot(File(output, "failure.png")); fail(message)
        }
        val bytes = ByteArrayOutputStream().use { stream ->
            val pdf = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(240, 600, index + 1).create())
                    page.canvas.drawColor(if (index == 0) Color.BLUE else Color.GREEN); pdf.finishPage(page)
                }
                pdf.writeTo(stream); stream.toByteArray()
            } finally { pdf.close() }
        }
        fun greenPage() = await("Expected visible green second PDF page") {
            val page = device.findObject(By.desc("PDF page 2 of 2")) ?: return@await false
            val bounds = page.visibleBounds
            val image = instrumentation.uiAutomation.takeScreenshot()
            try { !bounds.isEmpty && image.getPixel(bounds.centerX(), bounds.centerY()) == Color.GREEN }
            finally { image.recycle() }
        }
        TaskDraftRepository.clearMemory(); TaskDraftRepository.clearAttachments(context)
        try {
            credentials.clear(); credentials.update { it.put("refresh_token", "attachment-preview-fixture") }
            val session = checkNotNull(credentials.taskSession())
            val repo = runBlocking(Dispatchers.IO) { TaskDraftRepository.get(context, session) }
            val id = UUID.randomUUID().toString()
            val editor = repo.drafts.begin(id, "preview-fixture", "Preview Mac", "/repo")
            val item = ComposerAttachment(name = "staged-report.pdf", size = bytes.size)
            runBlocking { repo.attach(editor, AttachmentFiles.Prepared(item, bytes)) }
            repo.drafts.end(editor)
            val intent = Intent(context, TaskAttachmentPreviewTestActivity::class.java).putExtra("session", session).putExtra("draft", id)
            ActivityScenario.launch<TaskAttachmentPreviewTestActivity>(intent).use { scenario ->
                find(By.text(item.name)).click()
                find(By.text("Next page")).click(); find(By.text("2 / 2")); greenPage()
                var original: TaskAttachmentPreviewModel? = null
                scenario.onActivity { original = ViewModelProvider(it)[TaskAttachmentPreviewModel::class.java] }
                val model = checkNotNull(original)
                val artifact = checkNotNull(model.controller.state.value.artifact)
                assertArrayEquals(bytes, artifact.file.readBytes())
                device.takeScreenshot(File(output, "page-two.png"))
                scenario.recreate(); find(By.text("2 / 2")); greenPage()
                scenario.onActivity { assertSame(model, ViewModelProvider(it)[TaskAttachmentPreviewModel::class.java]) }
                assertEquals(artifact.file, model.controller.state.value.artifact?.file)
                device.takeScreenshot(File(output, "page-two-recreated.png"))
                find(By.text("Done")).click()
                await("Dismissal must remove decrypted preview") { !artifact.file.exists() }
                assertEquals(listOf(item), repo.drafts.state.value.getValue(id).attachments)
                assertArrayEquals(bytes, runBlocking { repo.readAttachment(item) })
                find(By.text(item.name)).click(); find(By.desc("PDF page 1 of 2"))
                val replacement = checkNotNull(model.controller.state.value.artifact)
                assertNotEquals(artifact.file, replacement.file)
                device.pressBack(); await("Back must remove decrypted preview") { !replacement.file.exists() }
            }
        } finally { TaskDraftRepository.clearMemory(); TaskDraftRepository.clearAttachments(context); credentials.clear() }
    }
}
