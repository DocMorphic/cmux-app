package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/** Production control + SSH adapter + Compose renderer. The in-process wire
 * fixture supplies PNG pixels; this is not a remote CDP/browser integration test. */
@OptIn(ExperimentalTestApi::class)
class SshBrowserStreamTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var pipe: Pipe
    private lateinit var control: SshCmuxControl
    private lateinit var stream: SshCmuxBrowserStream
    private lateinit var scope: CoroutineScope
    private var visible by mutableStateOf(true)
    private class Pipe : SshExecPipe {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED)
        override val output = incoming.receiveAsFlow()
        val sent = CopyOnWriteArrayList<JSONObject>()
        @Volatile var malformed = false
        @Volatile var sequence = 9L
        @Volatile var token = 40L
        private val png = Bitmap.createBitmap(100, 200, Bitmap.Config.ARGB_8888).let { bitmap ->
            bitmap.eraseColor(Color.rgb(20, 150, 100))
            val bytes = ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
            bitmap.recycle(); java.util.Base64.getEncoder().encodeToString(bytes)
        }
        fun feed(value: JSONObject) { check(incoming.trySend((value.toString() + "\n").toByteArray()).isSuccess) }
        fun state() = JSONObject().put("event", "browser-state").put("surface", 7).put("status", "live")
            .put("pointer_frame_seq", token).put("cols", 80).put("rows", 24)
            .put("url", "http://localhost:8080/").put("title", "SSH streamed fixture")
            .put("frame", JSONObject().put("seq", sequence).put("width", 100).put("height", 200)
                .put("image_width", if (malformed) 101 else 100).put("image_height", 200).put("data", png))
        override suspend fun write(bytes: ByteArray) {
            val request = JSONObject(bytes.toString(Charsets.UTF_8)); sent += request
            val data = when (request.getString("cmd")) {
                "identify" -> JSONObject().put("app", "cmux-tui").put("version", "fixture").put("protocol", 12).put("session", "fixture").put("pid", 123)
                    .put("capabilities", JSONArray(listOf("workspace-registry-v1", "attach-initial-size", "view-attachment-lease-v1",
                        "view-attachment-detach-v1", SshCmuxBrowserWire.CAPABILITY)))
                "attach-surface" -> { feed(state()); JSONObject().put("lease", "lease-${sent.size}") }
                "detach-attached-view" -> { feed(JSONObject().put("event", "detached").put("surface", 7)); JSONObject().put("outcome", "applied") }
                "get-cell-pixels" -> JSONObject().put("width_px", 8).put("height_px", 16)
                "resize-surface" -> { sequence++; token++; feed(state()); JSONObject().put("accepted", true) }
                else -> JSONObject()
            }
            feed(JSONObject().put("id", request.getString("id")).put("ok", true).put("data", data))
        }
        override fun close() { incoming.close() }
        fun requests(command: String) = sent.filter { it.getString("cmd") == command }
    }
    @Before fun setup() {
        pipe = Pipe(); scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val tree = SshCmuxInventory.parse(JSONObject("""{"generation":"owner-a","registry_id":"registry-a","workspaces":[
            {"id":1,"key":"workspace-a","name":"one","screens":[{"id":2,"panes":[{"id":3,"tabs":[
            {"surface":7,"kind":"browser","tab_resource_id":"tab_a","content_resource_id":"brw_a","title":"Browser"}]}]}]}]}"""))
        runBlocking(Dispatchers.Main) {
            control = SshCmuxControl(pipe, scope); control.handshake("fixture")
            stream = SshCmuxBrowserStream(SshCmuxBrowserSelection.capture("fixture", tree, tree.workspaces.single(), tree.tabs.single()),
                control, scope, { tree.tabs.single() }, { true })
        }
    }
    @After fun cleanup() {
        val logs = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots/ssh-stream-commands.txt")
        logs.parentFile?.mkdirs(); logs.appendText(pipe.sent.joinToString("\n") + "\n---\n")
        compose.activity.finish()
        runBlocking(Dispatchers.Main) { stream.close(); control.close(); scope.cancel() }
    }
    private fun show() {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            if (visible) NativeBrowserView(stream, stream.panelId, "Browser", onBack = { visible = false })
        } } }
        compose.waitUntil(15_000) { compose.onAllNodesWithText("SSH streamed fixture").fetchSemanticsNodes().isNotEmpty() }
    }
    private fun awaitPresentation() {
        compose.waitForIdle()
        compose.waitUntil(10_000) { pipe.requests("browser-frame-presented").lastOrNull()?.optLong("frame_seq") == pipe.token }
        compose.waitForIdle()
    }
    @Test fun decodedPixelsAreVisibleBeforeGuardedTapAndExitDetachesWithoutClosingControl() {
        show()
        awaitPresentation()
        val presented = pipe.requests("browser-frame-presented").last().getLong("frame_seq")
        assertTrue(pipe.requests("attach-surface").single().getInt("cols") > 1)
        assertTrue(pipe.requests("attach-surface").single().getInt("rows") > 1)
        val page = compose.onNodeWithContentDescription("SSH browser page").assertIsDisplayed()
        val pixels = page.captureToImage().toPixelMap()
        val pixel = pixels[pixels.width / 2, pixels.height / 2]
        assertEquals(20 / 255f, pixel.red, 0.03f); assertEquals(150 / 255f, pixel.green, 0.03f)
        page.performTouchInput { click(center) }
        compose.waitUntil(10_000) { pipe.requests("browser-mouse-guarded").size == 2 }
        val clicks = pipe.requests("browser-mouse-guarded")
        assertEquals(listOf("down", "up"), clicks.map { it.getString("kind") })
        assertTrue(clicks.all { it.getLong("frame_seq") == presented })
        assertEquals(50.0, clicks[0].getDouble("x_px"), 2.0)
        assertEquals(100.0, clicks[0].getDouble("y_px"), 2.0)
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screenshots/ssh-stream-browser.png")
        screenshot.parentFile?.mkdirs()
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            screenshot.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
        compose.onNodeWithText("‹  Workspaces").performClick()
        compose.waitUntil(10_000) { pipe.requests("detach-attached-view").size == 1 }
        assertFalse(control.closed)
    }
    @Test fun malformedPixelsNeverAcknowledgeThenFreshFrameEnablesNavigation() {
        pipe.malformed = true; show()
        compose.waitForIdle()
        compose.onNodeWithContentDescription("SSH browser page").assertDoesNotExist()
        assertTrue(pipe.requests("browser-frame-presented").isEmpty())
        pipe.malformed = false; pipe.sequence = 10; pipe.token = 41; pipe.feed(pipe.state())
        awaitPresentation()
        assertTrue(pipe.requests("browser-frame-presented").all { it.getLong("frame_seq") >= 41L })
        compose.onNodeWithContentDescription("Browser Back").assertIsEnabled().performClick()
        compose.waitUntil(10_000) { pipe.requests("browser-back").size == 1 }
        compose.onNodeWithContentDescription("Browser address").performClick().performTextReplacement("http://localhost:8080/next")
        compose.onNodeWithContentDescription("Browser address").performImeAction()
        compose.waitUntil(10_000) { pipe.requests("browser-navigate").size == 1 }
        assertEquals("http://localhost:8080/next", pipe.requests("browser-navigate").single().getString("url"))
    }
    @Test fun backgroundDetachAndForegroundAcceptLowerSequenceFromANewAttachment() {
        show(); awaitPresentation()
        val oldToken = pipe.requests("browser-frame-presented").last().getLong("frame_seq")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(10_000) { pipe.requests("detach-attached-view").size == 1 }
        pipe.sequence = 1; pipe.token = 2
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        awaitPresentation()
        val newToken = pipe.requests("browser-frame-presented").last().getLong("frame_seq")
        assertTrue(newToken < oldToken)
        compose.onNodeWithContentDescription("SSH browser page").assertIsDisplayed().performTouchInput { click(center) }
        compose.waitUntil(10_000) { pipe.requests("browser-mouse-guarded").size == 2 }
        assertTrue(pipe.requests("browser-mouse-guarded").all { it.getLong("frame_seq") == newToken })
        assertEquals(2, pipe.requests("attach-surface").size)
    }
}
