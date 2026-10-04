package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class NativeFeedbackComposerTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val emitted = mutableListOf<NativeHaptic>()
    private var hapticsEnabled = true
    private val haptics = NativeHaptics({ hapticsEnabled }, emitted::add)
    private fun show(submit: suspend (String, String, NativeFeedbackStamp) -> Unit, owner: State<String> = mutableStateOf("first")) {
        compose.setContent { CompositionLocalProvider(LocalNativeHaptics provides haptics) { CmuxTheme { NativeFeedbackHost(owner.value, "reply@example.test", submit) {
            val open = checkNotNull(LocalNativeFeedback.current)
            Button(onClick = open) { Text("Open feedback") }
        } } } }
        compose.onNodeWithText("Open feedback").performClick()
    }
    @Test fun validatesAndSendsOnceWithTheReplyAddressAndBuildStamp() {
        val completion = CompletableDeferred<Unit>()
        val calls = mutableListOf<Pair<String, String>>()
        show({ email, message, stamp ->
            assertTrue(stamp.bundle.startsWith("io.github.docmorphic.cmuxapp")); assertTrue(stamp.os.startsWith("Android "))
            calls += email to message; completion.await()
        })
        compose.onNodeWithTag("feedback-send").assertIsNotEnabled()
        compose.onNodeWithTag("feedback-email").assertTextContains("reply@example.test")
        compose.onNodeWithTag("feedback-message").performTextInput("Keyboard issue 你好")
        compose.onNodeWithTag("feedback-send").performClick()
        compose.onNodeWithTag("feedback-send").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(listOf("reply@example.test" to "Keyboard issue 你好"), calls); completion.complete(Unit) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("feedback-message").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Feedback sent").assertExists()
        compose.runOnIdle { assertEquals(listOf(NativeHaptic.SUCCESS), emitted) }
    }
    @Test fun failureKeepsTheDraftAndAnExplicitRetryCanSucceed() {
        var calls = 0
        show({ _, _, _ -> if (++calls == 1) throw java.io.IOException("Try again later.") })
        compose.onNodeWithTag("feedback-message").performTextInput("Saved draft")
        compose.onNodeWithTag("feedback-send").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("feedback-error").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("feedback-message").assertTextContains("Saved draft")
        compose.runOnIdle { assertEquals(listOf(NativeHaptic.ERROR), emitted) }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try { File(directory, "feedback-composer.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
        compose.onNodeWithTag("feedback-send").performClick()
        compose.waitUntil(5000) { compose.onAllNodesWithTag("feedback-message").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertEquals(2, calls); assertEquals(listOf(NativeHaptic.ERROR, NativeHaptic.SUCCESS), emitted) }
    }
    @Test fun cancellingPendingSendAllowsANewComposerWithoutLateDismissal() {
        val completion = CompletableDeferred<Unit>()
        var cancelled = false
        show({ _, _, _ -> try { completion.await() } finally { cancelled = true } })
        compose.onNodeWithTag("feedback-message").performTextInput("First")
        compose.onNodeWithTag("feedback-send").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.waitUntil(5000) { cancelled }
        compose.onNodeWithText("Open feedback").performClick()
        compose.onNodeWithTag("feedback-message").performTextInput("Second")
        compose.runOnIdle { completion.complete(Unit) }
        compose.onNodeWithTag("feedback-message").assertTextContains("Second")
        compose.onNodeWithTag("feedback-send").assertIsEnabled()
        compose.runOnIdle { assertTrue(emitted.isEmpty()) }
    }
    @Test fun changingAccountClosesAndCancelsTheOldComposer() {
        val owner = mutableStateOf("first")
        var cancelled = false
        show({ _, _, _ -> try { CompletableDeferred<Unit>().await() } finally { cancelled = true } }, owner)
        compose.onNodeWithTag("feedback-message").performTextInput("Old account")
        compose.onNodeWithTag("feedback-send").performClick()
        compose.runOnIdle { owner.value = "second" }
        compose.waitUntil(5000) { cancelled && compose.onAllNodesWithTag("feedback-message").fetchSemanticsNodes().isEmpty() }
    }
    @Test fun disablingWhileSubmissionIsPendingSuppressesItsCompletion() {
        val completion = CompletableDeferred<Unit>()
        show({ _, _, _ -> completion.await() })
        compose.onNodeWithTag("feedback-message").performTextInput("Synthetic local feedback")
        compose.onNodeWithTag("feedback-send").performClick()
        compose.runOnIdle { hapticsEnabled = false; completion.complete(Unit) }
        compose.waitUntil(5000) { compose.onAllNodesWithTag("feedback-message").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Feedback sent").assertExists()
        compose.runOnIdle { assertTrue(emitted.isEmpty()); hapticsEnabled = true }
        compose.waitForIdle()
        compose.runOnIdle { assertTrue(emitted.isEmpty()) }
    }

}
