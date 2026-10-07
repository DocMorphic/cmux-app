package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class AgentFeedQuestionControlsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val questions = listOf(
        AgentFeedQuestion("color", "Appearance", "Which color?", false,
            listOf(AgentFeedOption("blue", "Blue", null), AgentFeedOption("red", "Red", null))),
        AgentFeedQuestion("checks", "Validation", "Which checks?", true,
            listOf(AgentFeedOption("unit", "Unit tests", "Check behavior."),
                AgentFeedOption("device", "Device tests", "Check the visible interface on Android.\nCheck keyboard input.\nCheck interrupted gestures.")))
    )
    private fun capture(name: String) {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "agent-feed-questions").apply { mkdirs() }
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
    private fun item() = NativeAgentFeedItem("questions", "turn", "Claude", AgentFeedKind.QUESTION,
        AgentFeedStatus.PENDING, 100.0, 100.0, requestId = "request", questions = questions)
    private fun option(question: String, option: String) = compose.onNodeWithTag("AgentFeedQuestionOption:$question:$option")

    @Test fun inlinePagesRestoreDraftsRequireAllAnswersAndResetForChangedRequests() {
        val restoration = StateRestorationTester(compose)
        var value by mutableStateOf(item())
        var enabled by mutableStateOf(true)
        var connected by mutableStateOf(true)
        val sent = mutableListOf<AgentFeedDecision>()
        restoration.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()
            .verticalScroll(rememberScrollState()).padding(16.dp)) {
            AgentFeedQuestionControls(value, enabled, canSubmit = connected) { sent += it }
        } } } }
        compose.onNodeWithText("Question 1 of 2").assertIsDisplayed()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithTag("AgentFeedQuestionSubmit").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Previous").performScrollTo().performClick()
        option("color", "blue").performScrollTo().performClick()
        option("color", "blue").assertIsSelected()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithTag("AgentFeedQuestionOther:checks").performScrollTo().performClick()
        compose.onNodeWithTag("AgentFeedQuestionOtherText:checks").performScrollTo().performTextInput("Manual verification")
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Question 2 of 2").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("AgentFeedQuestionOtherText:checks").performScrollTo().assertTextContains("Manual verification")
        compose.runOnIdle { assertTrue(sent.isEmpty()); enabled = false }
        compose.onNodeWithTag("AgentFeedQuestionSubmit").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle { enabled = true }
        option("checks", "device").performScrollTo().performClick()
        option("checks", "unit").performScrollTo().performClick()
        compose.onNodeWithTag("AgentFeedQuestionOtherText:checks").assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { connected = false }
        compose.onNodeWithTag("AgentFeedQuestionSubmit").performScrollTo().assertIsNotEnabled()
        option("checks", "unit").performScrollTo().assertIsEnabled()
        compose.runOnIdle { connected = true }
        compose.onNodeWithTag("AgentFeedQuestionSubmit").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(listOf("Blue", "Unit tests, Device tests"), sent.single().selections)
            value = value.copy(requestId = "replacement") }
        compose.onNodeWithText("Question 1 of 2").performScrollTo().assertIsDisplayed()
        option("color", "blue").assertIsNotSelected()
        compose.onNodeWithText("Next").performScrollTo().performClick()
        compose.onNodeWithTag("AgentFeedQuestionSubmit").performScrollTo().assertIsNotEnabled()
    }

    @Test fun horizontalQuestionPagingDoesNotTriageTheEnclosingFeedRowAndUsesPageHeight() {
        val item = item()
        val mac = NativeCredentialStore.PairedMac("fixture", "mac", "Mac")
        val entry = NativeAgentFeedEntry(NativeFeedSource(mac, availability = NativeFeedAvailability.CONNECTED,
            capabilities = setOf(AGENT_FEED_CAPABILITY)), item)
        var triages = 0; var opened = 0
        compose.setContent { CmuxTheme { Surface { Column(Modifier.fillMaxSize().safeDrawingPadding()
            .verticalScroll(rememberScrollState())) {
            NativeAgentFeedRow(entry.ui("Mac", true), NativeAgentFeedPresentation.from(item), true, NativeDisplayPreferences(), "now",
                { triages++ }, { opened++ }, {}, {}, {})
        } } } }
        val pager = compose.onNodeWithTag("AgentFeedQuestionPager", useUnmergedTree = true)
        pager.performScrollTo()
        val shortHeight = pager.fetchSemanticsNode().boundsInRoot.height
        capture("question-one")
        pager.performTouchInput { swipeLeft() }
        compose.onNodeWithText("Question 2 of 2").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, triages); assertEquals(0, opened) }
        assertTrue(pager.fetchSemanticsNode().boundsInRoot.height > shortHeight)
        capture("question-two")
        pager.performTouchInput { swipeRight() }
        compose.onNodeWithText("Question 1 of 2").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, triages); assertEquals(0, opened) }
    }
}
