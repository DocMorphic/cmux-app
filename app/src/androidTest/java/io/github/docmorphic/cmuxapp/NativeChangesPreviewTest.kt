package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

@OptIn(ExperimentalTestApi::class)
class NativeChangesPreviewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private var visible by mutableStateOf(true)
    @Before fun setup() {
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
        runBlocking { client.connect() }
    }
    @After fun cleanup() { compose.activity.finish(); client.close(); peer.close() }
    private fun files(path: String, kind: String, oldPath: String? = null) = JSONObject().put("workspace_id", "ws")
        .put("files", JSONArray().put(JSONObject().put("path", path).put("old_path", oldPath).put("status", kind).put("is_binary", true)))
    private fun diff(path: String) = JSONObject().put("path", path).put("is_binary", true).put("unified_diff", "")
    private fun stat(data: ByteArray, revision: String, mime: String, kind: String) = JSONObject().put("exists", true).put("is_directory", false)
        .put("size", data.size).put("mime_type", mime).put("kind", kind).put("content_fingerprint", fingerprint(data, revision))
    private fun fingerprint(data: ByteArray, revision: String) = if (revision == "base") "blob:fixture:${data.size}" else "stat:${data.size}:123:4:5:6"
    private fun chunk(data: ByteArray, revision: String) = JSONObject().put("data_b64", Base64.getEncoder().encodeToString(data)).put("offset", 0)
        .put("total_size", data.size).put("eof", true).put("content_fingerprint", fingerprint(data, revision))
    private fun show() { compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize()) { Box(Modifier.fillMaxSize().safeDrawingPadding()) { if (visible) NativeChangesView(client, "ws", "Fixture repo", { visible = false }) } } } } }
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    private fun waitDescription(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription(text).fetchSemanticsNodes().isNotEmpty() }
    private fun png(color: Int) = ByteArrayOutputStream().use { out ->
        val bitmap = Bitmap.createBitmap(160, 120, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(color); bitmap.compress(Bitmap.CompressFormat.PNG, 100, out); bitmap.recycle(); out.toByteArray()
    }

    @Test fun renamedImageSwitchesRevisionAndCopiedImageSurvivesClosingPreview() {
        val before = png(Color.RED); val after = png(Color.BLUE)
        peer.changesResponse = { method, params ->
            val revision = params.optString("revision", "current"); val data = if (revision == "base") before else after
            when {
                method.endsWith(".files") -> files("new/image.png", "renamed", "old/image.png")
                method.endsWith(".file_diff") -> diff(params.getString("path"))
                method.endsWith(".file_stat") -> stat(data, revision, "image/png", "image")
                else -> chunk(data, revision)
            }
        }
        show(); waitText("image.png")
        compose.onNodeWithContentDescription("Open diff new/image.png").performClick()
        waitDescription("Image preview image.png")
        val center = compose.onNodeWithContentDescription("Image preview image.png").fetchSemanticsNode().boundsInWindow.center
        var rendered: Bitmap? = null
        compose.waitUntil(10_000) {
            val screenshot = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
            val matched = screenshot.getPixel(center.x.toInt(), center.y.toInt()) == Color.BLUE
            if (matched) rendered = screenshot else screenshot.recycle()
            matched
        }
        compose.activity.openFileOutput("changes-image-preview.png", Context.MODE_PRIVATE).use { rendered!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        rendered!!.recycle()
        compose.onNodeWithText("Before").performClick()
        waitDescription("Before preview old/image.png")
        waitDescription("Image preview image.png")
        var previousUri: android.net.Uri? = null
        compose.runOnUiThread { previousUri = (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.uri }
        compose.onNodeWithContentDescription("Viewer actions").performClick(); compose.onNodeWithText("Copy Image").performClick()
        var uri: android.net.Uri? = null
        compose.waitUntil(10_000) {
            compose.runOnUiThread { uri = (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.uri }
            uri != null && uri != previousUri
        }
        val bitmap = compose.activity.contentResolver.openInputStream(uri!!).use { BitmapFactory.decodeStream(it) }
        assertEquals(Color.RED, bitmap.getPixel(80, 60)); bitmap.recycle()
        assertTrue(peer.requests.filter { it.optString("method").endsWith(".file_fetch") }.any {
            val p = it.getJSONObject("params"); p.getString("revision") == "base" && p.getString("path") == "old/image.png"
        })
        compose.onNodeWithText("After").performClick(); waitDescription("After preview new/image.png"); waitDescription("Image preview image.png")
        compose.runOnIdle { visible = false }; compose.waitForIdle()
        compose.waitUntil(10_000) { File(compose.activity.cacheDir, "changes-previews").listFiles().orEmpty().isEmpty() }
        assertArrayEquals(before, compose.activity.contentResolver.openInputStream(uri!!).use { it!!.readBytes() })
    }

    @Test fun deletedPdfLoadsBaseRevisionAndRendersBothPages() {
        val data = ByteArrayOutputStream().use { output ->
            val pdf = PdfDocument()
            try {
                repeat(2) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(240, 320, index + 1).create())
                    page.canvas.drawColor(if (index == 0) Color.YELLOW else Color.GREEN)
                    page.canvas.drawText("Page ${index + 1}", 20f, 50f, Paint().apply { color = Color.BLACK; textSize = 22f })
                    pdf.finishPage(page)
                }
                pdf.writeTo(output)
            } finally { pdf.close() }; output.toByteArray()
        }
        peer.changesResponse = { method, params -> when {
            method.endsWith(".files") -> files("deleted.pdf", "deleted")
            method.endsWith(".file_diff") -> diff("deleted.pdf")
            method.endsWith(".file_stat") -> stat(data, params.getString("revision"), "application/pdf", "binary")
            else -> chunk(data, params.getString("revision"))
        } }
        show(); waitText("deleted.pdf"); compose.onNodeWithContentDescription("Open diff deleted.pdf").performClick()
        waitDescription("PDF page 1 of 2")
        compose.onNodeWithText("Before").assertDoesNotExist(); compose.onNodeWithText("After").assertDoesNotExist()
        compose.onNodeWithText("Next page").performClick()
        waitDescription("PDF page 2 of 2"); compose.onNodeWithContentDescription("PDF page 2 of 2").assertIsDisplayed()
        waitText("2 / 2"); compose.onNodeWithText("Next page").assertIsNotEnabled()
        compose.onNodeWithText("Previous page").assertIsEnabled().performClick()
        waitText("1 / 2"); compose.onNodeWithText("Previous page").assertIsNotEnabled()
        assertTrue(peer.requests.filter { it.optString("method").endsWith(".file_fetch") }.all { it.getJSONObject("params").getString("revision") == "base" })
        val local = File(compose.activity.cacheDir, "changes-previews").walkTopDown().first { it.name == "deleted.pdf" }
        ChangesPdfDocument(local).use { pdf ->
            assertEquals(2, pdf.pageSizes.size)
            val bitmap = pdf.render(1, 240); assertEquals(Color.GREEN, bitmap.getPixel(120, 160)); bitmap.recycle()
        }
    }

    @Test fun mixedAndVeryShortPdfPagesRemainIndividuallyNavigable() {
        // The first pair fits entirely in a portrait viewport. The final page
        // is also too short to reach the top without trailing layout space.
        val sizes = listOf(40, 40, 600, 80)
        val data = ByteArrayOutputStream().use { output ->
            val pdf = PdfDocument()
            try {
                sizes.forEachIndexed { index, height ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(240, height, index + 1).create())
                    page.canvas.drawColor(if (index % 2 == 0) Color.YELLOW else Color.GREEN)
                    pdf.finishPage(page)
                }
                pdf.writeTo(output)
            } finally { pdf.close() }; output.toByteArray()
        }
        peer.changesResponse = { method, params -> when {
            method.endsWith(".files") -> files("mixed.pdf", "added")
            method.endsWith(".file_diff") -> diff("mixed.pdf")
            method.endsWith(".file_stat") -> stat(data, "current", "application/pdf", "binary")
            else -> chunk(data, params.getString("revision"))
        } }
        show(); waitText("mixed.pdf"); compose.onNodeWithContentDescription("Open diff mixed.pdf").performClick()
        waitText("1 / 4")
        for (page in 2..4) {
            compose.onNodeWithText("Next page").assertIsEnabled().performClick()
            waitText("$page / 4"); compose.onNodeWithContentDescription("PDF page $page of 4").assertIsDisplayed()
        }
        compose.onNodeWithText("Next page").assertIsNotEnabled()
        for (page in 3 downTo 1) {
            compose.onNodeWithText("Previous page").assertIsEnabled().performClick()
            waitText("$page / 4")
        }
        compose.onNodeWithText("Previous page").assertIsNotEnabled()
        assertEquals(1, peer.requests.count { it.optString("method").endsWith(".file_fetch") })
    }

    @Test fun changedContentFailureCanRetryWithoutPublishingPartialPreview() {
        val data = png(Color.CYAN)
        val inconsistent = java.util.concurrent.atomic.AtomicBoolean(true)
        peer.changesResponse = { method, params -> when {
            method.endsWith(".files") -> files("image.png", "added")
            method.endsWith(".file_diff") -> diff("image.png")
            method.endsWith(".file_stat") -> stat(data, "current", "image/png", "image")
            else -> chunk(data, params.getString("revision")).also { if (inconsistent.get()) it.put("content_fingerprint", "stat:${data.size}:124:4:5:6") }
        } }
        show(); waitText("image.png"); compose.onNodeWithContentDescription("Open diff image.png").performClick()
        waitText("Couldn't load preview")
        compose.onNodeWithContentDescription("Image preview image.png").assertDoesNotExist()
        compose.onNodeWithContentDescription("Viewer actions").assertIsNotEnabled()
        assertTrue(File(compose.activity.cacheDir, "changes-previews").listFiles().orEmpty().isEmpty())
        inconsistent.set(false); compose.onNodeWithText("Retry").performClick()
        waitDescription("Image preview image.png"); compose.onNodeWithContentDescription("Viewer actions").assertIsEnabled()
        compose.onNodeWithText("Before").assertDoesNotExist()
    }

    @Test fun audioPreviewPreparesPlaysAndReleasesOnClose() {
        val size = 16000 * 2 * 2
        val data = ByteBuffer.allocate(44 + size).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + size); put("WAVEfmt ".toByteArray()); putInt(16)
            putShort(1); putShort(1); putInt(16000); putInt(32000); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(size)
            repeat(size / 2) { putShort(0) }
        }.array()
        peer.changesResponse = { method, params -> when {
            method.endsWith(".files") -> files("sound.wav", "added")
            method.endsWith(".file_diff") -> diff("sound.wav")
            method.endsWith(".file_stat") -> stat(data, "current", "audio/wav", "binary")
            else -> chunk(data, params.getString("revision"))
        } }
        show(); waitText("sound.wav"); compose.onNodeWithContentDescription("Open diff sound.wav").performClick()
        waitDescription("Media preview sound.wav")
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Play") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Play").performClick(); waitText("Pause")
        compose.onNodeWithText("Restart").performClick(); waitText("Play")
        compose.runOnIdle { visible = false }; compose.waitForIdle()
        compose.waitUntil(10_000) { File(compose.activity.cacheDir, "changes-previews").listFiles().orEmpty().isEmpty() }
    }
    @Test fun extensionlessImageCopyPreservesImageMimeAndExactBytes() {
        val data = png(Color.MAGENTA)
        peer.changesResponse = { method, _ -> when {
            method.endsWith(".files") -> files("image", "added")
            method.endsWith(".file_diff") -> diff("image")
            method.endsWith(".file_stat") -> stat(data, "current", "image/png", "image")
            else -> chunk(data, "current")
        } }
        show(); waitText("image"); compose.onNodeWithContentDescription("Open diff image").performClick()
        waitDescription("Image preview image")
        var previousUri: android.net.Uri? = null
        compose.runOnUiThread { previousUri = (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip?.getItemAt(0)?.uri }
        compose.onNodeWithContentDescription("Viewer actions").performClick(); compose.onNodeWithText("Copy Image").performClick()
        var clip: android.content.ClipData? = null
        compose.waitUntil(10_000) {
            compose.runOnUiThread { clip = (compose.activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).primaryClip }
            clip?.getItemAt(0)?.uri != null && clip?.getItemAt(0)?.uri != previousUri
        }
        assertTrue(clip!!.description.hasMimeType("image/png"))
        val uri = clip!!.getItemAt(0).uri
        assertEquals("image/png", compose.activity.contentResolver.getType(uri))
        assertArrayEquals(data, compose.activity.contentResolver.openInputStream(uri).use { it!!.readBytes() })
    }

}
