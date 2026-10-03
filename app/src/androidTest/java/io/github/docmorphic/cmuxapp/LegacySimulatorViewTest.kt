package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Base64
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.AnnotatedString
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class LegacySimulatorViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val panel = "abcdef01-2345-6789-abcd-ef0123456789"
    private val capabilities = setOf("simulator.stream.v1", "simulator.input.v1", "simulator.keepalive.v1")
    private fun descriptor(owner: String? = "test-connection", owned: Boolean? = true) = JSONObject()
        .put("panel_id", panel).put("workspace_id", "w").put("title", "Test Simulator").put("status", "ready")
        .put("is_ready", true).put("supports_touch", true).put("supports_keyboard", true)
        .put("supports_hardware_buttons", true).put("supports_rotation", true)
        .put("owner_connection_id", owner ?: JSONObject.NULL).put("is_owned_by_current_connection", owned ?: JSONObject.NULL)
    private fun model() = NativeSimulator.read(descriptor(), "w")!!
    private fun frame(seq: Int = 1, landscape: Boolean = false): JSONObject {
        val bitmap = Bitmap.createBitmap(if (landscape) 96 else 64, if (landscape) 64 else 96, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(if (landscape) Color.GREEN else Color.RED)
        val bytes = ByteArrayOutputStream()
        bitmap.compress(if (landscape) Bitmap.CompressFormat.JPEG else Bitmap.CompressFormat.PNG, 95, bytes)
        val result = JSONObject().put("panel_id", panel).put("seq", seq).put("format", if (landscape) "jpeg" else "png")
            .put("pixel_width", bitmap.width).put("pixel_height", bitmap.height).put("display_scale", 2)
            .put("data_base64", Base64.encodeToString(bytes.toByteArray(), Base64.NO_WRAP))
        bitmap.recycle(); return result
    }
    private inner class Endpoint(var startDescriptor: JSONObject = descriptor()) : LegacySimulatorEndpoint {
        override val events = MutableSharedFlow<MobileRpcClient.Event>(extraBufferCapacity = 32)
        var stream = ""
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        override suspend fun subscribe(streamId: String): JSONObject { stream = streamId; return JSONObject().put("stream_id", streamId) }
        override suspend fun unsubscribe(streamId: String) { assertEquals(stream, streamId); calls += "unsubscribe" to JSONObject() }
        override suspend fun request(method: String, params: JSONObject): JSONObject {
            calls += method to JSONObject(params.toString())
            if (method.endsWith(".start")) { emit("simulator.frame", frame()); return startDescriptor }
            return JSONObject()
        }
        fun emit(topic: String, value: JSONObject) { check(events.tryEmit(MobileRpcClient.Event(topic, value, stream))) }
        val inputs get() = calls.filter { it.first.startsWith("mobile.simulator.input.") }
    }
    private fun show(source: LegacySimulatorSource, online: () -> Boolean = { true }) {
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            LegacySimulatorPane(model(), source, capabilities, online())
        } } }
    }
    private fun capture(name: String, green: Boolean? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bounds = compose.onNodeWithTag("LegacySimulatorImage").fetchSemanticsNode().boundsInWindow
        var accepted: Bitmap? = null
        compose.waitUntil(5000) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            var matched = 0
            for (dx in -2..2) for (dy in -2..2) {
                val c = bitmap.getPixel(bounds.center.x.toInt() + dx * 12, bounds.center.y.toInt() + dy * 12)
                if (if (green == true) Color.green(c) > 180 && Color.red(c) < 70 else Color.red(c) > 180 && Color.green(c) < 70) matched++
            }
            if (green == null || matched >= 24) { accepted = bitmap; true } else { bitmap.recycle(); false }
        }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { accepted!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        accepted!!.recycle()
    }

    @Test fun pngAndJpegPaintAndLegacyGesturesButtonsTextAndRotationReachTheHost() {
        val endpoint = Endpoint(); show(LegacySimulatorSource { it(endpoint) })
        compose.onNodeWithText("Android Control").assertIsDisplayed()
        capture("legacy-simulator-png", false)
        compose.onNodeWithContentDescription("Home").performClick()
        compose.onNodeWithContentDescription("Lock").performClick()
        compose.onNodeWithTag("LegacySimulatorImage").performTouchInput { click(center) }
        compose.onNodeWithTag("LegacySimulatorImage").performTouchInput { down(center); moveBy(Offset(60f, 90f), 100); up() }
        compose.waitUntil(3000) { endpoint.inputs.count { it.first.endsWith(".pointer") } >= 4 }
        val pointers = endpoint.inputs.filter { it.first.endsWith(".pointer") }.map { it.second }
        assertEquals(listOf("tap", "began", "moved", "ended"), pointers.map { it.getString("phase") })
        assertEquals(.5, pointers[0].getDouble("x"), .02); assertEquals(.5, pointers[0].getDouble("y"), .02)
        compose.onNodeWithContentDescription("More Buttons").performClick()
        compose.onNodeWithText("Stream Quality").assertDoesNotExist()
        compose.onNodeWithText("App Switcher").performClick()
        compose.onNodeWithTag("LegacySimulatorText").performTextInput("héllo 🚀")
        compose.onNodeWithTag("LegacySimulatorText").performImeAction()
        compose.waitUntil(3000) { endpoint.inputs.any { it.first.endsWith(".text") } }
        assertEquals("héllo 🚀", endpoint.inputs.single { it.first.endsWith(".text") }.second.getString("text"))
        compose.onNodeWithTag("LegacySimulatorText").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.onNodeWithTag("LegacySimulatorText").assertIsNotFocused()
        assertEquals(listOf("home", "lock", "appSwitcher"), endpoint.inputs.filter { it.first.endsWith(".button") }.map { it.second.getString("button") })
        compose.onNodeWithTag("LegacySimulatorImage").performTouchInput { down(center); moveBy(Offset(40f, 50f), 100) }
        compose.runOnIdle { endpoint.emit("simulator.frame", frame(2, true)) }
        capture("legacy-simulator-jpeg-landscape", true)
        compose.onNodeWithTag("LegacySimulatorImage").performTouchInput { up() }
        compose.waitUntil(3000) { endpoint.inputs.count { it.second.optString("phase") == "ended" } == 2 }
        assertTrue(endpoint.inputs.all { it.second.getString("panel_id") == panel && it.second.getString("workspace_id") == "w" })
    }

    @Test fun viewOnlyAndLockedOwnershipDisableEveryInputAndClosedPanelClearsTheImage() {
        val endpoint = Endpoint(descriptor(null, null)); show(LegacySimulatorSource { it(endpoint) })
        compose.onNodeWithText("View Only").assertIsDisplayed(); capture("legacy-simulator-view-only", false)
        compose.onNodeWithContentDescription("Home").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Lock").assertIsNotEnabled()
        compose.onNodeWithContentDescription("More Buttons").assertIsNotEnabled()
        compose.onNodeWithTag("LegacySimulatorText").assertIsNotEnabled()
        compose.onNodeWithTag("LegacySimulatorImage").performTouchInput { click(center) }
        compose.runOnIdle { endpoint.emit("simulator.state", descriptor("other", false)) }
        compose.onNodeWithText("Simulator In Use").assertIsDisplayed(); capture("legacy-simulator-locked")
        assertTrue(endpoint.inputs.isEmpty())
        compose.runOnIdle { endpoint.emit("simulator.closed", JSONObject().put("panel_id", panel)) }
        compose.onNodeWithText("Simulator Unavailable").assertIsDisplayed()
        compose.waitUntil(3000) { endpoint.calls.any { it.first == "unsubscribe" } }
        assertFalse(endpoint.calls.any { it.first.endsWith(".stop") })
    }

    @Test fun backgroundAndReconnectCreateNewSessionsWithoutReplayingInput() {
        val endpoints = CopyOnWriteArrayList<Endpoint>(); var online by mutableStateOf(true)
        val source = LegacySimulatorSource { use -> Endpoint().also { endpoints += it }.let { use(it) } }
        show(source) { online }
        compose.onNodeWithText("Android Control").assertIsDisplayed(); capture("legacy-simulator-before-background", false)
        compose.onNodeWithContentDescription("Home").performClick()
        compose.waitUntil(3000) { endpoints.single().inputs.size == 1 }
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(3000) { endpoints.first().calls.any { it.first.endsWith(".stop") } }
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5000) { endpoints.size == 2 }
        compose.onNodeWithText("Android Control").assertIsDisplayed(); capture("legacy-simulator-foreground", false)
        assertTrue(endpoints.last().inputs.isEmpty())
        compose.runOnIdle { online = false }
        compose.onNodeWithText("Reconnecting").assertIsDisplayed()
        compose.onNodeWithContentDescription("Home").assertIsNotEnabled()
        compose.waitUntil(3000) { endpoints[1].calls.any { it.first == "unsubscribe" } }
        compose.runOnIdle { online = true }
        compose.waitUntil(5000) { endpoints.size == 3 }
        compose.onNodeWithText("Android Control").assertIsDisplayed(); capture("legacy-simulator-reconnected", false)
        assertTrue(endpoints.last().inputs.isEmpty())
    }

    @Test fun workspaceRouteHandoffWaitsForLegacyStopBeforeOpeningV2OnTheSameBorrowedConnection() {
        val incoming = Channel<ByteArray>(Channel.UNLIMITED); val stopGate = CompletableDeferred<Unit>()
        val observed = CopyOnWriteArrayList<String>(); val laneSent = CopyOnWriteArrayList<SimMessage>()
        val laneClosed = AtomicBoolean(); val laneIncoming = Channel<ByteArray>(Channel.UNLIMITED)
        var stream = ""
        val transport = object : MobileRpcTransport {
            override suspend fun connect() = Unit
            override suspend fun read() = incoming.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) {
                val request = JSONObject(MobileFrameDecoder().feed(bytes).single().toString(Charsets.UTF_8))
                val method = request.getString("method"); val params = request.getJSONObject("params")
                assertEquals("test-token", request.getJSONObject("auth").getString("stack_access_token"))
                observed += method
                val result = when (method) {
                    "mobile.events.subscribe" -> { stream = params.getString("stream_id"); JSONObject().put("stream_id", stream) }
                    "mobile.simulator.stream.start" -> descriptor()
                    "mobile.simulator.stream.stop" -> { stopGate.await(); JSONObject() }
                    else -> JSONObject()
                }
                incoming.send(MobileFrameCodec.encode(JSONObject().put("id", request.getString("id")).put("ok", true).put("result", result).toString().toByteArray()))
                if (method.endsWith(".start")) incoming.send(MobileFrameCodec.encode(JSONObject().put("kind", "event")
                    .put("topic", "simulator.frame").put("stream_id", stream).put("payload", frame()).toString().toByteArray()))
            }
            override val supportsSimulatorLanes = true
            override suspend fun openSimulator(panelId: String): SimStreamLane {
                assertEquals(panel, panelId); observed += "v2-open"
                return object : SimStreamLane {
                    override suspend fun read() = laneIncoming.receiveCatching().getOrNull()
                    override suspend fun write(bytes: ByteArray) { laneSent += SimStreamWire.decode(bytes.copyOfRange(4, bytes.size)) }
                    override fun close() { laneClosed.set(true); laneIncoming.close() }
                }
            }
            override fun close() { incoming.close(); laneIncoming.close() }
        }
        val base = MobileRpcClient(transport, { "test-token" }); runBlocking { base.connect() }; val lease = base.lease { }
        val descriptor = model(); val workspace = NativeWorkspace("w", "Simulator workspace", emptyList(), null, false, null, null,
            false, emptyList(), null, null, null, simulators = listOf(descriptor))
        var caps by mutableStateOf(capabilities); var mounted by mutableStateOf(true)
        try {
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                if (mounted) NativeSurfaceView(workspace, workspace.macSurfaces.single(), lease, caps, true,
                    onBack = { mounted = false }, onSurface = {}, onTerminal = {}, onBrowser = {})
            } } }
            compose.onNodeWithText("Android Control").assertIsDisplayed(); capture("legacy-simulator-workspace", false)
            compose.runOnIdle { caps = capabilities + SimStreamWire.CAPABILITY }
            compose.onNodeWithTag("SimulatorPane").assertExists()
            compose.waitUntil(3000) { "mobile.simulator.stream.stop" in observed }
            assertFalse("v2-open" in observed)
            stopGate.complete(Unit)
            compose.waitUntil(5000) { laneSent.any { it is SimMessage.Start } }
            assertTrue(observed.indexOf("mobile.events.unsubscribe") < observed.indexOf("v2-open"))
            assertEquals(1, observed.count { it == "mobile.simulator.stream.start" })
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            compose.waitUntil(3000) { laneClosed.get() }
            assertFalse(lease.isClosed); assertFalse(base.isClosed)
        } finally { stopGate.complete(Unit); lease.close(); base.close() }
    }

    @Test fun androidDecoderChecksActualImageDimensionsAndMimeBeforeAcceptingPixels() = runBlocking<Unit> {
        val png = LegacySimulatorFrame.read(frame(), panel)!!
        val jpeg = LegacySimulatorFrame.read(frame(2, true), panel)!!
        val red = decodeLegacySimulatorBitmap(png)!!; assertEquals(Color.RED, red.getPixel(20, 20)); red.recycle()
        val green = decodeLegacySimulatorBitmap(jpeg)!!; assertTrue(Color.green(green.getPixel(20, 20)) > 180); green.recycle()
        assertNull(decodeLegacySimulatorBitmap(png.copy(width = 8192)))
        assertNull(decodeLegacySimulatorBitmap(png.copy(format = "jpeg")))
        assertNull(decodeLegacySimulatorBitmap(jpeg.copy(format = "png")))
        assertNull(decodeLegacySimulatorBitmap(png.copy(base64 = "not an image")))
    }
}
