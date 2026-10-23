package io.github.docmorphic.cmuxapp

import android.graphics.Rect
import android.view.inspector.WindowInspector
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import org.json.JSONObject
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Actual compact landscape window, including the platform's camera and system-bar insets. */
class AgentFeedFullTextCompactPresentationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @Before fun emulatorOnly() {
        check(android.os.Build.MODEL.contains("sdk") || android.os.Build.FINGERPRINT.contains("generic"))
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val rotation = InstrumentationRegistry.getArguments().getString("cmux_reader_rotation") ?: "left"
        check(rotation in listOf("left", "right"))
        if (rotation == "left") device.setOrientationLeft() else device.setOrientationRight()
        try {
            compose.waitUntil(15_000) {
                val configuration = compose.activity.resources.configuration
                configuration.screenWidthDp > 640 && configuration.screenHeightDp < 600
            }
        } finally {
            val configuration = compose.activity.resources.configuration
            val folder = File(compose.activity.getExternalFilesDir(null), "feed-reader-compact").apply { mkdirs() }
            File(folder, "orientation-setup.json").writeText(JSONObject().put("widthDp", configuration.screenWidthDp)
                .put("heightDp", configuration.screenHeightDp).put("orientation", configuration.orientation)
                .put("deviceWidthPx", device.displayWidth).put("deviceHeightPx", device.displayHeight).toString(2))
        }
    }
    @After fun finish() {
        compose.activity.finish()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).apply { setOrientationNatural(); unfreezeRotation() }
    }

    @Test fun landscapeReaderSpansTheWindowAndKeepsControlsInsideSafeInsets() {
        var shown by mutableStateOf(true)
        var dismissals = 0
        val requestedTheme = InstrumentationRegistry.getArguments().getString("cmux_reader_theme")
        check(requestedTheme == null || requestedTheme in listOf("dark", "light"))
        val requestedDirection = InstrumentationRegistry.getArguments().getString("cmux_reader_direction") ?: "ltr"
        check(requestedDirection in listOf("ltr", "rtl"))
        compose.setContent { CmuxTheme(darkTheme = when (requestedTheme) {
            "dark" -> true
            "light" -> false
            else -> androidx.compose.foundation.isSystemInDarkTheme()
        }) {
            CompositionLocalProvider(LocalLayoutDirection provides if (requestedDirection == "rtl") LayoutDirection.Rtl else LayoutDirection.Ltr) {
                if (shown) AgentFeedFullText({ "# Landscape reader\n\nSelectable native body." }, {}, { dismissals++; shown = false })
            }
        } }
        var reader: NativeMarkdownList? = null
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
        val folder = File(compose.activity.getExternalFilesDir(null), "feed-reader-compact").apply { mkdirs() }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val device = UiDevice.getInstance(instrumentation)
        assertTrue(device.takeScreenshot(File(folder, "landscape-loaded.png")))
        fun barAppearance(): JSONObject {
            val record = JSONObject()
            compose.runOnUiThread {
                val view = reader!!
                val flags = checkNotNull(view.windowInsetsController).systemBarsAppearance
                record.put("flags", flags).put("lightStatusBars", flags and WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS != 0)
                    .put("lightNavigationBars", flags and WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS != 0)
                    .put("foreground", view.colors.foreground).put("focused", view.hasWindowFocus())
                    .put("systemNightMode", view.resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK)
                var parent: android.view.View? = view
                val ancestry = org.json.JSONArray()
                while (parent != null) {
                    ancestry.put(parent.javaClass.name)
                    parent = parent.parent as? android.view.View
                }
                record.put("viewAncestry", ancestry)
            }
            return record
        }
        val initialBars = barAppearance()
        val density = compose.activity.resources.displayMetrics.density
        val metrics = compose.activity.windowManager.currentWindowMetrics
        val insets = metrics.windowInsets.getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout())
        val usable = Rect(metrics.bounds).apply {
            left += insets.left; top += insets.top; right -= insets.right; bottom -= insets.bottom
        }
        val panel = compose.onNodeWithTag("AgentFeedFullTextCompactPanel").fetchSemanticsNode().boundsInRoot
        assertEquals("Compact reader spans the window, beyond Material's default 640dp cap", metrics.bounds.width().toFloat(), panel.width, 2f)
        compose.onNodeWithTag("AgentFeedFullTextPanel").assertDoesNotExist()
        val drag = compose.onNodeWithTag("AgentFeedFullTextDragArea", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals("Original compact landscape drag area is 24 points", 24f, drag.height / density, .5f)
        val header = compose.onNodeWithTag("AgentFeedFullTextHeader").fetchSemanticsNode().boundsInRoot
        assertEquals("Ordinary source toolbar is 54 points", 54f, header.height / density, .5f)
        val title = compose.onNodeWithTag("AgentFeedFullTextTitle").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("AgentFeedFullTextDragIndicator", useUnmergedTree = true).assertDoesNotExist()
        assertEquals("Original toolbar centers short titles in the whole window", metrics.bounds.exactCenterX(), title.center.x, 1f)
        val close = compose.onNodeWithTag("AgentFeedFullTextCloseCapsule")
        close.assertHeightIsEqualTo(44.dp).assertTouchHeightIsEqualTo(48.dp)
        assertEquals("Painted Close shares the source control row center", 22f,
            (close.fetchSemanticsNode().boundsInRoot.center.y - header.top) / density, .5f)
        assertEquals("Title shares the source control row center", 22f,
            (compose.onNodeWithTag("AgentFeedFullTextTitle").fetchSemanticsNode().boundsInRoot.center.y - header.top) / density, .5f)
        val closeBounds = close.fetchSemanticsNode().boundsInRoot
        assertTrue("Cancellation control stays clear of actual camera/system insets", closeBounds.left >= usable.left && closeBounds.right <= usable.right)
        if (requestedDirection == "rtl") assertTrue("RTL cancellation stays on the trailing physical side", closeBounds.left > title.right)
        else assertTrue("LTR cancellation stays on the leading physical side", closeBounds.right < title.left)
        val bounds = Rect()
        compose.runOnUiThread {
            val location = IntArray(2)
            reader!!.getLocationOnScreen(location)
            bounds.set(location[0], location[1], location[0] + reader!!.width, location[1] + reader!!.height)
        }
        assertEquals("Body uses all safe horizontal space", usable.width().toFloat(), bounds.width().toFloat(), 2f)
        assertEquals("Body starts after the real camera/system inset", usable.left.toFloat(), bounds.left.toFloat(), 2f)
        assertTrue("Native text ends above the system navigation inset", bounds.bottom <= usable.bottom + 2)
        assertTrue("The compact reader keeps a usable landscape viewport", bounds.height() / density > 200)
        compose.onNodeWithText("Close").assertIsDisplayed().assertHasClickAction()
        val record = JSONObject().put("windowBoundsPx", metrics.bounds.toString()).put("usableBoundsPx", usable.toString())
            .put("windowInsetsPx", insets.toString()).put("bodyBoundsPx", bounds.toString()).put("panelBoundsPx", panel.toString())
            .put("dragAreaBoundsPx", drag.toString()).put("headerBoundsPx", header.toString()).put("density", density)
            .put("requestedTheme", requestedTheme ?: "system").put("requestedDirection", requestedDirection)
            .put("requestedRotation", InstrumentationRegistry.getArguments().getString("cmux_reader_rotation") ?: "left")
            .put("titleBoundsPx", title.toString()).put("dragIndicatorVisible", false).put("closeBoundsPx", closeBounds.toString())
        compose.waitForIdle()
        device.waitForIdle(2_000)
        val settledBars = barAppearance()
        record.put("initialSystemBars", initialBars).put("settledSystemBars", settledBars)
        File(folder, "system-bars.json").writeText(record.toString(2))
        File(folder, "window-state.txt").writeText(device.executeShellCommand("dumpsys window"))
        assertTrue(device.takeScreenshot(File(folder, "landscape-settled.png")))
        val darkSheet = when (requestedTheme) {
            "dark" -> true
            "light" -> false
            else -> androidx.core.graphics.ColorUtils.calculateLuminance(settledBars.getInt("foreground")) > .5
        }
        // Independent source observations: original en-dark/en-light presented
        // sheet traits and attributed compositor padding pixels, run39961701278.
        val expectedBackground = if (darkSheet) android.graphics.Color.rgb(28, 28, 30) else android.graphics.Color.WHITE
        val expectedForeground = if (darkSheet) android.graphics.Color.WHITE else android.graphics.Color.BLACK
        assertEquals("Native reader uses original primary label color", expectedForeground, settledBars.getInt("foreground"))
        val screenshot = checkNotNull(android.graphics.BitmapFactory.decodeFile(File(folder, "landscape-settled.png").absolutePath))
        try {
            val x = bounds.left + (4 * density).toInt()
            val y = bounds.centerY()
            val color = screenshot.getPixel(x, y)
            record.put("backgroundSamplePx", org.json.JSONArray(listOf(x, y))).put("backgroundPixel", color)
            File(folder, "system-bars.json").writeText(record.toString(2))
            assertEquals("Actual empty native body padding matches original sheet background", expectedBackground, color)
        } finally { screenshot.recycle() }
        compose.runOnUiThread {
            val row = reader!!.getChildAt(0) as NativeMarkdownRow
            val heading = row.body!!
            assertTrue("Actual heading text has completed native precomputation", row.ready && heading.text.toString() == "Landscape reader")
            assertEquals("The rendered native heading receives the original label color", expectedForeground, heading.currentTextColor)
        }
        assertEquals("Focused reader navigation icons follow its actual sheet appearance", !darkSheet,
            settledBars.getBoolean("lightNavigationBars"))
        val dismissal = InstrumentationRegistry.getArguments().getString("cmux_reader_dismiss") ?: "back"
        check(dismissal in listOf("back", "swipe"))
        record.put("dismissalMethod", dismissal)
        if (dismissal == "swipe") {
            record.put("swipeInjectionReturned", device.swipe(metrics.bounds.centerX(), drag.center.y.toInt(),
                metrics.bounds.centerX(), usable.bottom - (8 * density).toInt(), 30))
        } else record.put("backInjectionReturned", device.pressBack())
        compose.waitUntil(5_000) { dismissals == 1 }
        compose.onNodeWithTag("AgentFeedFullTextCompactPanel").assertDoesNotExist()
        compose.runOnUiThread { assertTrue(reader!!.released) }
        File(folder, "result.json").writeText(record.put("dismissals", dismissals).put("scope",
            "Owned compact landscape API36 emulator, real production reader/window/insets, full-window toolbar centering and source compact-landscape grabber visibility, controlled LTR/RTL and Back/swipe dismissal. Not exact iOS Close edge margins, OS RTL locales/materials, real transport, physical Pixel/Mac, TalkBack or full parity acceptance.").toString(2))
    }
}
