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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import android.webkit.WebView
import android.view.View
import android.view.ViewGroup
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
    private fun capture(name: String) {
        compose.mainClock.advanceTimeByFrame(); compose.waitForIdle()
        val i = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(i.targetContext.getExternalFilesDir(null), "agent-feed-composer").apply { mkdirs() }
        i.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        }
    }
    private fun web(view: View): WebView? = when (view) {
        is WebView -> view
        is ViewGroup -> (0 until view.childCount).firstNotNullOfOrNull { web(view.getChildAt(it)) }
        else -> null
    }
    private fun awaitRenderedQuote() {
        compose.waitUntil(30_000) {
            val ready = AtomicBoolean(false); val latch = CountDownLatch(1)
            compose.runOnUiThread {
                val web = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { web(it) }
                if (web == null) latch.countDown() else web.evaluateJavascript(
                    "document.getElementById('content')?.innerText.includes('TAIL_MARKER') === true") { result ->
                    if (result != "true") latch.countDown() else web.postVisualStateCallback(1, object : WebView.VisualStateCallback() {
                        override fun onComplete(id: Long) { ready.set(true); latch.countDown() }
                    })
                }
            }
            latch.await(5, TimeUnit.SECONDS) && ready.get()
        }
    }
    private fun assertQuotePaint() {
        val info = java.util.concurrent.atomic.AtomicReference<String>()
        val bounds = java.util.concurrent.atomic.AtomicReference<android.graphics.Rect>()
        val latch = CountDownLatch(1)
        compose.runOnUiThread {
            val web = WindowInspector.getGlobalWindowViews().firstNotNullOfOrNull { web(it) }!!
            val rect = android.graphics.Rect(); web.getGlobalVisibleRect(rect); bounds.set(rect)
            val android = "width=${web.width},height=${web.height},scroll=${web.scrollY},visible=$rect,alpha=${web.alpha},shown=${web.isShown}"
            web.evaluateJavascript("JSON.stringify({width:innerWidth,height:innerHeight,scrollY,body:document.body.getBoundingClientRect().toJSON(),content:document.getElementById('content').getBoundingClientRect().toJSON(),color:getComputedStyle(document.getElementById('content')).color,html:document.getElementById('content').innerHTML.slice(0,250)})") {
                info.set(android + "\n" + it); latch.countDown()
            }
        }
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        val i = InstrumentationRegistry.getInstrumentation()
        java.io.File(i.targetContext.getExternalFilesDir(null), "agent-feed-composer/quote-geometry.txt").writeText(info.get())
        val box = bounds.get()
        compose.waitUntil(8_000) {
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
    private inner class Fixture(items: List<NativeAgentFeedItem>) : AutoCloseable {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
        val requests = CopyOnWriteArrayList<Pair<String, JSONObject>>()
        val session = NativeAgentFeedSession(scope, { true }, { method, params ->
            requests += method to params
            if (method == "feed.text") JSONObject().put("text", "Full report\n\n" + "long body ".repeat(1000) + "TAIL_MARKER").put("version", 1)
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
            compose.onNode(hasText("See more", substring = true) and hasAnyAncestor(hasTestTag("AgentFeedReplySheet")), useUnmergedTree = true).clickInlineMore()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("AgentFeedExpandedQuote").fetchSemanticsNodes().isNotEmpty() }
            awaitRenderedQuote()
            capture("expanded-quote")
            assertQuotePaint()
            compose.onNodeWithTag("AgentFeedComposeDraft").performTextInput("Private draft")
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
    @Test fun planRevisionUsesQuotedSheetAndManualFeedback() {
        Fixture(listOf(plan)).use { fixture ->
            compose.setContent { fixture.Content() }
            compose.onNodeWithText("Revise", useUnmergedTree = true).performClick()
            compose.onNode(hasText("A proposed plan") and hasAnyAncestor(hasTestTag("AgentFeedReplySheet")), useUnmergedTree = true).assertIsDisplayed()
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
