package io.github.docmorphic.cmuxapp

import android.app.Activity
import android.app.Application
import android.content.res.Configuration
import android.widget.TextView
import androidx.test.uiautomator.UiDevice
import android.graphics.Bitmap
import android.os.Bundle
import android.os.Parcel
import android.text.Selection
import android.text.Spannable
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.test.StandardTestDispatcher
import org.json.JSONArray
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Real routed NativeScreen + Activity recreation + local socket RPC; emulator only. */
@OptIn(ExperimentalTestApi::class)
class AgentFeedReaderActivityTest {
    @get:Rule val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val output get() = File(context.getExternalFilesDir(null),
        if (adaptiveChanges) "feed-reader-adaptive" else if (systemChanges) "feed-reader-system-config" else "feed-reader-activity").apply { mkdirs() }
    private var phase = "initial-load"
    private var systemChanges = false
    private var adaptiveChanges = false
    private val device get() = UiDevice.getInstance(instrumentation)
    @Before fun emulatorOnly() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        Assume.assumeTrue("Final post-save callback requires API 29+", android.os.Build.VERSION.SDK_INT >= 29)
    }

    private fun reader() = WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull {
        it.findViewWithTag<NativeMarkdownList>("AgentFeedNativeMarkdown")?.takeIf { v -> v.isAttachedToWindow && v.isShown }
    }
    private fun paragraph() = reader()?.let { r -> (0 until r.childCount).mapNotNull { r.getChildAt(it) as? NativeMarkdownRow }
        .firstOrNull { it.ready && it.block?.index == 1 }?.body }
    private fun await(test: () -> Boolean) {
        try { compose.waitUntil(25_000) { var ready = false; compose.runOnUiThread { ready = test() }; ready } }
        catch (error: Throwable) {
            val record = JSONObject().put("phase", phase)
            compose.runOnUiThread {
                record.put("readerPresent", reader() != null)
                    .put("firstVisible", reader()?.firstVisiblePosition).put("bodyLength", paragraph()?.text?.length)
                    .put("selection", paragraph()?.selectionStart).put("anchor", paragraph()?.let { anchor() })
            }
            File(output, "$phase-timeout.json").writeText(record.toString(2))
            val image = instrumentation.uiAutomation.takeScreenshot()
            File(output, "$phase-timeout.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
            throw error
        }
    }
    private fun settle() {
        compose.waitForIdle()
        val latch = CountDownLatch(1)
        compose.runOnUiThread {
            val view = reader()!!
            view.postOnAnimation { view.postOnAnimation { latch.countDown() } }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS)); instrumentation.waitForIdleSync()
    }
    private fun anchor(): Int {
        val body = paragraph()!!; val r = reader()!!; val row = body.parent as NativeMarkdownRow
        val y = (r.paddingTop - row.top - body.top - body.totalPaddingTop).coerceAtLeast(0)
        return body.layout.getLineStart(body.layout.getLineForVertical(y))
    }
    private fun capture(name: String) {
        settle()
        compose.runOnUiThread {
            val view = reader()!!
            val body = paragraph()
            val record = JSONObject().put("phase", phase).put("orientation", view.resources.configuration.orientation)
                .put("fontScale", view.resources.configuration.fontScale).put("readerWidth", view.width).put("readerHeight", view.height)
                .put("textPixels", body?.textSize).put("selectionStart", body?.selectionStart).put("selectionEnd", body?.selectionEnd)
                .put("lineCount", body?.lineCount)
            paragraph()?.let { record.put("formattedAnchor", anchor()) }
            File(output, "$name-frame.json").writeText(record.toString(2))
        }
        val image = instrumentation.uiAutomation.takeScreenshot()
        File(output, "$name.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
    }

    @Test fun routedNativeReaderReloadsAndRestoresThroughActualActivityRecreation() = routedReader(false)

    @Test fun actualRotationAndSystemFontChangeRetainRoutedReaderPositionSelectionAndSession() = routedReader(true)

    @Test fun actualCompactWideCompactWindowChangesRetainPositionSelectionAndSession() = routedReader(true, true)

    private fun topLineContains(body: TextView, scrollY: Int, character: Int) {
        val line = body.layout.getLineForVertical((scrollY - body.totalPaddingTop).coerceAtLeast(0))
        assertTrue("Original character $character stays in top visible line [${body.layout.getLineStart(line)}, ${body.layout.getLineEnd(line)})",
            character in body.layout.getLineStart(line) until body.layout.getLineEnd(line))
    }

    private fun changeConfiguration(scenario: ActivityScenario<NativeLifecycleTestActivity>, font: Boolean) {
        var previous: NativeLifecycleTestActivity? = null
        scenario.onActivity { previous = it }
        if (font) device.executeShellCommand("settings put system font_scale 2.0") else device.setOrientationLeft()
        compose.waitUntil(20_000) { runCatching {
            var changed = false
            scenario.onActivity { changed = it !== previous && if (font) kotlin.math.abs(it.resources.configuration.fontScale - 2f) < .001f
                else it.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE }
            changed
        }.getOrDefault(false) }
        assertTrue(previous!!.isDestroyed)
    }

    private fun changeWindowClass(scenario: ActivityScenario<NativeLifecycleTestActivity>, wide: Boolean,
        expectedDensity: Float, resize: () -> Unit) {
        var previous: NativeLifecycleTestActivity? = null
        scenario.onActivity { previous = it }
        resize()
        compose.waitUntil(20_000) { runCatching {
            var changed = false
            scenario.onActivity {
                val configuration = it.resources.configuration
                val regular = configuration.screenWidthDp >= 600 && configuration.screenHeightDp >= 600
                changed = it !== previous && regular == wide &&
                    kotlin.math.abs(it.resources.displayMetrics.density - expectedDensity) < .001f
            }
            changed
        }.getOrDefault(false) }
        assertTrue(previous!!.isDestroyed)
    }

    private fun routedReader(withSystemChanges: Boolean, withAdaptiveChanges: Boolean = false) {
        systemChanges = withSystemChanges
        adaptiveChanges = withAdaptiveChanges
        val originalFontSetting = device.executeShellCommand("settings get system font_scale").trim()
        val originalDensity = context.resources.displayMetrics.density
        val originalWindow = listOf("size", "density").associateWith { key ->
            device.executeShellCommand("wm $key").trim()
        }
        fun restoreWindow() {
            for ((key, value) in originalWindow) {
                val override = value.lineSequence().firstOrNull { it.startsWith("Override ") }?.substringAfter(": ")
                require(override == null || override.matches(Regex(if (key == "size") "[0-9]+x[0-9]+" else "[0-9]+")))
                device.executeShellCommand("wm $key ${override ?: "reset"}")
            }
        }
        require(originalFontSetting == "null" || originalFontSetting.toFloatOrNull()?.let { it.isFinite() && it > 0 } == true)
        if (systemChanges) assertEquals("Controlled owned emulator starts at normal font", "1.0", originalFontSetting)
        val store = NativeCredentialStore(context); val peer = NativeFixturePeer()
        val preferences = context.getSharedPreferences("cmux-display", android.content.Context.MODE_PRIVATE)
        val hadFeedPreference = preferences.contains(NativeDisplayPreferences.feedReplacesNotificationsKey)
        val previousFeedPreference = preferences.getBoolean(NativeDisplayPreferences.feedReplacesNotificationsKey, true)
        val marker = "UNSAVED_FEED_BODY_SENTINEL_36af"
        val text = "# Activity reader\n\n" + (1..4000).joinToString("\n") { "Row $it: $marker with **bold** reading content." } + "\n\n## Reader end"
        val rows = JSONArray().put(JSONObject().put("id", "reader-event").put("workstream_id", "reader-turn")
            .put("source", "Claude").put("kind", "stop").put("status", "telemetry").put("created_at", 198).put("updated_at", 198)
            .put("title", "Lifecycle report").put("reason", "A report preview").put("full_text_truncated", true)
            .put("workspace_id", "workspace-1").put("surface_id", "terminal-1"))
        val textBytes = text.toByteArray(Charsets.UTF_8)
        peer.agentFeedResponse = { method, params -> when (method) {
            "feed.list" -> JSONObject().put("revision", 1).put("items", rows)
            "feed.text" -> {
                assertEquals("reader-event", params.getString("item_id"))
                val start = params.optInt("offset"); val end = (start + 16_384).coerceAtMost(textBytes.size)
                JSONObject().put("text", textBytes.copyOfRange(start, end).toString(Charsets.UTF_8)).put("version", 1).apply {
                    if (end < textBytes.size) put("next_offset", end)
                }
            }
            else -> JSONObject()
        } }
        val saved = CopyOnWriteArrayList<ByteArray>()
        val callbacks = object : Application.ActivityLifecycleCallbacks {
            override fun onActivityPostSaveInstanceState(activity: Activity, state: Bundle) {
                // Snapshot final bytes now: the next Activity consumes/removes
                // restored providers from this same in-memory Bundle later.
                if (activity is NativeLifecycleTestActivity) {
                    val parcel = Parcel.obtain()
                    try { state.writeToParcel(parcel, 0); saved.add(parcel.marshall()) }
                    finally { parcel.recycle() }
                }
            }
            override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
            override fun onActivityCreated(a: Activity, b: Bundle?) {}
            override fun onActivityStarted(a: Activity) {}
            override fun onActivityResumed(a: Activity) {}
            override fun onActivityPaused(a: Activity) {}
            override fun onActivityStopped(a: Activity) {}
            override fun onActivityDestroyed(a: Activity) {}
        }
        val application = context.applicationContext as Application
        application.registerActivityLifecycleCallbacks(callbacks)
        try {
            preferences.edit().putBoolean(NativeDisplayPreferences.feedReplacesNotificationsKey, true).commit()
            store.clear(); store.update { it.put("refresh_token", "reader-activity-emulator-fixture") }
            store.rememberMac("cmux-ios://attach?v=2&r=100.64.0.1:58465", "fixture-mac", "Fixture Mac")
            store.update { it.put("pairing_code", "cmux-ios://attach?v=2&r=100.64.0.1:58465") }
            NativeLifecycleTestActivity.connector = NativeConnector { _, _ ->
                MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture-token" }).also { it.connect() }
            }
            var client: MobileRpcClient? = null
            ActivityScenario.launch(NativeLifecycleTestActivity::class.java).use { scenario ->
                compose.waitUntil(20_000) { compose.onAllNodes(hasText("Feed", substring = true) and isSelectable()).fetchSemanticsNodes().isNotEmpty() }
                compose.onNode(hasText("Feed", substring = true) and isSelectable()).performClick()
                compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("See more", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }
                compose.onNodeWithContentDescription("See more", useUnmergedTree = true).performClick()
                await { paragraph()?.text?.contains(marker) == true }; settle()
                var old: NativeLifecycleTestActivity? = null
                scenario.onActivity { old = it; client = ViewModelProvider(it).get(NativeFeedSession::class.java).connectionState("").client }
                val retainedClient = checkNotNull(client) { "Routed Feed must have an admitted foreground RPC client" }
                assertFalse(retainedClient.isClosed)
                var before = 0; var selected = 0
                var formattedPixelsBefore = 0f; var portraitWidth = 0
                compose.runOnUiThread {
                    reader()!!.setSelectionFromTop(1, -30_000)
                }
                settle(); compose.runOnUiThread {
                    before = anchor(); assertTrue(before > 1000); selected = before + 4
                    formattedPixelsBefore = paragraph()!!.textSize; portraitWidth = reader()!!.width
                    val body = paragraph()!!; Selection.setSelection(body.text as Spannable, selected, selected + 8)
                    assertEquals(selected, body.selectionStart)
                }
                capture("formatted-before")
                compose.runOnUiThread { assertEquals(selected, paragraph()!!.selectionStart) }
                val reads = peer.requests.count { it.optString("method") == "feed.text" }
                phase = "formatted-recreate"
                if (adaptiveChanges) changeWindowClass(scenario, true, 2f) {
                    device.executeShellCommand("wm size 2048x2732")
                    device.executeShellCommand("wm density 320")
                } else if (systemChanges) changeConfiguration(scenario, font = false) else scenario.recreate()
                await { paragraph()?.selectionStart == selected && reader()!!.firstVisiblePosition == 1 }; settle()
                var after = 0
                compose.runOnUiThread {
                    after = anchor()
                    if (systemChanges) {
                        if (adaptiveChanges) {
                            assertEquals("Real regular window uses the measured 580dp panel", 580f,
                                reader()!!.width / reader()!!.resources.displayMetrics.density, 1f)
                        } else assertTrue("Real landscape has a wider reader", reader()!!.width > portraitWidth)
                        val row = paragraph()!!.parent as NativeMarkdownRow
                        topLineContains(paragraph()!!, reader()!!.paddingTop - row.top - paragraph()!!.top, before)
                    } else assertEquals(before, after)
                }
                if (adaptiveChanges) {
                    compose.onNodeWithTag("AgentFeedFullTextPanel").assertIsDisplayed()
                    compose.onNodeWithTag("AgentFeedFullTextCompactPanel").assertDoesNotExist()
                }
                scenario.onActivity {
                    assertNotSame(old, it); assertTrue(old!!.isDestroyed)
                    assertSame(retainedClient, ViewModelProvider(it).get(NativeFeedSession::class.java).connectionState("").client)
                    assertFalse(retainedClient.isClosed)
                }
                assertTrue(peer.requests.count { it.optString("method") == "feed.text" } > reads)
                capture("formatted-restored")
                phase = "second-native-recreate"
                val secondAnchor = after
                val secondReads = peer.requests.count { it.optString("method") == "feed.text" }
                if (adaptiveChanges) changeWindowClass(scenario, false, originalDensity, ::restoreWindow)
                else if (systemChanges) changeConfiguration(scenario, font = true) else scenario.recreate()
                await { paragraph()?.selectionStart == selected }; settle()
                compose.runOnUiThread {
                    val body = paragraph()!!; val row = body.parent as NativeMarkdownRow
                    if (systemChanges) {
                        if (!adaptiveChanges) assertTrue(body.textSize > formattedPixelsBefore)
                        topLineContains(body, reader()!!.paddingTop - row.top - body.top, secondAnchor)
                    } else assertEquals(before, anchor())
                }
                if (adaptiveChanges) {
                    compose.onNodeWithTag("AgentFeedFullTextCompactPanel").assertIsDisplayed()
                    compose.onNodeWithTag("AgentFeedFullTextPanel").assertDoesNotExist()
                }
                scenario.onActivity { assertSame(retainedClient, ViewModelProvider(it).get(NativeFeedSession::class.java).connectionState("").client) }
                assertTrue(peer.requests.count { it.optString("method") == "feed.text" } > secondReads)
                compose.onNodeWithText("Source").assertDoesNotExist(); compose.onNodeWithText("Formatted").assertDoesNotExist()
                capture("second-native-restored")
                assertTrue("Actual saved Activity bundles", saved.size >= 2)
                val bytes = JSONArray()
                val registryKeys = JSONArray()
                saved.forEachIndexed { index, raw ->
                    File(output, "activity-state-$index.bin").writeBytes(raw)
                    val parcel = Parcel.obtain()
                    val bundle = try {
                        parcel.unmarshall(raw, 0, raw.size); parcel.setDataPosition(0)
                        Bundle.CREATOR.createFromParcel(parcel).apply { classLoader = context.classLoader }
                    } finally { parcel.recycle() }
                    val state = checkNotNull(bundle.getBundle("androidx.lifecycle.BundlableSavedStateRegistry.key"))
                    val keys = state.keySet()
                    File(output, "activity-state-$index-keys.json").writeText(JSONArray(keys.sorted()).toString(2))
                    assertTrue("Final saved Compose registry is present", keys.any { "SaveableStateRegistry" in it })
                    registryKeys.put(JSONArray(keys.sorted()))
                    assertTrue("Only coordinates/identity belong in saved state: ${raw.size}", raw.size < 64_000)
                    assertFalse(raw.toString(Charsets.UTF_8).contains(marker)); assertFalse(raw.toString(Charsets.UTF_16LE).contains(marker))
                    bytes.put(raw.size)
                }
                File(output, "result.json").writeText(JSONObject().put("bodyCharacters", text.length).put("savedStateBytes", bytes)
                    .put("savedRegistryKeys", registryKeys).put("savedStateCapture", "Application.onActivityPostSaveInstanceState")
                    .put("formattedBeforeAnchor", before).put("formattedAfterAnchor", after).put("formattedSelection", selected)
                    .put("actualSystemRotationAndFontChange", systemChanges && !adaptiveChanges)
                    .put("actualCompactWideCompactWindowChanges", adaptiveChanges)
                    .put("originalWindowConfiguration", JSONObject(originalWindow))
                    .put("textReads", peer.requests.count { it.optString("method") == "feed.text" }).put("retainedRpcClient", true)
                    .put("scope", "Actual Activity recreation of routed production Feed using emulator-local credentials and socket RPC; system changes only when explicitly recorded; no process death, real authentication, Mac/Pixel, TalkBack or full parity acceptance.").toString(2))
            }
            assertTrue("Finishing the Activity releases its admitted client", client!!.isClosed)
        } finally {
            if (systemChanges) {
                if (adaptiveChanges) restoreWindow()
                device.executeShellCommand(if (originalFontSetting == "null") "settings delete system font_scale" else "settings put system font_scale $originalFontSetting")
                device.setOrientationNatural(); device.unfreezeRotation()
            }
            application.unregisterActivityLifecycleCallbacks(callbacks); NativeLifecycleTestActivity.connector = null; peer.close(); store.clear()
            context.getSharedPreferences("native_agent_feed_read", android.content.Context.MODE_PRIVATE).edit().clear().commit()
            val edit = preferences.edit()
            if (hadFeedPreference) edit.putBoolean(NativeDisplayPreferences.feedReplacesNotificationsKey, previousFeedPreference)
            else edit.remove(NativeDisplayPreferences.feedReplacesNotificationsKey)
            edit.commit()
        }
    }
}
