package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AgentFeedReplyRowTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val item = NativeAgentFeedItem("stop", "turn", "Claude", AgentFeedKind.STOP, AgentFeedStatus.TELEMETRY,
        10.0, 10.0, reason = "Task complete", workspaceId = "w", surfaceId = "terminal")
    private val owner = AgentFeedUiOwner("opaque-owner", null)
    private fun row(failure: AgentFeedFailure? = null) = AgentFeedUiEntry("row", owner, item, "Mac", true, false, failure, true)
    private fun snapshot(row: AgentFeedUiEntry) = AgentFeedUiSnapshot(listOf(row), setOf(owner), setOf(owner), true, false, false, true, false)
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.getExternalFilesDir(null), "feed-reply-rows").apply { mkdirs() }
        InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot().let { bitmap ->
            File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    @Test fun uncertainReplyReopensItsDraftWithoutSendingAndOffersTheExactTerminal() {
        val opened = mutableListOf<Pair<String, Boolean>>()
        var replies = 0
        val failed = row(AgentFeedFailure("Delivery unconfirmed", AgentFeedDelivery.UNCONFIRMED, "Keep this reply"))
        val actions = object : AgentFeedTimelineActions {
            override suspend fun decide(entry: AgentFeedUiEntry, decision: AgentFeedDecision) = error("Unexpected decision")
            override suspend fun reply(entry: AgentFeedUiEntry, text: String): Boolean { replies++; return true }
            override suspend fun fullText(entry: AgentFeedUiEntry) = "Full message"
            override fun read(entry: AgentFeedUiEntry, needsInput: Boolean?) = Unit
            override fun open(entry: AgentFeedUiEntry, tab: Boolean) { opened += entry.item.surfaceId!! to tab }
        }
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            AgentFeedTimeline(snapshot(failed), "", false, actions, {}, Modifier.fillMaxSize(), scopeKey = "fixture")
        } } }
        compose.onNodeWithText("Couldn’t confirm your reply was sent. Check the terminal before retrying.").assertIsDisplayed()
        compose.onNodeWithText("Try Again").performClick()
        compose.onNodeWithTag("AgentFeedComposeDraft").assertTextContains("Keep this reply")
        compose.runOnIdle { assertEquals(0, replies); assertTrue(opened.isEmpty()) }
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Open Terminal").performClick()
        compose.runOnIdle { assertEquals(listOf("terminal" to true), opened); assertEquals(0, replies) }
        capture("unconfirmed-reply")
    }
    @Test fun bubbleAndBarRepliesUseSourceLayoutAndAccessibleSentLabelInBothDirections() {
        var direction by mutableStateOf(LayoutDirection.Ltr)
        compose.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            CompositionLocalProvider(LocalLayoutDirection provides direction) {
                Column(Modifier.padding(20.dp).width(320.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("Quoted prompt")
                    FeedBubble(user = true, filled = false) { Text("Inspect the build logs") }
                    Text("Recorded reply · bubbles")
                    FeedReplyMarker("**Thanks**, continue.", "Task complete", true)
                    Text("Recorded reply · quote bar")
                    FeedReplyMarker("Continue with tests", "Task complete", false)
                }
            }
        } } }
        compose.onNodeWithContentDescription("You: **Thanks**, continue.").assertIsDisplayed()
        compose.onNodeWithText("Replying to “Task complete”").assertIsDisplayed()
        compose.onNodeWithText("You").assertIsDisplayed()
        compose.onNodeWithText("Continue with tests").assertIsDisplayed()
        capture("reply-styles-ltr")
        compose.runOnIdle { direction = LayoutDirection.Rtl }
        compose.onNodeWithContentDescription("You: **Thanks**, continue.").assertIsDisplayed()
        capture("reply-styles-rtl")
    }
}
