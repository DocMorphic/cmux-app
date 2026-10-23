package io.github.docmorphic.cmuxapp

import android.graphics.Rect
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Real wide emulator window, production reader and platform dialog input. */
class AgentFeedFullTextPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        val configuration = compose.activity.resources.configuration
        check(configuration.screenWidthDp >= 600 && configuration.screenHeightDp >= 600)
        val orientation = InstrumentationRegistry.getArguments().getString("cmux_presentation_orientation")
        if (orientation != null) {
            check(orientation in listOf("portrait", "landscape"))
            val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
            if (orientation == "landscape") device.setOrientationLeft() else device.setOrientationNatural()
            compose.waitUntil(15_000) {
                val current = compose.activity.resources.configuration
                (current.screenWidthDp > current.screenHeightDp) == (orientation == "landscape")
            }
        }
    }
    @After fun finish() {
        compose.activity.finish()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).apply { setOrientationNatural(); unfreezeRotation() }
    }

    @Test fun wideWindowCentersTheReaderAndOutsideTapAndBackDismissIt() {
        var shown by mutableStateOf(true)
        var dismissals = 0
        var reader: NativeMarkdownList? = null
        compose.setContent { CmuxTheme {
            if (shown) AgentFeedFullText({ "# Wide reader\n\nSelectable native body." }, {}, { dismissals++; shown = false })
        } }
        fun awaitReader() {
            compose.waitUntil(15_000) {
                compose.runOnUiThread {
                    reader = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull {
                        it.findViewWithTag<NativeMarkdownList>("AgentFeedNativeMarkdown")?.takeIf { view ->
                            view.isShown && !view.released && view.hasWindowFocus()
                        }
                    }
                }
                reader != null
            }
        }
        awaitReader()
        compose.onNodeWithTag("AgentFeedFullTextPanel").assertIsDisplayed()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        val density = compose.activity.resources.displayMetrics.density
        val bounds = Rect()
        compose.runOnUiThread {
            assertTrue(reader!!.getGlobalVisibleRect(bounds))
            val location = IntArray(2)
            reader!!.getLocationOnScreen(location)
            bounds.set(location[0], location[1], location[0] + reader!!.width, location[1] + reader!!.height)
        }
        val metrics = compose.activity.windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val usable = Rect(metrics.bounds).apply {
            left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom
        }
        val orientation = if (device.displayWidth > device.displayHeight) "landscape" else "portrait"
        val folder = File(compose.activity.getExternalFilesDir(null), "feed-reader-presentation/$orientation").apply { mkdirs() }
        val record = JSONObject().put("bodyBoundsPx", bounds.toString()).put("windowBoundsPx", metrics.bounds.toString())
            .put("usableBoundsPx", usable.toString()).put("windowInsetsPx", insets.toString())
            .put("screenPx", listOf(device.displayWidth, device.displayHeight)).put("density", density)
        File(folder, "geometry.json").writeText(record.toString(2))
        assertTrue(device.takeScreenshot(File(folder, "wide-loaded.png")))
        assertEquals("Source panel width is 580 points; use dp on Android", 580f, bounds.width() / density, 1f)
        assertEquals("Native body centers between the actual system bars and camera cutout", usable.exactCenterX(), bounds.exactCenterX(), 2f)
        assertTrue("A centered panel leaves space below it", bounds.bottom < device.displayHeight - 100 * density)
        val panelTop = bounds.top - 64 * density // Source wide panel: 10dp drag area + 54dp toolbar.
        assertEquals("Panel centers inside the platform's usable area", usable.exactCenterY(),
            (panelTop + bounds.bottom) / 2, 2f)
        assertTrue(device.click(usable.left + 8, usable.centerY()))
        compose.waitUntil(5_000) { dismissals == 1 }
        compose.onNodeWithTag("AgentFeedFullTextPanel").assertDoesNotExist()
        compose.runOnUiThread { assertTrue(reader!!.released) }
        compose.runOnIdle { shown = true; reader = null }
        awaitReader()
        record.put("backInjectionReturned", device.pressBack())
        compose.waitUntil(5_000) { dismissals == 2 }
        compose.onNodeWithTag("AgentFeedFullTextPanel").assertDoesNotExist()
        File(folder, "result.json").writeText(record.put("dismissals", dismissals)
            .put("scope", "Owned wide API-36 emulator, actual production native reader and platform outside-tap/Back input. Not iOS materials, transport, Pixel/Mac, TalkBack or full parity acceptance.").toString(2))
    }
}
