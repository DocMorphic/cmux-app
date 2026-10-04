package io.github.docmorphic.cmuxapp

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class NativeHapticsTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingPersistsAndImmediatelyGatesCopyAndComposeFeedback() {
        val name = "haptic-test-${UUID.randomUUID()}"
        val preferences = compose.activity.getSharedPreferences(name, Context.MODE_PRIVATE)
        val emitted = mutableListOf<NativeHaptic>()
        val policy = NativeHaptics({ NativeDisplayPreferences.read(preferences).hapticFeedbackEnabled }, emitted::add)
        var toolkitCalls = 0
        val platform = object : HapticFeedback {
            override fun performHapticFeedback(hapticFeedbackType: HapticFeedbackType) { toolkitCalls++ }
        }
        var generation by mutableIntStateOf(0)
        var sheet by mutableStateOf(false)
        try {
            compose.setContent {
                CompositionLocalProvider(LocalNativeHaptics provides policy, LocalHapticFeedback provides platform) {
                    CmuxTheme { Surface(Modifier.fillMaxSize()) { key(generation) {
                        val state = rememberNativeDisplayPreferences(preferences)
                        val toolkit = LocalHapticFeedback.current
                        Column(Modifier.safeDrawingPadding()) {
                            NativeHapticSettings(preferences, state)
                            Button(onClick = { toolkit.performHapticFeedback(HapticFeedbackType.LongPress) }) { Text("Select text") }
                            Button(onClick = { sheet = true }) { Text("Open text") }
                        }
                        if (sheet) TerminalTextSheet(TerminalTextSnapshot("Synthetic terminal text", false, 10)) { sheet = false }
                    } } }
                }
            }
            compose.onNodeWithTag("settings.haptics").assertIsOn()
            assertFalse(preferences.contains(NativeDisplayPreferences.hapticsKey))
            compose.onNodeWithText("Select text").performClick()
            compose.onNodeWithText("Open text").performClick()
            compose.onNodeWithText("Copy All").performClick()
            compose.onNodeWithText("Done").performClick()
            compose.runOnIdle { assertEquals(1, toolkitCalls); assertEquals(listOf(NativeHaptic.SUCCESS), emitted) }
            compose.onNodeWithTag("settings.haptics").performClick()
            compose.onNodeWithTag("settings.haptics").assertIsOff()
            compose.runOnIdle { generation++ }
            compose.onNodeWithTag("settings.haptics").assertIsOff()
            compose.onNodeWithText("Select text").performClick()
            compose.onNodeWithText("Open text").performClick()
            compose.onNodeWithText("Copy All").performClick()
            compose.onNodeWithText("Copied").assertExists()
            compose.onNodeWithText("Done").performClick()
            compose.runOnIdle { assertEquals(1, toolkitCalls); assertEquals(listOf(NativeHaptic.SUCCESS), emitted) }
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val directory = File(instrumentation.targetContext.getExternalFilesDir(null), "haptics").apply { mkdirs() }
            instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
                File(directory, "settings-off.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }
            compose.onNodeWithTag("settings.haptics").performClick()
            compose.onNodeWithText("Select text").performClick()
            compose.runOnIdle { assertEquals(2, toolkitCalls) }
            compose.runOnIdle { preferences.edit().putString(NativeDisplayPreferences.hapticsKey, "wrong type").commit() }
            compose.onNodeWithTag("settings.haptics").assertIsOn()
            compose.runOnIdle { policy.perform(NativeHaptic.WARNING); assertEquals(NativeHaptic.WARNING, emitted.last()) }
        } finally { compose.activity.deleteSharedPreferences(name) }
    }

    @Test fun checklistActionsAndErrorsUseTheSameLivePolicy() {
        var enabled = true
        val emitted = mutableListOf<NativeHaptic>()
        val policy = NativeHaptics({ enabled }, emitted::add)
        val initial = TodoSnapshot(TodoStatus.REVIEW, false, listOf(TodoItem("a", "Review changes", TodoItemState.PENDING, "agent")))
        var calls = 0
        compose.setContent { CompositionLocalProvider(LocalNativeHaptics provides policy) { CmuxTheme { Surface {
            NativeTodoView(initial, true) { calls++; throw java.io.IOException("Local fixture failure") }
        } } } }
        compose.onNodeWithContentDescription("Mark Review changes as in progress").performClick()
        compose.waitUntil { emitted.size == 2 }
        compose.runOnIdle { assertEquals(listOf(NativeHaptic.LIGHT, NativeHaptic.ERROR), emitted); enabled = false }
        compose.onNodeWithText("OK").performClick()
        compose.onNodeWithTag("todo-new-item").performTextInput("New item")
        compose.onNodeWithContentDescription("Add checklist item").performClick()
        compose.waitForIdle()
        compose.runOnIdle { assertEquals(2, calls); assertEquals(2, emitted.size) }
    }
}
