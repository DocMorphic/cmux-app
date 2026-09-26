package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.graphics.Bitmap
import kotlinx.coroutines.runBlocking
import android.view.WindowManager
import android.view.View
import android.view.ViewGroup
import android.view.KeyEvent
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Runs the real Compose flow and framed RPC client against a local test peer. */
class NativeFlowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var peer: NativeFixturePeer
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before fun startPeer() {
        compose.runOnUiThread {
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        peer = NativeFixturePeer()
        NativeCredentialStore(context, "native_terminal_drafts").clear()
        TerminalDraftRepository.get(context).drafts.clear()
        NativeCredentialStore(context).update {
            it.put("refresh_token", "emulator-fixture-only")
            it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
        }
    }

    @After fun cleanUp() {
        compose.activity.finish()
        peer.close()
        NativeCredentialStore(context).clear()
        NativeCredentialStore(context, "native_terminal_drafts").clear()
        TerminalDraftRepository.get(context).drafts.clear()
    }

    @Test fun workspaceFilterTerminalInputAndKeyboardResize() {
        compose.setContent {
            CmuxTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect() }
                    })
                }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Read project").assertIsDisplayed()
        screenshot("workspaces")
        compose.onNodeWithText("☷").performClick()
        compose.onNodeWithText("Unread").performClick()
        compose.onNodeWithText("Read project").assertDoesNotExist()
        compose.onNodeWithText("Completed group").assertDoesNotExist()
        compose.onNodeWithText("Claude Code task").performClick()

        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "mobile.terminal.replay" } }
        waitForTerminalText()
        val initialRows = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" }
            .getJSONObject("params").getInt("viewport_rows")
        screenshot("terminal")
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("printf cmux")
        try { compose.waitUntil(10_000) {
            peer.requests.any {
                it.optString("method") == "mobile.terminal.viewport" &&
                    it.getJSONObject("params").optInt("viewport_rows", initialRows) < initialRows
            }
        } } finally {
            waitForTerminalText()
            screenshot("terminal-keyboard")
        }
        compose.onNodeWithText("The coroutine scope left the composition").assertDoesNotExist()
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "terminal.paste" } }
        val input = peer.requests.filter { it.optString("method") == "terminal.paste" }
        assertEquals(1, input.size)
        assertEquals("printf cmux", input.single().getJSONObject("params").getString("text"))
        assertEquals("return", input.single().getJSONObject("params").getString("submit_key"))
        assertTrue(peer.requests.none { it.optString("method") == "terminal.input" })
        compose.onNodeWithText("‹  2").performClick()
        compose.waitUntil(10_000) {
            peer.requests.any {
                it.optString("method") == "mobile.terminal.viewport" &&
                    it.getJSONObject("params").optBoolean("clear")
            }
        }
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
    }

    @Test fun multilineDraftSurvivesNavigationAndRejectedSend() {
        compose.setContent {
            CmuxTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect() }
                    })
                }
            }
        }
        compose.waitUntil(15_000) {
            compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        val message = "Review these changes\nKeep the existing API"
        compose.onNode(hasSetTextAction()).performTextInput(message)
        compose.onNodeWithText("‹  2").performClick()
        compose.onNodeWithText("Read project").performClick()
        assertDraft("")
        compose.onNode(hasSetTextAction()).performTextInput("Separate draft")
        compose.onNodeWithText("‹  2").performClick()
        compose.onNodeWithText("Claude Code task").performClick()
        assertDraft(message)
        peer.rejectNextPaste.set(true)
        val releasePaste = CountDownLatch(1)
        peer.releaseNextPaste = releasePaste
        compose.onNodeWithText("Send").performClick()
        try {
            compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "terminal.paste" } }
            compose.onNodeWithText("Sending…").assertIsNotEnabled()
            val pending = TerminalDrafts(NativeCredentialStore(context, "native_terminal_drafts")
                .load()?.optJSONArray("drafts"))
            assertTrue(pending.state.value.values.any { it.text == message && it.error != null })
        } finally { releasePaste.countDown() }
        compose.waitUntil(10_000) {
            TerminalDraftRepository.get(context).drafts.state.value.values.any {
                it.text == message && it.error == TerminalDrafts.DELIVERY_UNCONFIRMED
            }
        }
        compose.onNodeWithText(TerminalDrafts.DELIVERY_UNCONFIRMED).assertIsDisplayed()
        assertDraft(message)
        assertEquals(1, peer.requests.count { it.optString("method") == "terminal.paste" })
        screenshot("composer-rejected-send")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(10_000) {
            peer.requests.count { it.optString("method") == "terminal.paste" } == 2 &&
                TerminalDraftRepository.get(context).drafts.state.value.values.none { it.text == message }
        }
        assertDraft("")
        val requests = peer.requests.filter { it.optString("method") == "terminal.paste" }
        requests.forEach { request ->
            assertEquals(message, request.getJSONObject("params").getString("text"))
            assertEquals("return", request.getJSONObject("params").getString("submit_key"))
            assertEquals("workspace-1", request.getJSONObject("params").getString("workspace_id"))
            assertEquals("terminal-1", request.getJSONObject("params").getString("surface_id"))
        }
        compose.onNodeWithText("‹  2").performClick()
        compose.onNodeWithText("Read project").performClick()
        assertDraft("Separate draft")
        compose.waitUntil(10_000) {
            val saved = NativeCredentialStore(context, "native_terminal_drafts").load()?.optJSONArray("drafts")
            TerminalDrafts(saved).state.value.values.any { it.text == "Separate draft" }
        }
        assertEquals("emulator-fixture-only", NativeCredentialStore(context).load()?.optString("refresh_token"))
        compose.onNodeWithText("Insert").performClick()
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.paste" } == 3 }
        val inserted = peer.requests.last { it.optString("method") == "terminal.paste" }.getJSONObject("params")
        assertEquals("none", inserted.getString("submit_key"))
        assertEquals("Separate draft", inserted.getString("text"))
        assertEquals("terminal-2", inserted.getString("surface_id"))
    }

    @Test fun attachmentPickerStagesEncryptsAndSendsAfterExplicitRetry() {
        compose.setContent {
            CmuxTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect() }
                    })
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        val file = File(context.cacheDir, "attachment-fixture.txt").apply { writeText("Private fixture content") }
        val photo = File(context.cacheDir, "attachment-fixture.png")
        Bitmap.createBitmap(2400, 1200, Bitmap.Config.ARGB_8888).also { bitmap ->
            bitmap.eraseColor(android.graphics.Color.BLUE)
            photo.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val repo = TerminalDraftRepository.get(context)
        val target = TerminalDrafts.Target("cmux-ios://attach?v=2&r=100.64.0.1:58465", "workspace-1", "terminal-1")
        fun choose(file: File, menu: String) {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val monitor = instrumentation.addMonitor(IntentFilter(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE); addDataType("*/*")
            },
                Instrumentation.ActivityResult(Activity.RESULT_OK, Intent().setData(Uri.fromFile(file))), true)
            try {
                compose.onNodeWithContentDescription("Add attachment").performClick()
                compose.onNodeWithText(menu).performClick()
                compose.waitUntil(10_000) { monitor.hits > 0 }
            } finally { instrumentation.removeMonitor(monitor) }
        }
        choose(photo, "Photos")
        compose.waitUntil(15_000) { repo.drafts.state.value[target]?.attachments?.size == 1 }
        val image = repo.drafts.state.value[target]!!.attachments.single()
        val imageBytes = runBlocking { repo.read(image) }
        val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size, bounds)
        assertEquals(2048, bounds.outWidth)
        assertEquals(1024, bounds.outHeight)
        choose(file, "Files")
        compose.waitUntil(15_000) { repo.drafts.state.value[target]?.attachments?.size == 2 }
        val attachment = repo.drafts.state.value[target]!!.attachments.last()
        assertEquals("Private fixture content", runBlocking { String(repo.read(attachment)) })
        val onDisk = File(context.noBackupFilesDir, "terminal-attachments/${attachment.id}").readBytes()
        assertTrue(!String(onDisk).contains("Private fixture content"))
        runBlocking { repo.persistNow() }
        val restored = TerminalDrafts(NativeCredentialStore(context, "native_terminal_drafts").load()?.getJSONArray("drafts"))
        assertEquals(listOf(image, attachment), restored.state.value[target]!!.attachments)
        compose.onNode(hasSetTextAction()).performTextInput("Explain these attachments")
        screenshot("composer-attachments")
        peer.rejectNextPaste.set(true)
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(15_000) { repo.drafts.state.value[target]?.error != null }
        assertEquals(listOf(attachment), repo.drafts.state.value[target]!!.attachments)
        assertDraft("Explain these attachments")
        compose.onNodeWithText("Send").performClick()
        compose.waitUntil(15_000) { repo.drafts.state.value[target] == null }
        assertDraft("")
        assertEquals(1, peer.requests.count { it.optString("method") == "terminal.paste_image" })
        val uploads = peer.requests.filter { it.optString("method") == "mobile.task.attachment.upload" }
        assertEquals(2, uploads.size)
        uploads.forEach { request ->
            val params = request.getJSONObject("params")
            assertEquals(attachment.id, params.getString("operation_id"))
            assertEquals(attachment.id, params.getString("upload_id"))
            assertEquals("Private fixture content", String(java.util.Base64.getDecoder().decode(params.getString("data_b64"))))
        }
        val paste = peer.requests.last { it.optString("method") == "terminal.paste" }.getJSONObject("params")
        assertEquals("'/tmp/cmux fixture.txt' Explain these attachments", paste.getString("text"))
        runBlocking { repo.persistNow() }
        assertTrue(!File(context.noBackupFilesDir, "terminal-attachments/${attachment.id}").exists())
        file.delete(); photo.delete()
    }

    @Test fun directKeyboardCompositionKeysPauseAndTargetSwitch() {
        compose.setContent {
            CmuxTheme {
                Surface(Modifier.fillMaxSize()) {
                    NativeScreen(onUseHelper = {}, connector = NativeConnector { _, _ ->
                        MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" })
                            .also { it.connect() }
                    })
                }
            }
        }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("Claude Code task").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Claude Code task").performClick()
        waitForTerminalText()
        val initialRows = peer.requests.last { it.optString("method") == "mobile.terminal.viewport" }
            .getJSONObject("params").getInt("viewport_rows")
        compose.onNode(hasSetTextAction()).performTextInput("Keep my composer draft")
        compose.onNodeWithText("Keyboard").performClick()
        try { compose.waitUntil(10_000) {
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true &&
                peer.requests.lastOrNull { it.optString("method") == "mobile.terminal.viewport" }
                    ?.getJSONObject("params")?.optInt("viewport_rows", initialRows)?.let { it < initialRows } == true
        } } finally { screenshot("terminal-direct-open") }
        lateinit var keyboard: TerminalKeyboardView
        lateinit var connection: InputConnection
        compose.runOnIdle {
            keyboard = findTerminalKeyboard(compose.activity.window.decorView)!!
            connection = keyboard.onCreateInputConnection(EditorInfo())!!
            assertTrue(connection.setComposingText("n", 1))
            assertTrue(connection.setComposingText("ni", 1))
            assertEquals("ni", keyboard.text.toString())
            assertTrue(peer.requests.none { it.optString("method") == "terminal.input" })
            connection.commitText("你", 1)
            connection.deleteSurroundingText(2, 0)
            connection.setComposingText("🙂", 1)
            connection.deleteSurroundingText(1, 0)
            assertEquals("Direct typing · tap here for keyboard", keyboard.text.toString())
            connection.setComposingText("🙂", 1)
            connection.deleteSurroundingTextInCodePoints(1, 0)
            connection.setComposingText("界", 1)
        }
        screenshot("terminal-direct-keyboard")
        compose.runOnIdle {
            connection.finishComposingText()
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER))
            connection.sendKeyEvent(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_C, 0, KeyEvent.META_CTRL_ON))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP))
            connection.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_F1))
        }
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 7 }
        assertEquals(listOf("你", "\u007f\u007f", "界", "\r", "\u0003", "\u001b[A", "\u001bOP"),
            peer.requests.filter { it.optString("method") == "terminal.input" }.map { it.getJSONObject("params").getString("text") })
        peer.rejectNextInput.set(true)
        val release = CountDownLatch(1)
        peer.releaseNextInput = release
        compose.runOnIdle { connection.commitText("maybe delivered", 1); connection.commitText("never replay this", 1) }
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 8 }
        release.countDown()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Resume typing").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Resume typing").performClick()
        compose.runOnIdle {
            connection = keyboard.onCreateInputConnection(EditorInfo())!!
            connection.commitText("after resume", 1)
        }
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 9 }
        assertTrue(peer.requests.none { it.optJSONObject("params")?.optString("text") == "never replay this" })
        compose.onNodeWithText("Compose").performClick()
        assertDraft("Keep my composer draft")
        val oldConnection = connection
        compose.onNodeWithText("‹  2").performClick()
        compose.onNodeWithText("Read project").performClick()
        compose.onNodeWithText("Keyboard").performClick()
        compose.runOnIdle {
            assertTrue(!oldConnection.commitText("stale input", 1))
            val next = findTerminalKeyboard(compose.activity.window.decorView)!!
            next.onCreateInputConnection(EditorInfo())!!.commitText("second terminal", 1)
        }
        compose.waitUntil(10_000) { peer.requests.count { it.optString("method") == "terminal.input" } == 10 }
        val last = peer.requests.last { it.optString("method") == "terminal.input" }.getJSONObject("params")
        assertEquals("terminal-2", last.getString("surface_id"))
        assertEquals("second terminal", last.getString("text"))
        assertTrue(peer.requests.none { it.optJSONObject("params")?.optString("text") == "stale input" })
    }

    private fun findTerminalKeyboard(view: View): TerminalKeyboardView? {
        if (view is TerminalKeyboardView) return view
        if (view is ViewGroup) for (index in 0 until view.childCount) {
            findTerminalKeyboard(view.getChildAt(index))?.let { return it }
        }
        return null
    }

    private fun assertDraft(text: String) {
        compose.onNode(hasSetTextAction()).assert(
            SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString(text)))
    }

    private fun waitForTerminalText() {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText("cmux Android terminal", substring = true).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
    }
}

private class NativeFixturePeer : AutoCloseable {
    private val server = ServerSocket(0)
    val port get() = server.localPort
    val requests = CopyOnWriteArrayList<JSONObject>()
    val failures = CopyOnWriteArrayList<String>()
    val rejectNextPaste = AtomicBoolean(false)
    val rejectNextInput = AtomicBoolean(false)
    @Volatile var releaseNextInput: CountDownLatch? = null
    @Volatile var releaseNextPaste: CountDownLatch? = null
    private val sockets = CopyOnWriteArrayList<Socket>()
    @Volatile private var closed = false
    private var revision = 0
    private val acceptThread = Thread {
        try {
            while (!closed) {
                val socket = server.accept()
                sockets += socket
                Thread { serve(socket) }.apply { isDaemon = true; start() }
            }
        } catch (failure: Exception) { if (!closed) failures += failure.toString() }
    }.apply { isDaemon = true; start() }

    private fun serve(socket: Socket) {
        try {
            socket.use {
                val input = socket.getInputStream()
                val decoder = MobileFrameDecoder()
                val buffer = ByteArray(16384)
                while (!closed) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    for (frame in decoder.feed(buffer.copyOf(count))) {
                        val request = JSONObject(String(frame, Charsets.UTF_8))
                        requests += request
                        if (request.optString("method") == "terminal.paste") {
                            releaseNextPaste?.let { latch ->
                                check(latch.await(10, TimeUnit.SECONDS)) { "Paste test acknowledgement was not released" }
                                releaseNextPaste = null
                            }
                        }
                        if (request.optString("method") == "terminal.input") {
                            releaseNextInput?.let { latch ->
                                check(latch.await(10, TimeUnit.SECONDS)) { "Input acknowledgement was not released" }
                                releaseNextInput = null
                            }
                        }
                        val result = response(request.optString("method"), request.optJSONObject("params") ?: JSONObject())
                        val rejected = (request.optString("method") == "terminal.paste" && rejectNextPaste.getAndSet(false)) ||
                            (request.optString("method") == "terminal.input" && rejectNextInput.getAndSet(false))
                        val envelope = JSONObject().put("id", request.getString("id")).put("ok", !rejected)
                        if (rejected) envelope.put("error", JSONObject().put("code", "surface_unavailable")
                            .put("message", "Fixture terminal temporarily unavailable"))
                        else envelope.put("result", result)
                        socket.getOutputStream().write(MobileFrameCodec.encode(envelope.toString().toByteArray()))
                        socket.getOutputStream().flush()
                    }
                }
            }
        } catch (failure: Exception) { if (!closed) failures += failure.toString() }
    }

    private fun response(method: String, params: JSONObject): JSONObject = when (method) {
        "mobile.host.status" -> JSONObject().put("mac_display_name", "Fixture Mac")
            .put("mac_device_id", "fixture-mac").put("capabilities", JSONArray().put("task.attachments.v1"))
        "mobile.workspace.list" -> JSONObject("""{
            "groups":[{"id":"complete","name":"Completed group","is_collapsed":false}],
            "workspaces":[
              {"id":"workspace-1","title":"Claude Code task","current_directory":"~/projects/cmux-app",
               "has_unread":true,"terminals":[{"id":"terminal-1","title":"Shell"}]},
              {"id":"workspace-2","title":"Read project","group_id":"complete",
               "has_unread":false,"terminals":[{"id":"terminal-2","title":"Shell"}]}
            ]} """)
        "mobile.task.attachment.upload" -> JSONObject().put("path", "/tmp/cmux fixture.txt")
        "notification.feed.list" -> JSONObject().put("notifications", JSONArray())
        "mobile.events.subscribe" -> JSONObject().put("stream_id", params.optString("stream_id"))
        "mobile.terminal.viewport" -> JSONObject().put("columns", params.optInt("viewport_columns", 40))
            .put("rows", params.optInt("viewport_rows", 20))
        "mobile.terminal.replay" -> {
            val columns = params.optInt("viewport_columns", 40)
            val rows = params.optInt("viewport_rows", 20)
            val spans = JSONArray()
            listOf("cmux Android terminal", "Colors and grid layout", "$ printf cmux", "cmux").forEachIndexed { row, line ->
                val value = line.take(columns)
                if (row < rows) spans.put(JSONObject().put("row", row).put("column", 0)
                    .put("text", value).put("cell_width", value.length).put("style_id", 0))
            }
            JSONObject().put("render_grid", JSONObject().put("format", "cmux.render-grid.v1")
                .put("surface_id", params.getString("surface_id")).put("columns", columns).put("rows", rows)
                .put("render_epoch", "fixture").put("render_revision", ++revision).put("full", true)
                .put("row_spans", spans).put("styles", JSONArray())
                .put("cursor", JSONObject().put("row", 4.coerceAtMost(rows - 1)).put("column", 0)
                    .put("visible", true).put("style", "block")))
        }
        else -> JSONObject()
    }

    override fun close() {
        closed = true
        server.close()
        sockets.forEach { runCatching { it.close() } }
        acceptThread.join(1000)
    }
}
