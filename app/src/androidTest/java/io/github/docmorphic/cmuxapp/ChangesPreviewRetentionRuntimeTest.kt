package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class ChangesPreviewRetentionRuntimeTest {
    @Test fun renamedImageAndPdfKeepRevisionBytesAndPageAcrossRecreation() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val credentials = NativeCredentialStore(context)
        val output = File(context.getExternalFilesDir(null), "changes-preview-retention").apply { mkdirs() }
        fun png(color: Int, striped: Boolean = false) = ByteArrayOutputStream().use { bytes ->
            val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(color)
            if (striped) {
                val canvas = android.graphics.Canvas(bitmap)
                canvas.drawRect(0f, 0f, 160f / 3, 120f, android.graphics.Paint().apply { this.color = Color.GREEN })
                canvas.drawRect(320f / 3, 0f, 160f, 120f, android.graphics.Paint().apply { this.color = Color.BLUE })
            }
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, bytes); bitmap.recycle(); bytes.toByteArray()
        }
        val before = png(Color.RED, striped = true); val after = png(Color.BLUE)
        val pdf = ByteArrayOutputStream().use { bytes ->
            val document = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(240, 600, index + 1).create())
                    page.canvas.drawColor(if (index == 0) Color.YELLOW else Color.GREEN); document.finishPage(page)
                }
                document.writeTo(bytes)
            } finally { document.close() }
            bytes.toByteArray()
        }
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 15_000)) { "Missing $selector" }
        fun assertColor(description: String, color: Int, fractionX: Float = .5f) {
            val deadline = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < deadline) {
                try {
                    val bounds = find(By.desc(description)).visibleBounds
                    val screenshot = instrumentation.uiAutomation.takeScreenshot()
                    val match = try { bounds.width() > 0 && bounds.height() > 0 &&
                        screenshot.getPixel(bounds.left + (bounds.width() * fractionX).toInt(), bounds.centerY()) == color } finally { screenshot.recycle() }
                    if (match) return
                } catch (_: StaleObjectException) { }
                Thread.sleep(100)
            }
            device.takeScreenshot(File(output, "pixel-failure.png")); fail("Wrong visible pixels for $description")
        }
        NativeFixturePeer().use { peer ->
            try {
                credentials.clear(); credentials.update { it.put("refresh_token", "preview-retention-fixture")
                    .put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
                peer.workspaceChangesSupported = true
                peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Preview workspace","terminals":[{"id":"terminal-1","title":"Shell"}]}]}""")
                peer.changesResponse = { method, params ->
                    val path = params.optString("path"); val revision = params.optString("revision", "current")
                    val bytes = if (path == "report.pdf") pdf else if (revision == "base") before else after
                    val fingerprint = if (revision == "base") "blob:fixture:${bytes.size}" else "stat:${bytes.size}:123:4:5:6"
                    when {
                        method.endsWith(".summary") -> JSONObject("""{"summaries":[{"workspace_id":"workspace-1","is_repo":true,"files_changed":2,"additions":0,"deletions":0}]}""")
                        method.endsWith(".files") -> JSONObject("""{"workspace_id":"workspace-1","repo_root":"/fixture","files":[{"path":"image.png","old_path":"old/image.png","status":"renamed","is_binary":true},{"path":"report.pdf","status":"added","is_binary":true}]}""")
                        method.endsWith(".file_diff") -> JSONObject().put("path", path).put("unified_diff", "").put("is_binary", true).put("truncated", false)
                        method.endsWith(".file_stat") -> JSONObject().put("exists", true).put("is_directory", false).put("size", bytes.size)
                            .put("kind", if (path == "report.pdf") "binary" else "image").put("mime_type", if (path == "report.pdf") "application/pdf" else "image/png")
                            .put("content_fingerprint", fingerprint)
                        else -> JSONObject().put("offset", 0).put("total_size", bytes.size).put("eof", true)
                            .put("data_b64", Base64.getEncoder().encodeToString(bytes)).put("content_fingerprint", fingerprint)
                    }
                }
                NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" }).also { it.connect() }
                }
                ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                    find(By.desc("Changes: 2 files, +0, −0")).click()
                    find(By.desc("Open diff image.png")).click(); assertColor("Image preview image.png", Color.BLUE)
                    find(By.text("Before")).click(); find(By.desc("Before preview old/image.png")); assertColor("Image preview image.png", Color.RED)
                    val imageDescription = "Image preview image.png"
                    assertColor(imageDescription, Color.GREEN, .2f)
                    assertColor(imageDescription, Color.BLUE, .8f)
                    val imageBounds = find(By.desc(imageDescription)).visibleBounds
                    fun doubleTapImage() {
                        device.click(imageBounds.centerX(), imageBounds.centerY()); Thread.sleep(80)
                        device.click(imageBounds.centerX(), imageBounds.centerY())
                    }
                    doubleTapImage()
                    assertColor(imageDescription, Color.RED, .2f)
                    device.swipe(imageBounds.left + imageBounds.width() / 4, imageBounds.centerY(),
                        imageBounds.left + 3 * imageBounds.width() / 4, imageBounds.centerY(), 25)
                    assertColor(imageDescription, Color.GREEN, .2f)
                    assertColor(imageDescription, Color.RED, .8f)
                    var original: WorkspaceChangesPresentation? = null
                    scenario.onActivity { original = ViewModelProvider(it)[NativeFeedSession::class.java].changesSheet }
                    val artifact = checkNotNull(original!!.store.previews.state.value.artifact).file
                    fun fetches() = peer.requests.count { it.optString("method") == "mobile.workspace.changes.file_fetch" }
                    val imageReads = fetches()
                    scenario.recreate(); find(By.desc("Before preview old/image.png")); assertColor("Image preview image.png", Color.RED)
                    assertColor(imageDescription, Color.GREEN, .2f)
                    assertColor(imageDescription, Color.RED, .8f)
                    scenario.onActivity { assertSame(original, ViewModelProvider(it)[NativeFeedSession::class.java].changesSheet)
                        assertEquals(artifact, original!!.store.previews.state.value.artifact?.file) }
                    assertEquals(imageReads, fetches()); device.takeScreenshot(File(output, "image-before-recreated.png"))
                    doubleTapImage()
                    assertColor(imageDescription, Color.GREEN, .2f); assertColor(imageDescription, Color.BLUE, .8f)
                    assertTrue(device.findObject(UiSelector().description(imageDescription)).pinchOut(50, 30))
                    assertColor(imageDescription, Color.RED, .2f)
                    doubleTapImage()
                    assertColor(imageDescription, Color.GREEN, .2f); assertColor(imageDescription, Color.BLUE, .8f)
                    device.swipe(imageBounds.right - imageBounds.width() / 5, imageBounds.centerY(),
                        imageBounds.left + imageBounds.width() / 5, imageBounds.centerY(), 30)
                    find(By.desc("PDF page 1 of 2"))
                    find(By.text("Next page")).click(); find(By.text("2 / 2")); assertColor("PDF page 2 of 2", Color.GREEN)
                    device.waitForIdle()
                    val pageBounds = find(By.desc("PDF page 2 of 2")).visibleBounds
                    val pdfReads = fetches()
                    scenario.recreate(); find(By.text("2 / 2")); assertColor("PDF page 2 of 2", Color.GREEN)
                    assertEquals(pageBounds, find(By.desc("PDF page 2 of 2")).visibleBounds)
                    assertEquals(pdfReads, fetches()); device.takeScreenshot(File(output, "pdf-page-recreated.png"))
                    device.pressBack(); find(By.desc("Close changes")).click()
                    val deadline = SystemClock.elapsedRealtime() + 10_000
                    while (File(context.cacheDir, "changes-previews").listFiles().orEmpty().isNotEmpty() && SystemClock.elapsedRealtime() < deadline) Thread.sleep(50)
                    assertTrue(File(context.cacheDir, "changes-previews").listFiles().orEmpty().isEmpty())
                    assertTrue(peer.requests.none { it.optString("method") == "mobile.terminal.replay" })
                }
            } finally { NativeLifecycleTestActivity.connector = null; credentials.clear() }
        }
    }
}
