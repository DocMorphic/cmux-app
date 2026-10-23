package io.github.docmorphic.cmuxapp

import android.text.Selection
import android.text.Spannable
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class AgentFeedFullTextTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private fun awaitReader() {
        compose.waitUntil(20_000) {
            var ready = false
            compose.runOnUiThread { ready = WindowInspector.getGlobalWindowViews().any {
                it.findViewWithTag<NativeMarkdownList>("AgentFeedNativeMarkdown")?.isShown == true
            } }
            ready
        }
    }

    private fun captureStatus(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "feed-reader").apply { mkdirs() }
        fun windows() = org.json.JSONArray().apply {
            compose.runOnUiThread {
                WindowInspector.getGlobalWindowViews().filter { it.isShown }.forEach {
                    put(org.json.JSONObject().put("class", it.javaClass.name).put("focus", it.hasWindowFocus())
                        .put("appearanceFlags", it.windowInsetsController?.systemBarsAppearance))
                }
            }
        }
        fun screenshot(suffix: String) {
            val image = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            File(directory, name + suffix + ".png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
        }
        val before = windows(); screenshot("")
        compose.waitForIdle()
        androidx.test.uiautomator.UiDevice.getInstance(instrumentation).waitForIdle(2_000)
        val drawn = java.util.concurrent.CountDownLatch(1)
        compose.runOnUiThread {
            WindowInspector.getGlobalWindowViews().first { it.isShown && it.hasWindowFocus() }
                .postOnAnimation { compose.activity.window.decorView.postOnAnimation { drawn.countDown() } }
        }
        assertTrue(drawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
        instrumentation.waitForIdleSync()
        val after = windows(); screenshot("-settled")
        File(directory, name + "-windows.json").writeText(org.json.JSONObject().put("initial", before).put("settled", after).toString(2))
    }

    @Test fun originalControlsCenterTheTitleAndPlaceLoadingAboveItsLabel() {
        val result = CompletableDeferred<String>(); var dismissed = false
        compose.setContent { CmuxTheme { AgentFeedFullText({ result.await() }, {}, { dismissed = true }) } }
        compose.onNodeWithText("Source").assertDoesNotExist(); compose.onNodeWithText("Formatted").assertDoesNotExist()
        val title = compose.onNodeWithTag("AgentFeedFullTextTitle").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("AgentFeedFullTextSheet").fetchSemanticsNode().boundsInRoot
        assertEquals(sheet.center.x, title.center.x, 1f)
        val header = compose.onNodeWithTag("AgentFeedFullTextHeader").fetchSemanticsNode().boundsInRoot
        assertEquals("Ordinary reader toolbar matches the measured 54-point source component", 54f,
            header.height / compose.density.density, .5f)
        assertEquals("Title shares the original 22pt control center", 22f,
            (title.center.y - header.top) / compose.density.density, .5f)
        val close = compose.onNodeWithTag("AgentFeedFullTextCloseCapsule")
        close.assertHeightIsEqualTo(44.dp).assertTouchHeightIsEqualTo(48.dp)
        val configuration = compose.activity.resources.configuration
        if (configuration.screenWidthDp < 600 && configuration.screenWidthDp < configuration.screenHeightDp) {
            val panel = compose.onNodeWithTag("AgentFeedFullTextCompactPanel").fetchSemanticsNode().boundsInRoot
            val drag = compose.onNodeWithTag("AgentFeedFullTextDragArea", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val safeTop = compose.activity.windowManager.currentWindowMetrics.windowInsets.getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() or android.view.WindowInsets.Type.displayCutout()).top.toFloat()
            assertEquals("Portrait reserves only the safe top inset remaining below the sheet offset",
                maxOf(panel.top, safeTop), drag.top, 1f)
            assertEquals("Ordinary portrait drag area matches the original 16 points", 16f, drag.height / compose.density.density, .5f)
            val indicator = compose.onNodeWithTag("AgentFeedFullTextDragIndicator", useUnmergedTree = true).assertIsDisplayed().fetchSemanticsNode().boundsInRoot
            assertEquals("Portrait grabber remains centered in the full sheet", panel.center.x, indicator.center.x, 1f)
        }
        val indicator = compose.onNodeWithTag("AgentFeedFullTextProgress", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithTag("AgentFeedFullTextLoadingLabel", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue(indicator.bottom <= label.top)
        captureStatus("original-controls-loading")
        result.complete("# Original reader\n\nNative **body**"); awaitReader()
        compose.onNodeWithText("Source").assertDoesNotExist(); compose.onNodeWithText("Formatted").assertDoesNotExist()
        compose.onNodeWithText("Close").performClick(); compose.runOnIdle { assertTrue(dismissed) }
    }

    @Test fun retryReadsOnlyAfterSuccessAndDismissCancelsPendingRead() {
        var loads = 0; var reads = 0
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var pending by mutableStateOf(false)
        var shown by mutableStateOf(true)
        compose.setContent { CmuxTheme {
            if (shown) key(pending) { AgentFeedFullText({
                loads++
                if (pending) try { entered.complete(Unit); awaitCancellation() }
                finally { cancelled.complete(Unit) }
                if (loads == 1) error("Reconnect and try again")
                "Complete response"
            }, { reads++ }, { shown = false }) }
        } }
        val unavailable = InstrumentationRegistry.getInstrumentation().targetContext.getString(R.string.cmux_ui_mobile_agentfeed_fulltext_unavailable_b62af026)
        compose.onNodeWithText(unavailable).assertIsDisplayed()
        compose.onNodeWithText("Reconnect and try again").assertDoesNotExist()
        captureStatus("retry-original-generic-error")
        compose.runOnIdle { assertEquals(0, reads) }
        compose.onNodeWithText("Try again").performClick()
        awaitReader()
        compose.runOnIdle { assertEquals(1, reads); pending = true }
        compose.waitUntil(5_000) { entered.isCompleted }
        compose.onNodeWithText("Close").performClick()
        compose.waitUntil(5_000) { cancelled.isCompleted }
        compose.runOnIdle { assertEquals(1, reads) }
        compose.onNodeWithText("Full text").assertDoesNotExist()
    }

    @Test fun lateNonCooperativeReadAfterCloseNeverMarksTheMessageRead() {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()
        var shown by mutableStateOf(true)
        var reads = 0
        compose.setContent { CmuxTheme {
            if (shown) AgentFeedFullText({
                try { withContext(NonCancellable) {
                    entered.complete(Unit); release.await(); "Late complete response"
                } } finally { finished.complete(Unit) }
            }, { reads++ }, { shown = false })
        } }
        try {
            compose.waitUntil(5_000) { entered.isCompleted }
            compose.onNodeWithText("Loading full text…").assertIsDisplayed()
            compose.onNodeWithText("Close").performClick()
            compose.onNodeWithTag("AgentFeedFullTextSheet").assertDoesNotExist()
            release.complete(Unit)
            compose.waitUntil(5_000) { finished.isCompleted }
            compose.waitForIdle()
            compose.runOnIdle { assertEquals(0, reads) }
        } finally { release.complete(Unit) }
    }
}
