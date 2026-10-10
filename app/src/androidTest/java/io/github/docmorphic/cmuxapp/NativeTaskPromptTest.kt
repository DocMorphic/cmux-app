package io.github.docmorphic.cmuxapp

import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.util.UUID

@OptIn(ExperimentalTestApi::class)
class NativeTaskPromptTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>(effectContext = StandardTestDispatcher())
    private lateinit var peer: NativeFixturePeer
    private lateinit var client: MobileRpcClient
    private val drafts = TaskDrafts()
    private val id = UUID.randomUUID().toString()
    @Volatile private var input: androidx.compose.ui.platform.PlatformTextInputMethodRequest? = null

    private class Owner : LifecycleOwner {
        val registry = LifecycleRegistry(this)
        override val lifecycle: Lifecycle get() = registry
    }
    @Before fun start() {
        compose.activity.runOnUiThread {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" })
        runBlocking { client.connect(); client.hostStatus() }
    }
    @After fun close() { compose.activity.finish(); client.close(); peer.close() }

    private fun show(owner: LifecycleOwner = compose.activity, restore: StateRestorationTester? = null) {
        val content: @Composable () -> Unit = {
            CompositionLocalProvider(LocalLifecycleOwner provides owner) {
                CaptureComposerInput({ input = it }) { CmuxTheme { Surface(Modifier.fillMaxSize()
                    .statusBarsPadding().navigationBarsPadding().imePadding()) {
                    NativeTaskComposerView(client, listOf("/repo"), "prompt-mac", remember { TaskModelRepository() }, {}, {},
                        savedDrafts = drafts, draftId = id, catalog = { awaitCancellation() })
                } } }
            }
        }
        if (restore == null) compose.setContent(content) else restore.setContent(content)
    }
    private fun selection() = compose.onNodeWithContentDescription("Task prompt").fetchSemanticsNode()
        .config[SemanticsProperties.TextSelectionRange]

    @Test fun initialFocusWaitsForResumeAndDoesNotReturnAfterOptions() {
        lateinit var owner: Owner
        compose.runOnUiThread { owner = Owner().also { it.registry.currentState = Lifecycle.State.STARTED } }
        show(owner)
        compose.onNodeWithContentDescription("Task prompt").assertIsNotFocused()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.waitUntil(10_000) {
            compose.onNodeWithContentDescription("Task prompt").fetchSemanticsNode().config[SemanticsProperties.Focused] &&
                androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                    ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true
        }
        compose.onNodeWithContentDescription("Task Options").performClick()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Task prompt").assertIsNotFocused()
        compose.runOnUiThread { owner.registry.currentState = Lifecycle.State.STARTED; owner.registry.currentState = Lifecycle.State.RESUMED }
        compose.onNodeWithContentDescription("Task prompt").assertIsNotFocused()
        assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
    }

    @Test fun selectionSurvivesAgentOptionsAndSavedStateWithoutRefocusing() {
        val editor = drafts.begin(id, "prompt-mac", "Mac", "/repo")
        drafts.edit(editor) { it.copy(prompt = "hello world 👩🏽‍💻") }; drafts.end(editor)
        val restore = StateRestorationTester(compose)
        show(restore = restore)
        compose.waitUntil(10_000) { input != null }
        compose.runOnIdle { assertTrue(input!!.createInputConnection(EditorInfo()).setSelection(6, 11)) }
        compose.waitUntil(10_000) { selection() == TextRange(6, 11) }
        compose.openTaskPicker("Agent")
        compose.onNodeWithText("Codex", useUnmergedTree = true).performClick()
        assertEquals(TextRange(6, 11), selection())
        compose.onNodeWithContentDescription("Task Options").performClick()
        compose.onNodeWithText("Done").performClick()
        assertEquals(TextRange(6, 11), selection())
        compose.onNodeWithContentDescription("Task prompt").assertIsNotFocused()
        restore.emulateSavedInstanceStateRestore()
        assertEquals(TextRange(6, 11), selection())
        compose.onNodeWithContentDescription("Task prompt").assertIsNotFocused()
        compose.onNodeWithContentDescription("Task prompt").performTextInput("中")
        compose.waitUntil(10_000) { drafts.state.value[id]?.prompt == "hello 中 👩🏽‍💻" }
        assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
    }
}
