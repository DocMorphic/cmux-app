package io.github.docmorphic.cmuxapp

import android.view.inputmethod.EditorInfo
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import kotlinx.coroutines.test.StandardTestDispatcher
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.io.File
import androidx.test.platform.app.InstrumentationRegistry

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
        System.setProperty("cmux.prompt.viewport.trace", "true")
        compose.activity.runOnUiThread {
            androidx.core.view.WindowCompat.setDecorFitsSystemWindows(compose.activity.window, false)
            compose.activity.window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        peer = NativeFixturePeer()
        client = MobileRpcClient(PairingCode.Route("127.0.0.1", peer.port), { "fixture" })
        runBlocking { client.connect(); client.hostStatus() }
    }
    @After fun close() { compose.activity.finish(); client.close(); peer.close(); System.clearProperty("cmux.prompt.viewport.trace") }

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

    private fun promptPaint(name: String): ImageBitmap {
        compose.waitForIdle()
        compose.waitUntil(10_000) {
            !compose.onNodeWithContentDescription("Task prompt").fetchSemanticsNode().config[TaskPromptScrolling]
        }
        var previous: ImageBitmap? = null
        var image: ImageBitmap? = null
        var stable = 0
        // Native scrolling can continue after Compose reports idle. Compare
        // settled painted frames so a legitimate fling is not called a reset.
        compose.waitUntil(10_000) {
            val next = compose.onNodeWithContentDescription("Task prompt").captureToImage()
            stable = if (previous?.let { paintedDifference(it, next) < 10 } == true) stable + 1 else 0
            previous = next; image = next
            stable >= 3
        }
        val accepted = checkNotNull(image)
        val folder = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "task-prompt-viewport").apply { mkdirs() }
        File(folder, "$name.png").outputStream().use {
            accepted.asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        return accepted
    }
    private fun paintedDifference(before: ImageBitmap, after: ImageBitmap): Int {
        assertEquals(before.width, after.width)
        val a = before.toPixelMap(); val b = after.toPixelMap()
        val height = minOf(before.height, after.height, 500)
        var changed = 0
        for (y in 4 until height - 4) for (x in 4 until before.width - 4) {
            val left = a[x, y]; val right = b[x, y]
            if (kotlin.math.abs(left.red - right.red) + kotlin.math.abs(left.green - right.green) +
                kotlin.math.abs(left.blue - right.blue) > 0.25f) changed++
        }
        return changed
    }

    @Test fun manuallyScrolledPromptSurvivesAgentOptionsAndSavedStateUntilTyping() {
        val text = (1..100).joinToString("\n") { "Line $it — ${('A'.code + it % 26).toChar()} task details 中" }
        val editor = drafts.begin(id, "prompt-mac", "Mac", "/repo")
        drafts.edit(editor) { it.copy(prompt = text) }; drafts.end(editor)
        val restore = StateRestorationTester(compose)
        show(restore = restore)
        compose.waitUntil(10_000) { input != null &&
            androidx.core.view.ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
                ?.isVisible(androidx.core.view.WindowInsetsCompat.Type.ime()) == true }
        compose.runOnIdle { assertTrue(input!!.createInputConnection(EditorInfo()).setSelection(text.length, text.length)) }
        compose.waitUntil(10_000) { selection() == TextRange(text.length) }
        val caretPaint = promptPaint("caret")
        repeat(3) { compose.onNodeWithContentDescription("Task prompt").performTouchInput { swipeDown(durationMillis = 700) } }
        val manual = promptPaint("manual")
        assertTrue("Drag must actually change the painted viewport", paintedDifference(caretPaint, manual) > 1000)
        assertEquals(TextRange(text.length), selection())
        compose.openTaskPicker("Agent")
        compose.onNodeWithText("Codex", useUnmergedTree = true).performClick()
        assertTrue("Agent refresh must retain manual viewport", paintedDifference(manual, promptPaint("agent")) < 500)
        compose.openTaskOptions()
        compose.onNodeWithText("Done").performClick()
        assertTrue("Options must retain manual viewport", paintedDifference(manual, promptPaint("options")) < 500)
        restore.emulateSavedInstanceStateRestore()
        assertTrue("Saved state must retain manual viewport", paintedDifference(manual, promptPaint("restored")) < 500)
        assertEquals(TextRange(text.length), selection())
        compose.onNodeWithContentDescription("Task prompt").performTextInput("\nTyped final marker")
        compose.waitUntil(10_000) { drafts.state.value[id]?.prompt == "$text\nTyped final marker" }
        assertTrue("Typing must resume following the caret", paintedDifference(manual, promptPaint("typed")) > 1000)
        assertTrue(peer.requests.none { it.optString("method") == "workspace.create" })
    }

    @Test fun lastPromptEditIsSubmittedBeforeRecomposition() {
        val editor = drafts.begin(id, "prompt-mac", "Mac", "/repo")
        drafts.edit(editor) { it.copy(prompt = "Before") }; drafts.end(editor)
        show()
        val insert = compose.onNodeWithContentDescription("Task prompt").fetchSemanticsNode()
            .config[SemanticsActions.InsertTextAtCursor].action!!
        val submit = compose.onNodeWithContentDescription("Create Task").fetchSemanticsNode()
            .config[SemanticsActions.OnClick].action!!
        // Both native UI actions run in one main-thread turn, before collected
        // draft state can recompose the create button's callback.
        compose.runOnIdle { assertTrue(insert(AnnotatedString("Latest 中\n"))); assertTrue(submit()) }
        compose.waitUntil(10_000) { peer.requests.any { it.optString("method") == "workspace.create" } }
        val params = peer.requests.single { it.optString("method") == "workspace.create" }.getJSONObject("params")
        assertEquals("Latest 中\nBefore", params.getJSONObject("initial_env").getString("CMUX_TASK_PROMPT"))
    }

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
        compose.openTaskOptions()
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
        compose.openTaskOptions()
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
