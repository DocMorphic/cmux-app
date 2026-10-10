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
        .asReversed().firstNotNullOfOrNull {
            it.findViewWithTag<AgentFeedSourceScroll>("AgentFeedSourceScroll")
                ?.takeIf { view -> view.isShown && view.isAttachedToWindow && view.hasWindowFocus() }
        }
    private fun awaitReader(check: (AgentFeedSourceScroll) -> Boolean = { it.body.height > 0 }) {
        compose.waitUntil(20_000) {
            val ready = AtomicBoolean(false)
            compose.runOnUiThread { ready.set(reader()?.let(check) == true) }
            ready.get()
        }
    }
    private fun capture(name: String, finalLine: String? = null) {
        val i = InstrumentationRegistry.getInstrumentation()
        // Native callbacks do not advance the Compose test clock. Settle the
        // AndroidView owner's draw before waiting for platform frames.
        compose.waitForIdle()
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
        compose.runOnUiThread {
            val view = checkNotNull(reader())
            File(directory, "$name.json").writeText(org.json.JSONObject()
                .put("focused", view.hasWindowFocus()).put("attached", view.isAttachedToWindow)
                .put("scrollY", view.scrollY).put("bodyHeight", view.body.height).put("viewportHeight", view.height)
                .put("firstLine", view.body.layout.getLineForVertical((view.scrollY - view.body.paddingTop).coerceAtLeast(0)))
                .put("length", view.body.text.length).toString())
        }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            if (finalLine != null) {
                var checked = 0; var painted = 0
                compose.runOnUiThread {
                    val body = checkNotNull(reader()).body
                    val line = body.layout.getLineForOffset(body.text.length - 1)
                    assertEquals(finalLine, body.text.subSequence(body.layout.getLineStart(line), body.text.length).toString())
                    val paint = android.text.TextPaint(body.paint)
                    val top = kotlin.math.floor(paint.fontMetrics.top.toDouble()).toInt()
                    val height = kotlin.math.ceil((paint.fontMetrics.bottom - top).toDouble()).toInt() + 4
                    val reference = android.graphics.Bitmap.createBitmap(
                        kotlin.math.ceil(paint.measureText(finalLine).toDouble()).toInt() + 4,
                        height, android.graphics.Bitmap.Config.ARGB_8888)
                    android.graphics.Canvas(reference).drawText(finalLine, 2f, (2 - top).toFloat(), paint)
                    val location = IntArray(2); body.getLocationOnScreen(location)
                    val left = location[0] + body.totalPaddingLeft + body.layout.getLineLeft(line).toInt() - 2
                    val screenTop = location[1] + body.totalPaddingTop + body.layout.getLineBaseline(line) + top - 2
                    for (y in 0 until reference.height) for (x in 0 until reference.width) {
                        if (android.graphics.Color.alpha(reference.getPixel(x, y)) < 200) continue
                        checked++
                        if (left + x !in 0 until bitmap.width || screenTop + y !in 0 until bitmap.height) continue
                        val actual = bitmap.getPixel(left + x, screenTop + y)
                        val expected = paint.color
                        if (kotlin.math.abs(android.graphics.Color.red(actual) - android.graphics.Color.red(expected)) < 45 &&
                            kotlin.math.abs(android.graphics.Color.green(actual) - android.graphics.Color.green(expected)) < 45 &&
                            kotlin.math.abs(android.graphics.Color.blue(actual) - android.graphics.Color.blue(expected)) < 45) painted++
                    }
                    reference.recycle()
                }
                bitmap.recycle()
                assertTrue("Final line must be painted on screen ($painted/$checked ink pixels)",
                    checked > 100 && painted.toDouble() / checked > .85)
            } else {
                bitmap.recycle()
            }
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
        capture("long-source-tail", "TAIL_MARKER")
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
