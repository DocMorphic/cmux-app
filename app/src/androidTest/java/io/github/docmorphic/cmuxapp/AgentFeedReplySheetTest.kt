package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import android.widget.TextView
import android.widget.ScrollView
import android.view.View
import android.view.inspector.WindowInspector

class AgentFeedReplySheetTest {
    private fun SemanticsNodeInteraction.clickInlineMore() {
        val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
        performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.GetTextLayoutResult) { it(layouts) }
        val layout = layouts.single()
        val offset = layout.layoutInput.text.text.lastIndexOf("See more")
        performTouchInput { click(layout.getBoundingBox(offset + 2).center) }
    }

    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val mac = NativeCredentialStore.PairedMac("fixture", "mac", "Mac", instanceTag = "build")
    private val stop = NativeAgentFeedItem("stop", "turn", "Claude", AgentFeedKind.STOP, AgentFeedStatus.TELEMETRY,
        100.0, 100.0, workspaceId = "workspace", surfaceId = "terminal", reason = "Preview of the report", fullTextTruncated = true)
    private val plan = stop.copy(id = "plan", kind = AgentFeedKind.PLAN, status = AgentFeedStatus.PENDING,
        requestId = "plan-request", reason = null, plan = "A proposed plan", defaultMode = "autoAccept", fullTextTruncated = false)
    private fun capture(name: String, checkQuoteClipping: Boolean = false) {
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(i.targetContext.getExternalFilesDir(null), "agent-feed-composer").apply { mkdirs() }
        compose.waitUntil(8_000) { hasAppWindowFocus() }
        val toolbarGap = android.graphics.Rect()
        if (checkQuoteClipping) compose.runOnUiThread {
            val view = scroll()
            val location = IntArray(2); view.getLocationOnScreen(location)
            val density = view.resources.displayMetrics.density
            // The middle of the fixed toolbar contains no controls. Scrolled
            // text must not escape its viewport and paint into this gap.
            toolbarGap.set(location[0] + view.width / 3, location[1] - (24 * density).toInt(),
                location[0] + view.width * 2 / 3, location[1] - (4 * density).toInt())
        }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            try {
                java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                if (checkQuoteClipping) {
                    var ink = 0
                    for (y in toolbarGap.top until toolbarGap.bottom step 2)
                        for (x in toolbarGap.left until toolbarGap.right step 2) {
                            val pixel = bitmap.getPixel(x, y)
                            if (android.graphics.Color.red(pixel) > 110 && android.graphics.Color.green(pixel) > 110 &&
                                android.graphics.Color.blue(pixel) > 110) ink++
                        }
                    assertTrue("Quoted text painted over the toolbar: $ink bright samples", ink < 10)
                }
            } finally { bitmap.recycle() }
        }
    }
    // Inspect this process's actual window focus. Accessibility's active-root
    // query can return null during window transitions; an external ANR dialog
    // takes focus away from every application window.
    private fun hasAppWindowFocus(): Boolean {
        val focused = AtomicBoolean(false)
        compose.runOnUiThread { focused.set(WindowInspector.getGlobalWindowViews().any { it.hasWindowFocus() }) }
        return focused.get()
    }
    private fun quote(): TextView? = WindowInspector.getGlobalWindowViews()
        .firstNotNullOfOrNull { it.findViewWithTag<TextView>("AgentFeedExpandedQuote") }
    private fun scroll(): ScrollView = WindowInspector.getGlobalWindowViews()
        .firstNotNullOf { it.findViewWithTag<ScrollView>("AgentFeedComposeScroll") }
    private fun scrollToDraft() {
        compose.runOnUiThread { scroll().scrollTo(0, scroll().getChildAt(0).height) }
        compose.waitUntil(5_000) { compose.onNodeWithTag("AgentFeedComposeDraft").isDisplayed() }
    }
    private fun awaitRenderedQuote() {
        compose.waitUntil(30_000) {
            val ready = AtomicBoolean(false)
            compose.runOnUiThread { ready.set(quote()?.let { it.isShown && it.text.endsWith("TAIL_MARKER") && it.height > 0 } == true) }
            ready.get()
        }
        compose.runOnUiThread { scroll().scrollTo(0, 0) }
        compose.waitForIdle()
    }
    private fun assertQuotePaint() {
        val info = java.util.concurrent.atomic.AtomicReference<String>()
        val bounds = java.util.concurrent.atomic.AtomicReference<android.graphics.Rect>()
        compose.runOnUiThread {
            val view = quote()!!
            val rect = android.graphics.Rect(); view.getGlobalVisibleRect(rect); bounds.set(rect)
            info.set("width=${view.width},height=${view.height},scroll=${scroll().scrollY},visible=$rect,shown=${view.isShown},length=${view.text.length},lines=${view.lineCount}")
        }
        val i = InstrumentationRegistry.getInstrumentation()
        java.io.File(i.targetContext.getExternalFilesDir(null), "agent-feed-composer/quote-geometry.txt").writeText(info.get())
        val box = bounds.get()
        compose.waitUntil(8_000) {
            if (!hasAppWindowFocus()) return@waitUntil false
            i.uiAutomation.takeScreenshot().let { bitmap ->
                var ink = 0
                for (y in box.top.toInt().coerceAtLeast(0) until box.bottom.toInt().coerceAtMost(bitmap.height) step 2)
                    for (x in box.left.toInt().coerceAtLeast(0) until box.right.toInt().coerceAtMost(bitmap.width) step 2) {
                        val c = bitmap.getPixel(x, y)
                        if (android.graphics.Color.red(c) > 110 && android.graphics.Color.green(c) > 110 && android.graphics.Color.blue(c) > 110) ink++
                    }
                if (ink > 200) java.io.File(i.targetContext.getExternalFilesDir(null), "agent-feed-composer/expanded-quote-painted.png")
                    .outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle(); ink > 200
            }
        }
    }
    private inner class Fixture(items: List<NativeAgentFeedItem>, val fullBody: String = "Full report\n\n" + "long body ".repeat(1000) + "TAIL_MARKER") : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val session = NativeAgentFeedSession(scope, { true }, { method, params ->
            requests += method to params
            if (method == "feed.text") {
                // Fixtures use ASCII; real transport tests cover UTF-8 page boundaries.
                val start = params.getInt("offset")
                val end = (start + 16384).coerceAtMost(fullBody.length)
                JSONObject().put("text", fullBody.substring(start, end)).put("version", 1).apply {
                    if (end < fullBody.length) put("next_offset", end)
                }
            }
            else JSONObject().put("submitted", true)
        }, NativeAgentFeedSnapshot(1, items))
        var loaded by mutableStateOf(true)
        var account by mutableStateOf("account-a")
        @Composable fun Content() {
            val feed by session.state.collectAsState()
            var read by remember { mutableStateOf(NativeAgentFeedReadState(0.0)) }
            CmuxTheme { Surface { NativeAgentFeedView(listOf(NativeFeedSource(mac,
                availability = NativeFeedAvailability.CONNECTED, capabilities = setOf(AGENT_FEED_CAPABILITY),
                agentFeed = if (loaded) feed else NativeAgentFeedState(loading = true))), "", false, read, { read = it },
                { session }, { "Mac" }, { _, _ -> }, {}, Modifier.fillMaxSize().safeDrawingPadding(),
                scopeKey = account, allowedMacs = listOf(mac)) } }
        }
        override fun close() { session.close(); scope.cancel() }
    }
    @Test fun draftSurvivesRestorationWhileSnapshotReloadsAndOnlyExplicitReplySends() {
        Fixture(listOf(stop)).use { fixture ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { fixture.Content() }
            compose.onNodeWithText("Reply", useUnmergedTree = true).performClick()
            compose.onNodeWithText("Replying to Claude", useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("AgentFeedComposeDraft").performTextInput("Continue with tests")
            capture("reply-sheet")
            compose.runOnIdle { fixture.loaded = false }
            restoration.emulateSavedInstanceStateRestore()
            compose.onNodeWithText("Waiting for this Mac’s Feed").assertIsDisplayed()
            compose.runOnIdle { assertTrue(fixture.requests.isEmpty()); fixture.loaded = true }
            compose.onNodeWithTag("AgentFeedComposeDraft").assertTextContains("Continue with tests")
            compose.onNodeWithTag("AgentFeedComposeSend").performClick()
            compose.waitUntil(10_000) { fixture.requests.any { it.first == "mobile.terminal.paste" } }
            compose.runOnIdle {
                val sent = fixture.requests.single { it.first == "mobile.terminal.paste" }.second
                assertEquals("Continue with tests", sent.getString("text")); assertEquals("stop", sent.getString("feed_event_id"))
            }
            compose.onNodeWithTag("AgentFeedReplySheet").assertDoesNotExist()
        }
    }
    @Test fun expandedQuoteAndReaderReloadWithoutTruncationAndAccountChangeClearsComposer() {
        Fixture(listOf(stop)).use { fixture ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { fixture.Content() }
            compose.onNodeWithText("Reply", useUnmergedTree = true).performClick()
            compose.onNode(hasText("See more", substring = true) and hasAnyAncestor(hasTestTag("AgentFeedComposerPreviewContent")), useUnmergedTree = true).clickInlineMore()
            awaitRenderedQuote()
            capture("expanded-quote")
            assertQuotePaint()
            scrollToDraft()
            compose.onNodeWithTag("AgentFeedComposeDraft").assertIsDisplayed().performTextInput("Private draft")
            compose.runOnIdle { fixture.account = "account-b" }
            compose.onNodeWithTag("AgentFeedReplySheet").assertDoesNotExist()
            compose.onNodeWithText("See more", substring = true, useUnmergedTree = true).clickInlineMore()
            compose.onNodeWithText("Source").performClick()
            compose.onNodeWithText("TAIL_MARKER", substring = true).assertExists()
            val reads = fixture.requests.count { it.first == "feed.text" }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(10_000) { fixture.requests.count { it.first == "feed.text" } > reads }
            compose.onNodeWithText("Formatted").assertIsDisplayed()
            compose.onNodeWithText("TAIL_MARKER", substring = true).assertExists()
            compose.runOnIdle { assertTrue(fixture.requests.none { it.first == "mobile.terminal.paste" }) }
        }
    }
    @Test fun longQuoteUsesOneScrollAreaAndRestoresPositionAndDraftAfterReload() {
        val body = "**" + "long body ".repeat(110_000) + "end**TAIL_MARKER"
        Fixture(listOf(stop), body).use { fixture ->
            val restoration = StateRestorationTester(compose)
            restoration.setContent { fixture.Content() }
            compose.onNodeWithText("Reply", useUnmergedTree = true).performClick()
            compose.onNodeWithTag("AgentFeedComposeDraft").performTextInput("Keep this draft")
            compose.onNode(hasText("See more", substring = true) and hasAnyAncestor(hasTestTag("AgentFeedComposerPreviewContent")), useUnmergedTree = true).clickInlineMore()
            awaitRenderedQuote()
            compose.runOnUiThread {
                val full = quote()!!
                assertEquals(body.length - 4, full.text.length)
                assertTrue("Body must exceed Compose's packed-constraint height", full.height > 262143)
                assertEquals(0, full.scrollY)
                val spans = full.text as android.text.Spanned
                assertTrue(spans.getSpans(0, 10, android.text.style.StyleSpan::class.java).any { it.style == android.graphics.Typeface.BOLD })
            }
            scrollToDraft()
            compose.onNodeWithTag("AgentFeedComposeDraft").assertIsDisplayed().assertTextContains("Keep this draft")
            capture("continuous-quote-bottom", checkQuoteClipping = true)
            var savedY = 0
            compose.runOnUiThread { scroll().scrollTo(0, 1200); savedY = scroll().scrollY }
            restoration.emulateSavedInstanceStateRestore()
            compose.waitUntil(30_000) {
                val ready = AtomicBoolean(false)
                compose.runOnUiThread { ready.set(quote()?.let {
                    it.text.endsWith("TAIL_MARKER") && it.height > 262143 && scroll().scrollY == savedY
                } == true) }
                ready.get()
            }
            compose.runOnUiThread { assertEquals(savedY, scroll().scrollY) }
            scrollToDraft()
            compose.onNodeWithTag("AgentFeedComposeDraft").assertIsDisplayed().assertTextContains("Keep this draft")
            compose.runOnIdle { assertTrue(fixture.requests.none { it.first == "mobile.terminal.paste" }) }
        }
    }

    @Test fun planRevisionUsesQuotedSheetAndManualFeedback() {
        Fixture(listOf(plan)).use { fixture ->
            compose.setContent { fixture.Content() }
            compose.onNodeWithText("Revise", useUnmergedTree = true).performClick()
            compose.onNode(hasText("A proposed plan") and hasAnyAncestor(hasTestTag("AgentFeedComposerPreviewContent")), useUnmergedTree = true).assertIsDisplayed()
            compose.onNodeWithTag("AgentFeedComposeDraft").performTextInput("Include rollback")
            compose.onNodeWithTag("AgentFeedComposeSend").performClick()
            compose.waitUntil(10_000) { fixture.requests.any { it.first == "feed.exit_plan.reply" } }
            compose.runOnIdle {
                val sent = fixture.requests.single { it.first == "feed.exit_plan.reply" }.second
                assertEquals("manual", sent.getString("mode")); assertEquals("Include rollback", sent.getString("feedback"))
            }
        }
    }
    @Test fun approvalMenuSendsTheChosenModeWithoutOpeningAComposer() {
        Fixture(listOf(plan)).use { fixture ->
            compose.setContent { fixture.Content() }
            compose.onNodeWithContentDescription("More approval modes").performClick()
            compose.onNodeWithText("Approve as ultraplan").performClick()
            compose.waitUntil(10_000) { fixture.requests.any { it.first == "feed.exit_plan.reply" } }
            compose.runOnIdle { assertEquals("ultraplan", fixture.requests.single { it.first == "feed.exit_plan.reply" }.second.getString("mode")) }
            compose.onNodeWithTag("AgentFeedReplySheet").assertDoesNotExist()
        }
    }

}
