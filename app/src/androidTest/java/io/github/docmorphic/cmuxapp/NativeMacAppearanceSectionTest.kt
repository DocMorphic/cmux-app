package io.github.docmorphic.cmuxapp

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NativeMacAppearanceSectionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val target = NativeComputerTarget("appearance-mac", "default", "Original Mac")
    private val identity = NativeMacIdentity(target.deviceId, target.buildTag)
    private var disk: String? = null
    private val store = NativeMacAppearanceStore({ disk }, { disk = it })
    private fun content(save: suspend ((NativeMacAppearance) -> NativeMacAppearance) -> Unit = {
        store.update(identity, { true }, it)
    }) {
        compose.setContent {
            val state by store.state.collectAsState()
            CmuxTheme { Surface(Modifier.fillMaxSize().statusBarsPadding()) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    NativeMacAppearanceSection(target, state.get(target.deviceId, target.buildTag), state.error,
                        save = save, retry = { store.reload() })
                }
            } }
        }
    }

    @Test fun editsNameColorAndIconIndependentlyAndAutoRestoresEachField() {
        val other = identity.copy(buildTag = "debug")
        store.update(other, { true }) { NativeMacAppearance("Debug", "palette:7", "🐧") }
        content()
        compose.onNodeWithText("Name").performTextReplacement("Studio")
        compose.onNodeWithText("Save name").performClick()
        compose.waitUntil(5000) { store.state.value.values[identity]?.name == "Studio" }
        compose.onNodeWithContentDescription("Color palette 3").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Color palette 3").assertIsSelected()
        compose.onNodeWithContentDescription("Icon Heart").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Icon Heart").assertIsSelected()
        assertEquals(NativeMacAppearance("Studio", "palette:2", "heart.fill"), store.state.value.values[identity])
        compose.onNodeWithContentDescription("Auto color").performScrollTo().performClick()
        assertNull(store.state.value.values[identity]?.color)
        assertEquals("heart.fill", store.state.value.values[identity]?.icon)
        compose.onNodeWithContentDescription("Auto icon").performScrollTo().performClick()
        compose.onNodeWithText("Name").performScrollTo().performTextReplacement("")
        compose.onNodeWithText("Save name").performClick()
        compose.waitUntil(5000) { identity !in store.state.value.values }
        assertEquals(NativeMacAppearance("Debug", "palette:7", "🐧"), store.state.value.values[other])
    }

    @Test fun customRgbAndEmojiValidateThenPersistAndRender() {
        content()
        compose.onNodeWithContentDescription("Custom color").performScrollTo().performClick()
        compose.onNodeWithText("Hex color").performTextReplacement("#GG0000")
        compose.onNodeWithText("Use color").assertIsNotEnabled()
        compose.onNodeWithText("Hex color").performTextReplacement("#12AB34")
        compose.onNodeWithText("Use color").performClick()
        compose.waitUntil(5000) { store.state.value.values[identity]?.color == "#12AB34" }
        compose.onNodeWithText("Custom emoji").performScrollTo().performTextReplacement("not-an-emoji")
        compose.onNodeWithText("Use emoji").performScrollTo().performClick()
        compose.onNodeWithText("Enter an emoji or choose an icon above.").performScrollTo().assertIsDisplayed()
        assertNull(store.state.value.values[identity]?.icon)
        compose.onNodeWithText("Custom emoji").performScrollTo().performTextReplacement("🧑🏽‍💻")
        compose.onNodeWithText("Use emoji").performClick()
        compose.waitUntil(5000) { store.state.value.values[identity]?.icon == "🧑🏽‍💻" }
        compose.onNodeWithText("APPEARANCE").performScrollTo()
        capture("computer-appearance-custom")
        val restored = NativeMacAppearanceStore({ disk }, {})
        assertEquals(store.state.value, restored.state.value)
    }

    @Test fun pendingAndFailedWriteDoNotInventSavedStateAndCanRetry() {
        val gate = CompletableDeferred<Unit>()
        var attempts = 0
        content { change ->
            attempts++
            if (attempts == 1) { gate.await(); error("Disk write failed") }
            store.update(identity, { true }, change)
        }
        compose.onNodeWithText("Name").performTextReplacement("New name")
        compose.onNodeWithText("Save name").performClick()
        compose.onNodeWithText("Save name").assertIsNotEnabled().performClick()
        compose.onNodeWithContentDescription("Color palette 1").assertIsNotEnabled()
        compose.runOnIdle { assertEquals(1, attempts); assertNull(store.state.value.values[identity]); gate.complete(Unit) }
        compose.onNodeWithText("Could not save appearance. Check your account and try again.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Save name").performScrollTo().performClick()
        compose.waitUntil(5000) { store.state.value.values[identity]?.name == "New name" }
        assertEquals(2, attempts)
    }

    @Test fun realDetailEditorUpdatesHeaderAndReopensWithoutDialing() = runBlocking<Unit> {
        val team = NativeTeamScope("test", "appearance-${java.util.UUID.randomUUID()}", "fixture-team", 1)
        val teams = MutableStateFlow(NativeAccountTeamsState(scope = team))
        val mac = IrohV2Computer("r", "ab".repeat(32), target.deviceId, target.buildTag, target.name, emptyList())
        val backend = object : IrohAccountBackend {
            override val state = MutableStateFlow(IrohV2ControlState(ready = true, computers = listOf(mac), permissionExpiresAt = 2000))
            override suspend fun start() { }
            override suspend fun refresh() { }
            override fun transport(mac: IrohV2Computer, permits: () -> Boolean): MobileRpcTransport = error("Appearance must not dial")
            override fun close() { }
        }
        NativeIrohRuntime(teams, { teams.value.scope == it }, { "fixture-token" }, { _, _ -> backend }, { 1000 }).use { runtime ->
            withTimeout(3000) { runtime.state.first { it.ready } }
            compose.setContent { CmuxTheme { NativeComputerDetailsButton(runtime, runtime.state.value, target) } }
            compose.onNodeWithContentDescription("Details for Original Mac (default)").performClick()
            compose.onNodeWithText("Name").performScrollTo().performTextReplacement("Studio UI")
            compose.onNodeWithText("Save name").performClick()
            compose.waitUntil(5000) { compose.onAllNodesWithText("Studio UI").fetchSemanticsNodes().size >= 2 }
            compose.onNodeWithText("‹  Back").performClick()
            compose.onNodeWithContentDescription("Details for Studio UI (default)").performClick()
            compose.onNodeWithText("Name").performScrollTo().assertTextContains("Studio UI")
            capture("computer-appearance-details")
            compose.onNodeWithText("‹  Back").performClick()
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = androidx.test.platform.app.InstrumentationRegistry.getInstrumentation()
        val root = instrumentation.targetContext.getExternalFilesDir(null)!!
        instrumentation.uiAutomation.takeScreenshot().let { bitmap ->
            java.io.File(root, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
    }
}
