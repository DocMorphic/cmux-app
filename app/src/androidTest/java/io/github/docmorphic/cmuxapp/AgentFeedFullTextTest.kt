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
    private val modal = AgentFeedModal("account", "event", "mac", "build", "turn", null,
        AgentFeedKind.STOP, "workspace", "terminal", "read", raw = true)
    private fun reader() = WindowInspector.getGlobalWindowViews()
        .firstNotNullOfOrNull { it.findViewWithTag<AgentFeedSourceScroll>("AgentFeedSourceScroll") }
    private fun awaitReader(check: (AgentFeedSourceScroll) -> Boolean = { it.body.height > 0 }) {
        compose.waitUntil(20_000) {
            val ready = AtomicBoolean(false)
            compose.runOnUiThread { ready.set(reader()?.let(check) == true) }
            ready.get()
        }
    }
    private fun capture(name: String) {
        val i = InstrumentationRegistry.getInstrumentation()
        // A native scroll changes geometry before the next frame is painted.
        // Wait for that frame before accepting the screenshot as visual evidence.
        val drawn = java.util.concurrent.CountDownLatch(1)
        compose.runOnUiThread {
            val view = checkNotNull(reader())
            view.postOnAnimation { view.postOnAnimation { drawn.countDown() } }
        }
        assertTrue("Reader did not draw", drawn.await(5, java.util.concurrent.TimeUnit.SECONDS))
        i.waitForIdleSync()
        val directory = File(i.targetContext.getExternalFilesDir(null), "feed-reader").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }

    @Test fun longSourceReloadsWithoutSavingMessageAndRestoresViewportAndSelection() {
        val body = (1..14_000).joinToString("\n") { "Reader line $it — complete source text." } + "\nTAIL_MARKER"
        val restoration = StateRestorationTester(compose)
        var loads = 0
        restoration.setContent { CmuxTheme {
            AgentFeedFullText(modal, {}, { loads++; body }, {}, {})
        } }
        awaitReader()
        var y = 0; var selection = 0
        compose.runOnUiThread {
            val view = reader()!!
            assertEquals(body, view.body.text.toString())
            assertTrue("Must exceed Compose's text height constraint", view.body.height > 262143)
            assertTrue(view.body.isTextSelectable)
            view.scrollTo(0, 12_345); y = view.scrollY
            selection = view.body.layout.getLineStart(view.body.layout.getLineForVertical(y))
            Selection.setSelection(view.body.text as Spannable, selection, selection + 11)
            view.id = android.view.View.generateViewId()
            view.body.id = android.view.View.generateViewId()
            val state = android.util.SparseArray<android.os.Parcelable>()
            view.saveHierarchyState(state)
            assertEquals("The native hierarchy must not freeze message text", 0, state.size())
        }
        restoration.emulateSavedInstanceStateRestore()
        awaitReader { it.body.text.length == body.length && it.scrollY == y && it.body.selectionStart == selection }
        compose.runOnUiThread {
            assertEquals(2, loads)
            assertEquals(selection + 11, reader()!!.body.selectionEnd)
            reader()!!.scrollTo(0, reader()!!.body.height)
        }
        capture("long-source-tail")
        compose.runOnUiThread {
            val view = reader()!!
            val last = view.body.layout.getLineForOffset(body.lastIndex)
            assertTrue("Final message line must be reachable", view.body.layout.getLineBottom(last) <= view.scrollY + view.height)
        }
    }

    @Test fun formattedToggleRetainsSourcePositionAndSelectionWithoutReloading() {
        val body = (1..250).joinToString("\n\n") { "Paragraph $it. Keep this reading position." }
        var loads = 0
        compose.setContent { CmuxTheme {
            var raw by rememberSaveable { mutableStateOf(true) }
            AgentFeedFullText(modal.copy(raw = raw), { raw = it.raw }, { loads++; body }, {}, {})
        } }
        awaitReader()
        var y = 0
        compose.runOnUiThread {
            reader()!!.scrollTo(0, 1200); y = reader()!!.scrollY
            Selection.setSelection(reader()!!.body.text as Spannable, 200, 220)
        }
        compose.onNodeWithText("Formatted").performClick()
        compose.onNodeWithText("Source").performClick()
        awaitReader { it.scrollY == y && it.body.selectionStart == 200 && it.body.selectionEnd == 220 }
        compose.runOnIdle { assertEquals(1, loads) }
        capture("source-mode-restored")
    }

    @Test fun retryReadsOnlyAfterSuccessAndDismissCancelsPendingRead() {
        var loads = 0; var reads = 0
        val entered = CompletableDeferred<Unit>()
        val cancelled = CompletableDeferred<Unit>()
        var pending by mutableStateOf(false)
        var shown by mutableStateOf(true)
        compose.setContent { CmuxTheme {
            if (shown) key(pending) { AgentFeedFullText(modal, {}, {
                loads++
                if (pending) try { entered.complete(Unit); awaitCancellation() }
                finally { cancelled.complete(Unit) }
                if (loads == 1) error("Reconnect and try again")
                "Complete response"
            }, { reads++ }, { shown = false }) }
        } }
        compose.onNodeWithText("Reconnect and try again").assertIsDisplayed()
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
            if (shown) AgentFeedFullText(modal, {}, {
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
