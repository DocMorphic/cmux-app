package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.text.AnnotatedString
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import android.view.inspector.WindowInspector
import java.util.concurrent.atomic.AtomicBoolean
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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
    private fun capture(name: String, keyboard: Boolean = false) {
        // Semantic actions can pass behind a system ANR dialog. Require real window
        // focus and, for the editor screenshot, a visible IME before accepting proof.
        var readySince = 0L
        var previousInset = -1
        var keyboardTop = Int.MAX_VALUE
        compose.waitUntil(10_000) {
            val ready = AtomicBoolean(false)
            compose.runOnUiThread {
                val view = WindowInspector.getGlobalWindowViews().firstOrNull { it.hasWindowFocus() }
                val insets = view?.let(ViewCompat::getRootWindowInsets)
                val bottom = insets?.getInsets(WindowInsetsCompat.Type.ime())?.bottom ?: 0
                val visible = view != null && (!keyboard || (insets?.isVisible(WindowInsetsCompat.Type.ime()) == true && bottom > view.height / 5))
                val now = android.os.SystemClock.uptimeMillis()
                if (!visible || bottom != previousInset) readySince = now
                if (readySince == 0L) readySince = now
                previousInset = bottom
                if (view != null && keyboard) keyboardTop = view.height - bottom
                // Insets announce visibility before the IME is painted. Wait for
                // stable visible geometry before the screenshot, not just a flag.
                ready.set(visible && now - readySince >= 500)
            }
            ready.get()
        }
        compose.waitForIdle()
        var geometry = ""
        if (keyboard) {
            // Fetch semantics before capture (it flushes pending Compose frames),
            // then require stable editor placement, not just stable native insets.
            var stableSince = 0L
            var lastBounds: androidx.compose.ui.geometry.Rect? = null
            compose.waitUntil(10_000) {
                val node = editor("checks").fetchSemanticsNode()
                val bounds = node.boundsInWindow
                val visible = bounds.bottom <= keyboardTop && bounds.height >= node.size.height - 1 && bounds.height > 0
                val now = android.os.SystemClock.uptimeMillis()
                if (!visible || bounds != lastBounds) stableSince = now
                lastBounds = bounds
                geometry = "editor=$bounds, size=${node.size}, keyboardTop=$keyboardTop"
                visible && now - stableSince >= 500
            }
        }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "agent-feed-questions").apply { mkdirs() }
        if (keyboard) java.io.File(folder, "$name-geometry.txt").writeText(geometry)
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(folder, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        if (keyboard) {
            val node = editor("checks").fetchSemanticsNode()
            val bounds = node.boundsInWindow
            assertTrue("Custom editor is behind the keyboard: $bounds, size=${node.size}, keyboard top=$keyboardTop",
                bounds.bottom <= keyboardTop && bounds.height >= node.size.height - 1 && bounds.height > 0)
        }
    }
    private fun item() = NativeAgentFeedItem("questions", "turn", "Claude", AgentFeedKind.QUESTION,
        AgentFeedStatus.PENDING, 100.0, 100.0, requestId = "request", questions = questions)
    private fun entry() = AgentFeedUiEntry("question", AgentFeedUiOwner("mac", "build"), item(), "Mac", true, false, null, true)
    private fun option(question: String, option: String) = compose.onNodeWithTag("AgentFeedQuestionOption:$question:$option")
    private fun editor(question: String) = compose.onNodeWithTag("AgentFeedQuestionOtherText:$question")

    @Test fun allPromptSheetRetainsInactiveCustomAnswersAndGatesSubmission() {
        var entry by mutableStateOf(entry())
        var modal by mutableStateOf(AgentFeedModal.from("account", entry, "question"))
        val sent = mutableListOf<AgentFeedDecision>()
        compose.setContent { CmuxTheme { Surface { AgentFeedQuestionSheet(entry, modal, { modal = it }, {}) { sent += it } } } }
        compose.onNodeWithText("Answer questions").assertIsDisplayed()
        compose.onNodeWithTag("AgentFeedQuestionSubmit").assertIsNotEnabled()
        capture("answer-sheet")
        option("color", "blue").performScrollTo().performClick()
        option("color", "blue").performClick().assertIsSelected()
        editor("checks").performScrollTo().performClick().performTextInput("Manual verification\n👩🏽‍💻")
        editor("checks").assertIsDisplayed()
        capture("custom-answer-keyboard", keyboard = true)
        compose.onNodeWithTag("AgentFeedQuestionSubmit").assertIsEnabled()
        option("checks", "device").performScrollTo().performClick()
        option("checks", "unit").performScrollTo().performClick()
        editor("checks").performScrollTo().assertTextContains("Manual verification\n👩🏽‍💻")
        compose.onNodeWithTag("AgentFeedQuestionOther:checks").assertIsNotSelected().performClick().assertIsSelected()
        option("checks", "device").assertIsNotSelected()
        // Focusing an empty custom answer immediately clears a preset and disables Submit.
        editor("color").performScrollTo().performClick().assertIsFocused()
        compose.onNodeWithTag("AgentFeedQuestionSubmit").assertIsNotEnabled()
        option("color", "blue").assertIsNotSelected().performScrollTo().performClick()
        compose.runOnIdle { entry = entry.copy(connected = false) }
        compose.onNodeWithTag("AgentFeedQuestionSubmit").assertIsNotEnabled()
        editor("checks").assertIsEnabled()
        compose.runOnIdle { entry = entry.copy(connected = true, pending = true) }
        compose.onNodeWithTag("AgentFeedQuestionSubmit").assertIsNotEnabled()
        editor("checks").assertIsNotEnabled()
        compose.runOnIdle { entry = entry.copy(pending = false); assertTrue(sent.isEmpty()) }
        compose.onNodeWithTag("AgentFeedQuestionSubmit").performClick()
        compose.runOnIdle { assertEquals(listOf("Blue", "Manual verification\n👩🏽‍💻"), sent.single().selections) }
    }

    @Test fun timelineRestoresDraftWhileWaitingAndDropsItForChangedPromptOrCancel() {
        val restoration = StateRestorationTester(compose)
        var entry by mutableStateOf(entry())
        var waiting by mutableStateOf(false)
        val sent = mutableListOf<AgentFeedDecision>()
        var opened = 0; var triages = 0
        val actions = object : AgentFeedTimelineActions {
            override suspend fun decide(entry: AgentFeedUiEntry, decision: AgentFeedDecision): Boolean { sent += decision; return true }
            override suspend fun reply(entry: AgentFeedUiEntry, text: String) = error("Unexpected reply")
            override suspend fun fullText(entry: AgentFeedUiEntry) = error("Unexpected full text")
            override fun read(entry: AgentFeedUiEntry, needsInput: Boolean?) { triages++ }
            override fun open(entry: AgentFeedUiEntry, tab: Boolean) { opened++ }
        }
        restoration.setContent { CmuxTheme { Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            AgentFeedTimeline(AgentFeedUiSnapshot(if (waiting) emptyList() else listOf(entry), setOf(entry.owner),
                if (waiting) emptySet() else setOf(entry.owner), !waiting, false, false, true, false),
                "", false, actions, {}, scopeKey = "account")
        } } }
        compose.onNodeWithText("2 questions").assertIsDisplayed()
        option("color", "blue").assertDoesNotExist()
        capture("question-preview")
        compose.onNodeWithTag("AgentFeedQuestionAnswer").performClick()
        compose.runOnIdle { assertEquals(0, opened); assertEquals(0, triages) }
        option("color", "blue").performScrollTo().performClick()
        editor("checks").performScrollTo().performTextInput("Keep this draft")
        option("checks", "unit").performScrollTo().performClick()
        compose.runOnIdle { waiting = true }
        compose.onNodeWithText("Waiting for this Mac’s Feed").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Waiting for this Mac’s Feed").assertIsDisplayed()
        compose.runOnIdle { waiting = false }
        editor("checks").performScrollTo().assertTextContains("Keep this draft")
        option("checks", "unit").performScrollTo().assertIsSelected()
        option("color", "blue").performScrollTo().assertIsSelected()
        compose.runOnIdle { assertTrue(sent.isEmpty()); entry = entry.copy(item = entry.item.copy(
            questions = questions.map { if (it.id == "color") it.copy(prompt = "A different color request") else it })) }
        compose.onNodeWithTag("AgentFeedQuestionSheet").assertDoesNotExist()
        compose.onNodeWithTag("AgentFeedQuestionAnswer").performClick()
        option("color", "blue").assertIsNotSelected()
        editor("checks").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        editor("checks").performTextInput("Discard on cancel")
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("AgentFeedQuestionAnswer").performClick()
        editor("checks").performScrollTo().assert(SemanticsMatcher.expectValue(SemanticsProperties.EditableText, AnnotatedString("")))
        compose.runOnIdle { assertTrue(sent.isEmpty()) }
    }

    @Test fun questionWithoutOptionsAcceptsFreeTextAndQuoteBubblesRespectStoredPreference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferences = context.getSharedPreferences("question-display-test", 0)
        try {
            preferences.edit().clear().commit()
            assertTrue(NativeDisplayPreferences().feedBubbleQuotes)
            assertTrue(NativeDisplayPreferences.read(preferences).feedBubbleQuotes)
            preferences.edit().putBoolean(NativeDisplayPreferences.feedBubblesKey, false).commit()
            assertFalse(NativeDisplayPreferences.read(preferences).feedBubbleQuotes)
            val entry = entry().let { it.copy(item = it.item.copy(questions = listOf(questions.first().copy(options = emptyList())))) }
            var modal by mutableStateOf(AgentFeedModal.from("account", entry, "question"))
            var sent: AgentFeedDecision? = null
            compose.setContent { CmuxTheme { AgentFeedQuestionSheet(entry, modal, { modal = it }, {}) { sent = it } } }
            editor("color").performTextInput("  Free answer  ")
            compose.onNodeWithTag("AgentFeedQuestionSubmit").performClick()
            compose.runOnIdle { assertEquals(listOf("Free answer"), sent?.selections) }
        } finally { preferences.edit().clear().commit() }
    }
}
