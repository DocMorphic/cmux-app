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

    @Test fun originalControlsCenterTheTitleAndPlaceLoadingAboveItsLabel() {
        val result = CompletableDeferred<String>(); var dismissed = false
        compose.setContent { CmuxTheme { AgentFeedFullText({ result.await() }, {}, { dismissed = true }) } }
        compose.onNodeWithText("Source").assertDoesNotExist(); compose.onNodeWithText("Formatted").assertDoesNotExist()
        val title = compose.onNodeWithTag("AgentFeedFullTextTitle").fetchSemanticsNode().boundsInRoot
        val sheet = compose.onNodeWithTag("AgentFeedFullTextSheet").fetchSemanticsNode().boundsInRoot
        assertEquals(sheet.center.x, title.center.x, 1f)
        val indicator = compose.onNodeWithTag("AgentFeedFullTextProgress").fetchSemanticsNode().boundsInRoot
        val label = compose.onNodeWithText("Loading full text…").fetchSemanticsNode().boundsInRoot
        assertTrue(indicator.bottom <= label.top)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "feed-reader").apply { mkdirs() }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "original-controls-loading.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; image.recycle()
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
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "feed-reader").apply { mkdirs() }
        val image = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        File(directory, "retry-original-generic-error.png").outputStream().use { image.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        image.recycle()
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
