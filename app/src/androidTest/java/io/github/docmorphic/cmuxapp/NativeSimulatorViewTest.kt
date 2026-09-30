package io.github.docmorphic.cmuxapp

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean

class NativeSimulatorViewTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val preferences = "simulator-pane-test"
    private val panel = "abcdef01-2345-6789-abcd-ef0123456789"
    private fun fixture(name: String): Pair<SimMessage.Config, List<SimMessage.Frame>> {
        val context = InstrumentationRegistry.getInstrumentation().context
        val value = JSONObject(context.assets.open("simulator/$name.json").bufferedReader().use { it.readText() })
        fun bytes(value: String) = android.util.Base64.decode(value, android.util.Base64.DEFAULT)
        val sets = value.getJSONArray("parameter_sets"); val frames = value.getJSONArray("frames")
        return SimMessage.Config(if (name == "hevc") SimCodec.HEVC else SimCodec.H264,
            value.getLong("width"), value.getLong("height"), 2f, SimOrientation.PORTRAIT,
            value.getInt("nal_header_length"), List(sets.length()) { bytes(sets.getString(it)) }) to
            List(frames.length()) { i -> frames.getJSONObject(i).let {
                SimMessage.Frame(it.getLong("sequence").toULong(), if (it.getBoolean("keyframe")) 1 else 0,
                    (i * 100000).toULong(), bytes(it.getString("payload"))) } }
    }
    private inner class Source : SimLaneSource {
        val lanes = CopyOnWriteArrayList<Lane>()
        inner class Lane : SimStreamLane {
            val incoming = Channel<ByteArray>(Channel.UNLIMITED)
            val sent = CopyOnWriteArrayList<SimMessage>()
            val closed = AtomicBoolean()
            fun host(message: SimMessage) { check(incoming.trySend(SimStreamWire.encode(message)).isSuccess) }
            override suspend fun read() = incoming.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) {
                val value = SimStreamWire.decode(bytes.copyOfRange(4, bytes.size)); sent += value
                if (value is SimMessage.Start) {
                    val qualityChange = sent.filterIsInstance<SimMessage.Start>().size > 1
                    val data = fixture(if (qualityChange) "h264" else "hevc")
                    host(data.first); host(data.second[0]); if (!qualityChange) host(data.second[1])
                }
            }
            override fun close() { closed.set(true); incoming.close() }
        }
        override suspend fun use(block: suspend (SimStreamLane) -> Unit): Boolean {
            val lane = Lane(); lanes += lane
            try { block(lane); return true } finally { lane.close() }
        }
    }
    private fun resetPreferences() {
        InstrumentationRegistry.getInstrumentation().targetContext.getSharedPreferences(preferences, 0).edit().clear().commit()
    }
    private fun awaitVideo(source: Source) {
        try { compose.waitUntil(10000) { source.lanes.firstOrNull()?.sent?.filterIsInstance<SimMessage.Ack>()?.size == 2 } }
        catch (error: Throwable) {
            capture("simulator-pane-failed")
            throw AssertionError("Lanes: ${source.lanes.map { lane -> lane.closed.get() to lane.sent.map { it.javaClass.simpleName } }}\n" +
                compose.onRoot().printToString(), error)
        }
    }
    private fun capture(name: String, expectedGreen: Boolean? = null) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bounds = compose.onNodeWithTag("SimulatorVideo").fetchSemanticsNode().boundsInWindow
        var accepted: Bitmap? = null
        compose.waitUntil(5000) {
            val bitmap = instrumentation.uiAutomation.takeScreenshot()
            val cx = bounds.center.x.toInt(); val cy = bounds.center.y.toInt()
            var match = 0
            for (dy in -2..2) for (dx in -2..2) {
                val color = bitmap.getPixel(cx + dx * 12, cy + dy * 12)
                if (if (expectedGreen == true) Color.green(color) > 180 && Color.red(color) < 70
                    else Color.red(color) > 180 && Color.green(color) < 70) match++
            }
            if (expectedGreen == null || match >= 24) { accepted = bitmap; true }
            else { bitmap.recycle(); false }
        }
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        File(directory, "$name.png").outputStream().use { accepted!!.compress(Bitmap.CompressFormat.PNG, 100, it) }
        accepted!!.recycle()
    }

    @Test fun paneDisplaysVideoForwardsGesturesAndControlsAndRenegotiatesQuality() {
        resetPreferences()
        val source = Source(); var mounted by mutableStateOf(true)
        val calls = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        var selected = "a"
        val actions = SimulatorActions(true, true, "w", panel) { method, params ->
            calls += method to JSONObject(params.toString())
            if (method.endsWith(".select")) selected = params.getString("udid")
            JSONObject().put("devices", JSONArray().apply {
                for (id in listOf("a", "b")) put(JSONObject().put("udid", id).put("name", "iPhone $id")
                    .put("runtime_name", "iOS 26").put("family", "iPhone").put("state", "Booted").put("is_selected", id == selected))
            })
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            if (mounted) SimulatorPane(source, actions, true, preferences)
        } } }
        awaitVideo(source)
        capture("simulator-pane-streaming", true)
        compose.onNodeWithContentDescription("Home").performClick()
        compose.onNodeWithContentDescription("Lock").performClick()
        compose.onNodeWithTag("SimulatorVideo").performTouchInput {
            down(center); moveTo(Offset(width.toFloat() - 1, height.toFloat() - 1), 100); up()
        }
        compose.waitUntil(3000) { source.lanes.first().sent.filterIsInstance<SimMessage.Input>().flatMap { it.events }
            .filterIsInstance<SimInput.Touch>().lastOrNull()?.phase == SimTouchPhase.ENDED }
        val inputs = source.lanes.first().sent.filterIsInstance<SimMessage.Input>().flatMap { it.events }
        assertTrue(inputs.contains(SimInput.Button(SimButton.HOME))); assertTrue(inputs.contains(SimInput.Button(SimButton.LOCK)))
        val touch = inputs.filterIsInstance<SimInput.Touch>()
        assertEquals(SimTouchPhase.BEGAN, touch.first().phase); assertEquals(SimTouchPhase.ENDED, touch.last().phase)
        assertTrue(touch.all { it.pointer == 0 && it.x in 0f..1f && it.y in 0f..1f })
        compose.onNodeWithContentDescription("More Buttons").performClick()
        compose.onNodeWithText("Stream Quality").performClick()
        capture("simulator-pane-quality")
        compose.onNodeWithText("Balanced").performClick()
        compose.waitUntil(5000) { source.lanes.single().sent.filterIsInstance<SimMessage.Ack>().size == 3 }
        capture("simulator-pane-renegotiated", false)
        assertEquals(listOf(2000, 1280), source.lanes.single().sent.filterIsInstance<SimMessage.Start>().map { it.maximumLongSide })
        compose.onNodeWithContentDescription("More Buttons").performClick()
        compose.onNodeWithText("Switch Simulator").performClick()
        capture("simulator-pane-devices")
        compose.onNodeWithText("iPhone b · iOS 26").performClick()
        compose.waitUntil(3000) { calls.any { it.first == "mobile.simulator.device.select" } }
        compose.runOnIdle { source.lanes.single().host(SimMessage.State(SimHostStatus.WORKER_CRASHED, "private diagnostic")) }
        compose.onNodeWithText("Simulator Needs Recovery").assertIsDisplayed()
        compose.onNodeWithText("private diagnostic").assertDoesNotExist()
        capture("simulator-pane-recovery")
        compose.onNodeWithText("Recover").performClick()
        compose.waitUntil(5000) { source.lanes.size == 2 && source.lanes.last().sent.filterIsInstance<SimMessage.Ack>().size == 2 }
        assertTrue(source.lanes.first().closed.get())
        assertEquals(1, calls.count { it.first == "mobile.simulator.recover" })
        assertTrue(calls.all { it.second.getString("workspace_id") == "w" && it.second.getString("panel_id") == panel })
        assertEquals(1280, (source.lanes.last().sent.first() as SimMessage.Start).maximumLongSide)
        compose.onNodeWithTag("SimulatorText").performTextInput("héllo 🚀")
        compose.onNodeWithTag("SimulatorText").performImeAction()
        compose.waitUntil(3000) { source.lanes.last().sent.filterIsInstance<SimMessage.Input>().any { SimInput.Text("héllo 🚀") in it.events } }
        compose.onNodeWithTag("SimulatorText").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { mounted = false }
        compose.waitUntil(3000) { source.lanes.all { it.closed.get() } }
        compose.runOnIdle { mounted = true }
        compose.waitUntil(5000) { source.lanes.size == 3 && source.lanes.last().sent.filterIsInstance<SimMessage.Ack>().size == 2 }
        assertEquals(1280, (source.lanes.last().sent.first() as SimMessage.Start).maximumLongSide)
        compose.runOnIdle { mounted = false }
        compose.waitUntil(3000) { source.lanes.all { it.closed.get() } }
    }

    @Test fun disconnectKeepsPaneMountedAndReconnectionNeverReplaysStaleInput() {
        resetPreferences()
        val first = Source(); val second = Source(); var online by mutableStateOf(true)
        var source by mutableStateOf<SimLaneSource>(first)
        val actions = SimulatorActions(false, false, "w", panel) { _, _ -> fail("Unsupported mutation"); JSONObject() }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
            SimulatorPane(source.takeIf { online }, actions, online, preferences)
        } } }
        awaitVideo(first)
        compose.runOnIdle { online = false }
        compose.onNodeWithTag("SimulatorPane").assertExists()
        compose.onNodeWithContentDescription("Home").assertIsNotEnabled()
        compose.onNodeWithTag("SimulatorText").assertIsNotEnabled()
        compose.waitUntil(3000) { first.lanes.single().closed.get() }
        compose.runOnIdle { source = second; online = true }
        compose.waitUntil(8000) { second.lanes.firstOrNull()?.sent?.filterIsInstance<SimMessage.Ack>()?.size == 2 }
        capture("simulator-pane-reconnected", true)
        assertTrue(second.lanes.single().sent.none { it is SimMessage.Input })
        compose.onNodeWithContentDescription("Home").assertIsEnabled()
    }

    @Test fun workspaceSurfaceRouteOpensTheExactSimulatorThroughABorrowedRpcClient() {
        val source = Source(); val controls = Channel<ByteArray>()
        val transport = object : MobileRpcTransport {
            override suspend fun connect() = Unit
            override suspend fun read() = controls.receiveCatching().getOrNull()
            override suspend fun write(bytes: ByteArray) { error("Unexpected control request") }
            override val supportsSimulatorLanes = true
            override suspend fun openSimulator(panelId: String): SimStreamLane {
                assertEquals(panel, panelId)
                return source.Lane().also { source.lanes += it }
            }
            override fun close() { controls.close() }
        }
        val base = MobileRpcClient(transport, { "fixture-token" }); runBlocking { base.connect() }
        val lease = base.lease { }
        val descriptor = NativeSimulator(panel, "w", "Test Simulator", "iPhone", "Booted", "ready",
            true, true, true, true, true, null, null)
        val workspace = NativeWorkspace("w", "Simulator workspace", emptyList(), null, false, null, null,
            false, emptyList(), null, null, null, simulators = listOf(descriptor))
        var mounted by mutableStateOf(true)
        try {
            compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().systemBarsPadding()) {
                if (mounted) NativeSurfaceView(workspace, workspace.macSurfaces.single(), lease, setOf(SimStreamWire.CAPABILITY), true,
                    onBack = { mounted = false }, onSurface = { }, onTerminal = { }, onBrowser = { })
            } } }
            awaitVideo(source)
            compose.onNodeWithText("Test Simulator ▾").assertIsDisplayed()
            capture("simulator-workspace-route", true)
            compose.onNodeWithContentDescription("Back to workspaces").performClick()
            compose.waitUntil(3000) { source.lanes.single().closed.get() }
            assertFalse(lease.isClosed); assertFalse(base.isClosed)
        } finally { lease.close(); base.close() }
    }
}
