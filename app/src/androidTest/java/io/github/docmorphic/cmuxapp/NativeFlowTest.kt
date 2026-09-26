package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
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
        NativeCredentialStore(context).update {
            it.put("refresh_token", "emulator-fixture-only")
            it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465")
        }
    }

    @After fun cleanUp() {
        compose.activity.finish()
        peer.close()
        NativeCredentialStore(context).clear()
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
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "terminal.input" } }
        val input = peer.requests.filter { it.optString("method") == "terminal.input" }
        assertEquals(1, input.size)
        assertEquals("printf cmux\r", input.single().getJSONObject("params").getString("text"))
        compose.onNodeWithText("‹  2").performClick()
        compose.waitUntil(10_000) {
            peer.requests.any {
                it.optString("method") == "mobile.terminal.viewport" &&
                    it.getJSONObject("params").optBoolean("clear")
            }
        }
        assertTrue(peer.failures.toString(), peer.failures.isEmpty())
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
                        val result = response(request.optString("method"), request.optJSONObject("params") ?: JSONObject())
                        val envelope = JSONObject().put("id", request.getString("id"))
                            .put("ok", true).put("result", result)
                        socket.getOutputStream().write(MobileFrameCodec.encode(envelope.toString().toByteArray()))
                        socket.getOutputStream().flush()
                    }
                }
            }
        } catch (failure: Exception) { if (!closed) failures += failure.toString() }
    }

    private fun response(method: String, params: JSONObject): JSONObject = when (method) {
        "mobile.host.status" -> JSONObject().put("mac_display_name", "Fixture Mac")
            .put("mac_device_id", "fixture-mac").put("capabilities", JSONArray())
        "mobile.workspace.list" -> JSONObject("""{
            "groups":[{"id":"complete","name":"Completed group","is_collapsed":false}],
            "workspaces":[
              {"id":"workspace-1","title":"Claude Code task","current_directory":"~/projects/cmux-app",
               "has_unread":true,"terminals":[{"id":"terminal-1","title":"Shell"}]},
              {"id":"workspace-2","title":"Read project","group_id":"complete",
               "has_unread":false,"terminals":[{"id":"terminal-2","title":"Shell"}]}
            ]} """)
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
                .put("surface_id", "terminal-1").put("columns", columns).put("rows", rows)
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
