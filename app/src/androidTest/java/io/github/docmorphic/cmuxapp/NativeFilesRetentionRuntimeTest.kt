package io.github.docmorphic.cmuxapp

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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Exercises the actual terminal -> Files route, its retained feed, and real Activity recreation. */
class NativeFilesRetentionRuntimeTest {
    @Test fun galleryKeepsDownloadedPdfPageAndPendingTransferAcrossRecreation() {
        check(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        val device = UiDevice.getInstance(instrumentation)
        val credentials = NativeCredentialStore(context)
        val output = File(context.getExternalFilesDir(null), "files-retention").apply { mkdirs() }
        val pending = CountDownLatch(1); val release = CountDownLatch(1)
        val pdf = ByteArrayOutputStream().use { bytes ->
            val document = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = document.startPage(PdfDocument.PageInfo.Builder(240, 600, index + 1).create())
                    page.canvas.drawColor(if (index == 0) Color.BLUE else Color.GREEN); document.finishPage(page)
                }
                document.writeTo(bytes); bytes.toByteArray()
            } finally { document.close() }
        }
        val text = "Retained download completed after screen recreation."
        fun find(selector: BySelector) = checkNotNull(device.wait(Until.findObject(selector), 20_000)) { "Missing $selector" }
        fun await(message: String, predicate: () -> Boolean) {
            val end = SystemClock.elapsedRealtime() + 15_000
            while (SystemClock.elapsedRealtime() < end) { if (predicate()) return; Thread.sleep(75) }
            device.takeScreenshot(File(output, "failure.png")); fail(message)
        }
        fun greenPage() {
            await("The restored PDF page is not visibly green") {
                val page = device.findObject(By.desc("PDF page 2 of 2")) ?: return@await false
                val bounds = page.visibleBounds; val screen = instrumentation.uiAutomation.takeScreenshot()
                try { !bounds.isEmpty && screen.getPixel(bounds.centerX(), bounds.centerY()) == Color.GREEN }
                finally { screen.recycle() }
            }
        }
        NativeFixturePeer().use { peer ->
            try {
                credentials.clear(); credentials.update { it.put("refresh_token", "files-retention-fixture")
                    .put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
                peer.artifactsSupported = true
                peer.customWorkspaceListing = JSONObject("""{"workspaces":[{"id":"workspace-1","title":"Files workspace","terminals":[{"id":"terminal-1","title":"Shell"}]}]}""")
                val items = JSONArray().put(JSONObject().put("path", "/report.pdf").put("kind", "binary").put("size", pdf.size))
                    .put(JSONObject().put("path", "/pending.txt").put("kind", "text").put("size", text.length))
                peer.artifactResponse = { method, params ->
                    val path = params.optString("path"); val bytes = if (path == "/report.pdf") pdf else text.toByteArray()
                    when {
                        method.endsWith("scan") -> JSONObject().put("session_id", "files-session").put("artifacts", items)
                        method.endsWith("gallery") -> JSONObject().put("session_id", "files-session").put("generation", "g1")
                            .put("referenced", items).put("referenced_total", 2)
                        method.endsWith("stat") -> JSONObject().put("exists", true).put("is_directory", false).put("size", bytes.size)
                            .put("kind", if (path == "/report.pdf") "binary" else "text")
                            .put("mime_type", if (path == "/report.pdf") "application/pdf" else "text/plain")
                        else -> {
                            if (path == "/pending.txt") { pending.countDown(); check(release.await(30, TimeUnit.SECONDS)) }
                            JSONObject().put("offset", 0).put("total_size", bytes.size).put("eof", true)
                                .put("data_b64", Base64.getEncoder().encodeToString(bytes))
                        }
                    }
                }
                NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                    MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" }).also { it.connect() }
                }
                ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                    find(By.text("Files workspace")).click()
                    find(By.desc("Choose terminal or pane")).click(); find(By.text("Files")).click()
                    find(By.desc("Open file /report.pdf")).click()
                    find(By.text("Next page")).click(); find(By.text("2 / 2")); greenPage(); device.waitForIdle()
                    val pageBounds = find(By.desc("PDF page 2 of 2")).visibleBounds
                    var original: TerminalFilesPresentation? = null
                    scenario.onActivity { original = ViewModelProvider(it)[NativeFeedSession::class.java].filesSheet }
                    val owner = checkNotNull(original); val originalFile = checkNotNull(owner.galleryPreview.state.value.artifact).file
                    fun fetches(path: String) = peer.requests.count { it.optString("method").endsWith(".artifact.fetch") &&
                        it.getJSONObject("params").optString("path") == path }
                    val reads = fetches("/report.pdf")
                    assertEquals(1, reads)
                    scenario.recreate(); find(By.text("2 / 2")); greenPage()
                    assertEquals(pageBounds, find(By.desc("PDF page 2 of 2")).visibleBounds)
                    scenario.onActivity { assertSame(owner, ViewModelProvider(it)[NativeFeedSession::class.java].filesSheet) }
                    assertEquals(originalFile, owner.galleryPreview.state.value.artifact?.file)
                    assertTrue(originalFile.exists()); assertEquals(reads, fetches("/report.pdf"))
                    device.takeScreenshot(File(output, "pdf-recreated.png"))
                    device.pressBack(); find(By.desc("Open file /pending.txt")).click()
                    assertTrue(pending.await(15, TimeUnit.SECONDS)); assertEquals(1, fetches("/pending.txt"))
                    scenario.recreate(); find(By.desc("File preview /pending.txt"))
                    scenario.onActivity { assertSame(owner, ViewModelProvider(it)[NativeFeedSession::class.java].filesSheet) }
                    release.countDown()
                    await("Pending transfer did not complete") { owner.galleryPreview.state.value.artifact != null }
                    assertEquals(1, fetches("/pending.txt"))
                    val downloaded = checkNotNull(owner.galleryPreview.state.value.artifact).file
                    assertEquals(text, downloaded.readText()); find(By.text(text))
                    device.takeScreenshot(File(output, "pending-recreated.png"))
                    find(By.text("Done")).click()
                    await("Closing Files did not release downloaded data") { !downloaded.exists() && !originalFile.exists() }
                    scenario.onActivity { assertNull(ViewModelProvider(it)[NativeFeedSession::class.java].filesSheet) }
                    assertTrue(peer.requests.filter { it.optString("method").endsWith(".artifact.fetch") }
                        .all { it.getJSONObject("params").optString("session_id") == "files-session" })
                }
            } finally { release.countDown(); NativeLifecycleTestActivity.connector = null; credentials.clear() }
        }
    }
}
