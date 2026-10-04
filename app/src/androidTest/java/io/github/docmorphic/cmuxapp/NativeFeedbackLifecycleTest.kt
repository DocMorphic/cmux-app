package io.github.docmorphic.cmuxapp

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class NativeFeedbackLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private var scenario: ActivityScenario<NativeFeedbackLifecycleTestActivity>? = null
    private val emitted = java.util.concurrent.CopyOnWriteArrayList<NativeHaptic>()
    private fun launch(submit: suspend (String, String, NativeFeedbackStamp) -> Unit) {
        NativeFeedbackLifecycleTestActivity.submit = submit
        NativeFeedbackLifecycleTestActivity.haptics = NativeHaptics({ true }, emitted::add)
        scenario = ActivityScenario.launch(NativeFeedbackLifecycleTestActivity::class.java)
        compose.onNodeWithText("Open feedback").performClick()
        compose.onNodeWithTag("feedback-message").performTextInput("Rotate draft 你好")
        compose.onNodeWithTag("feedback-email").performTextReplacement("changed@example.test")
    }
    @After fun cleanup() { scenario?.close(); NativeFeedbackLifecycleTestActivity.submit = null; NativeFeedbackLifecycleTestActivity.haptics = null }
    @Test fun activityRecreationPreservesDraftAndEmailWithoutSending() {
        val calls = AtomicInteger()
        launch { _, _, _ -> calls.incrementAndGet() }
        scenario!!.recreate()
        compose.onNodeWithTag("feedback-message").assertTextContains("Rotate draft 你好")
        compose.onNodeWithTag("feedback-email").assertTextContains("changed@example.test")
        assertEquals(0, calls.get())
        compose.onNodeWithTag("feedback-send").performClick()
        compose.waitUntil(5000) { calls.get() == 1 && compose.onAllNodesWithTag("feedback-message").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun pendingSendSurvivesRecreationOnceAndReportsItsResult() {
        val calls = AtomicInteger(); val completed = CompletableDeferred<Unit>(); val ended = AtomicBoolean()
        launch { email, message, _ ->
            calls.incrementAndGet(); assertEquals("changed@example.test", email); assertEquals("Rotate draft 你好", message)
            try { completed.await() } finally { ended.set(true) }
        }
        compose.onNodeWithTag("feedback-send").performClick()
        compose.waitUntil(5000) { calls.get() == 1 }
        scenario!!.recreate()
        compose.onNodeWithTag("feedback-send").assertIsNotEnabled()
        assertFalse(ended.get()); assertEquals(1, calls.get())
        completed.complete(Unit)
        compose.waitUntil(5000) { compose.onAllNodesWithTag("feedback-message").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Feedback sent").assertExists()
        assertEquals(1, calls.get())
        compose.runOnIdle { assertEquals(listOf(NativeHaptic.SUCCESS), emitted.toList()) }
        scenario!!.recreate()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(listOf(NativeHaptic.SUCCESS), emitted.toList()) }
    }
    @Test fun failedSendRetainsErrorAndDraftAcrossRecreationUntilExplicitRetry() {
        val calls = AtomicInteger()
        launch { _, _, _ -> if (calls.incrementAndGet() == 1) throw java.io.IOException("Fixture offline") }
        compose.onNodeWithTag("feedback-send").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("feedback-error").fetchSemanticsNodes().isNotEmpty() }
        scenario!!.recreate()
        compose.onNodeWithTag("feedback-error").assertTextEquals("Fixture offline")
        compose.onNodeWithTag("feedback-message").assertTextContains("Rotate draft 你好")
        assertEquals(1, calls.get())
        compose.runOnIdle { assertEquals(listOf(NativeHaptic.ERROR), emitted.toList()) }
        compose.onNodeWithTag("feedback-send").performClick()
        compose.waitUntil(5000) { calls.get() == 2 && compose.onAllNodesWithTag("feedback-message").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(listOf(NativeHaptic.ERROR, NativeHaptic.SUCCESS), emitted.toList()) }
    }
}
