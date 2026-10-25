package io.github.docmorphic.cmuxapp

import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.InterceptPlatformTextInput
import androidx.compose.ui.platform.PlatformTextInputInterceptor
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextRange
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Actual production state-based field and wrapped Foundation InputConnection, on an AVD only.
 * Public input calls are deterministic; these are not physical Gboard/keyboard acceptance. */
@OptIn(ExperimentalComposeUiApi::class)
class NativeChiefRichComposerRuntimeTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    @get:Rule val testName = org.junit.rules.TestName()
    private lateinit var rich: NativeChiefRichEditor
    @Volatile private var request: PlatformTextInputMethodRequest? = null
    private var ownerText by mutableStateOf("")
    private var ownerRuns by mutableStateOf(emptyList<NativeConversationTextRun>())
    private var ownerRevision by mutableLongStateOf(0)
    private var fontScale by mutableFloatStateOf(1f)
    private var direction by mutableStateOf(androidx.compose.ui.unit.LayoutDirection.Ltr)
    private val sent = mutableListOf<Pair<String, List<NativeConversationTextRun>>>()

    @Before fun mount() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        compose.runOnUiThread { rich = NativeChiefRichEditor("") }
        compose.setContent {
            val capture = remember { PlatformTextInputInterceptor { value, next ->
                request = value
                // Deterministic public-API edit cases keep Gboard from independently
                // committing their marked text. Palette cases use the actual system IME.
                if (testName.methodName.contains("palette", ignoreCase = true)) next.startInputMethod(value)
                else kotlinx.coroutines.awaitCancellation()
            } }
            CmuxTheme {
                val inherited = androidx.compose.ui.platform.LocalDensity.current
                CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(inherited.density, fontScale),
                    androidx.compose.ui.platform.LocalLayoutDirection provides direction) {
                Surface(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
                    InterceptPlatformTextInput(capture) {
                        Column {
                            Spacer(Modifier.weight(1f))
                            NativeChiefComposer(ownerText, ownerRevision, false, { publish(it, emptyList()) }, { _, _ -> false },
                                richEditorState = rich, draftRuns = ownerRuns, onRichDraft = ::publish,
                                onRichSend = { text, _, runs -> sent += text to runs; publish("", emptyList()); true })
                        }
                    }
                }
                }
            }
        }
        compose.onNodeWithTag("ChiefComposer").performClick()
        compose.waitUntil(10_000) { request != null }
    }
    private fun publish(text: String, runs: List<NativeConversationTextRun>): Long {
        if (text != ownerText || runs != ownerRuns) { ownerText = text; ownerRuns = runs; ownerRevision++ }
        return ownerRevision
    }
    private fun connection(): InputConnection {
        lateinit var result: InputConnection
        compose.runOnUiThread { result = checkNotNull(request).createInputConnection(EditorInfo()) }
        return result
    }
    private fun edit(action: (InputConnection) -> Unit) {
        val input = connection(); compose.runOnUiThread { action(input) }; compose.waitForIdle()
        compose.runOnUiThread { rich.synchronizeSelectionAndComposition(); assertTrue("Exact field/model agreement", rich.coherent) }
    }
    private fun openPalette() {
        val actions = compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config[SemanticsActions.CustomActions]
        compose.runOnUiThread { assertTrue(actions.single { it.label == compose.activity.getString(R.string.chief_rich_title) }.action()) }
        var stableSince = android.os.SystemClock.uptimeMillis()
        compose.waitUntil(10_000) {
            val visible = compose.onAllNodesWithTag("ChiefTextEffectsPalette").fetchSemanticsNodes().isNotEmpty()
            var keyboard = true
            compose.runOnUiThread { keyboard = compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            if (!visible || keyboard) stableSince = android.os.SystemClock.uptimeMillis()
            visible && !keyboard && android.os.SystemClock.uptimeMillis() - stableSince >= 250
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle(); android.os.SystemClock.sleep(150)
        val bitmap = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot()
        val directory = java.io.File(compose.activity.getExternalFilesDir(null), "rich-composer").apply { mkdirs() }
        java.io.File(directory, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val diagnostic = compose.onNodeWithTag("ChiefComposerBar").printToString() + "\nRich: " + rich.snapshot + "\nOwner: " + ownerRuns
        java.io.File(directory, "$name.txt").writeText(diagnostic)
    }

    @Test fun batchingKeepsFirstChunkStyleAndEqualTextReplacementKeepsExactRange() {
        compose.runOnUiThread { rich.toggle(NativeConversationTextStyle.BOLD) }
        edit { input -> input.beginBatchEdit(); input.commitText("a", 1); input.commitText("aaa", 1); input.endBatchEdit() }
        compose.runOnUiThread {
            assertEquals("aaaa", rich.snapshot.text)
            assertEquals(listOf(NativeConversationTextRun(0, 1, 1)), rich.snapshot.runs)
        }
        edit { it.setSelection(0, 2) }
        compose.runOnUiThread { rich.toggle(NativeConversationTextStyle.BOLD) }
        edit { it.setSelection(1, 3); it.commitText("aa", 1) }
        compose.runOnUiThread {
            assertEquals("aaaa", rich.snapshot.text)
            assertEquals(listOf(NativeConversationTextRun(0, 1, 1)), rich.snapshot.runs)
            assertEquals(rich.snapshot.runs, ownerRuns)
        }
    }

    @Test fun markedTextAndModernInputCallsRetainMetadataUntilUnmark() {
        compose.runOnUiThread { rich.toggle(NativeConversationTextStyle.ITALIC) }
        edit { it.beginBatchEdit(); it.setComposingText("ka", 1, null); it.setComposingText("かな", 1, null); it.endBatchEdit() }
        compose.runOnUiThread {
            assertEquals(TextRange(0, 2), rich.field.composition)
            assertEquals(listOf(NativeConversationTextRun(0, 2, 2)), rich.snapshot.runs)
            assertEquals(2L, rich.snapshot.typingStyle)
        }
        edit { it.finishComposingText(); it.commitText("!", 1, null) }
        compose.runOnUiThread {
            assertNull(rich.field.composition); assertEquals(0L, rich.snapshot.typingStyle)
            assertEquals("かな!", rich.snapshot.text)
            assertEquals(listOf(NativeConversationTextRun(0, 2, 2)), rich.snapshot.runs)
        }
        if (android.os.Build.VERSION.SDK_INT >= 34) edit { it.replaceText(1, 3, "X", 1, null) }
        compose.runOnUiThread { if (android.os.Build.VERSION.SDK_INT >= 34) assertEquals("かX", rich.snapshot.text) }
    }

    @Test fun richUndoRedoAndOldBatchCannotRestoreAnOwnerReset() {
        edit { it.commitText("base", 1) }
        edit { it.setSelection(0, 4) }
        compose.runOnUiThread { rich.toggle(NativeConversationTextStyle.BOLD) }
        edit { it.setSelection(4, 4); it.commitText("X", 1) }
        edit { it.setSelection(4, 5) }
        compose.runOnUiThread { rich.toggle(NativeConversationTextStyle.ITALIC); assertTrue(rich.history(false)) }
        compose.waitForIdle()
        compose.runOnUiThread { assertEquals("base", rich.snapshot.text); assertEquals(listOf(NativeConversationTextRun(0, 4, 1)), rich.snapshot.runs) }
        compose.runOnUiThread { assertTrue(rich.history(true)) }
        compose.waitForIdle()
        compose.runOnUiThread { assertEquals(listOf(NativeConversationTextRun(0, 4, 1), NativeConversationTextRun(4, 1, 2)), rich.snapshot.runs) }
        val obsolete = connection()
        compose.runOnUiThread { obsolete.beginBatchEdit(); obsolete.commitText("late", 1); rich.reset(""); publish("", emptyList()) }
        compose.runOnUiThread { assertFalse(obsolete.endBatchEdit()); assertFalse(obsolete.commitText("late", 1)) }
        compose.waitForIdle()
        compose.runOnUiThread { assertEquals("", rich.field.text.toString()); assertEquals("", rich.snapshot.text); assertTrue(ownerRuns.isEmpty()) }
    }

    @Test fun paletteChangesTheExistingFieldPublishesFormattingAndSendCapturesItOnce() {
        edit { it.commitText("Hello", 1); it.setSelection(0, 5) }
        val retainedField = rich.field
        openPalette()
        capture("palette-before")
        compose.onNodeWithTag("ChiefTextStyle:bold").performClick()
        capture("palette-after-bold")
        compose.onNodeWithTag("ChiefTextStyle:bold").assertIsSelected()
        compose.onNodeWithTag("ChiefTextEffect:big").performClick().assertIsSelected()
        capture("palette-big-bold")
        compose.runOnUiThread {
            assertSame(retainedField, rich.field); assertEquals(TextRange(0, 5), rich.field.selection)
            assertEquals(listOf(NativeConversationTextRun(0, 5, 1, NativeConversationTextEffect.BIG)), ownerRuns)
        }
        compose.onNodeWithTag("ChiefSend").performClick()
        compose.onNodeWithTag("ChiefTextEffectsPalette").assertDoesNotExist()
        compose.runOnUiThread {
            assertEquals(listOf("Hello" to listOf(NativeConversationTextRun(0, 5, 1, NativeConversationTextEffect.BIG))), sent)
            assertEquals("", rich.field.text.toString()); assertTrue(rich.snapshot.runs.isEmpty())
        }
        compose.onNodeWithTag("ChiefSend").assertDoesNotExist()
    }

    @Test fun codePointDeletionKeepsSurrogateAndFollowingFormattingOffsets() {
        edit { it.commitText("a😀b", 1); it.setSelection(3, 4) }
        compose.runOnUiThread { rich.toggle(NativeConversationTextStyle.UNDERLINE) }
        edit { it.setSelection(3, 3); it.deleteSurroundingTextInCodePoints(1, 0) }
        compose.runOnUiThread {
            assertEquals("ab", rich.snapshot.text); assertEquals(TextRange(1), rich.field.selection)
            assertEquals(listOf(NativeConversationTextRun(1, 1, 4)), rich.snapshot.runs)
        }
    }

    @Test fun aRejectedSameValueComposingCommandCannotApplySpeculativeStyles() {
        edit { it.setComposingText("aa", 1) }
        compose.runOnUiThread {
            rich.toggle(NativeConversationTextStyle.ITALIC)
            val before = rich.snapshot
            val rejecting = rich.connection(object : android.view.inputmethod.BaseInputConnection(compose.activity.window.decorView, false) {
                override fun setComposingText(text: CharSequence?, newCursorPosition: Int) = false
            }) { true }
            assertFalse(rejecting.setComposingText("aa", 1))
            assertEquals(before, rich.snapshot)
        }
    }

    @Test fun aStyleChangeDuringAnOpenImeBatchIsNotOverwrittenByItsOlderSnapshot() {
        edit { it.commitText("aa", 1) }
        val input = connection()
        val mutable = android.text.SpannableStringBuilder("X")
        compose.runOnUiThread { input.beginBatchEdit(); input.commitText(mutable, 1); mutable.replace(0, 1, "Y") }
        compose.runOnUiThread {
            assertEquals("aa", rich.field.text.toString())
            rich.toggle(NativeConversationTextStyle.BOLD)
        }
        compose.runOnUiThread { input.endBatchEdit() }
        compose.waitForIdle()
        compose.runOnUiThread {
            assertEquals("aaX", rich.field.text.toString())
            assertEquals(listOf(NativeConversationTextRun(2, 1, 1)), rich.snapshot.runs)
            assertTrue(rich.coherent)
        }
    }

    @Test fun accessibleRtlPaletteKeepsPhysicalOrderAndCloseRestoresTheKeyboardAndSelection() {
        edit { it.commitText("مرحبا", 1); it.setSelection(0, 5) }
        compose.runOnUiThread { fontScale = 2f; direction = androidx.compose.ui.unit.LayoutDirection.Rtl }
        compose.waitForIdle(); openPalette()
        NativeConversationTextStyle.entries.forEach { compose.onNodeWithTag("ChiefTextStyle:${it.wireName}").assertIsDisplayed() }
        NativeConversationTextEffect.entries.forEach { compose.onNodeWithTag("ChiefTextEffect:${it.wireName}").assertIsDisplayed() }
        assertTrue(compose.onNodeWithTag("ChiefTextEffect:big").fetchSemanticsNode().boundsInRoot.left <
            compose.onNodeWithTag("ChiefTextEffect:small").fetchSemanticsNode().boundsInRoot.left)
        capture("palette-large-rtl")
        compose.onNodeWithTag("ChiefTextEffectsClose").performClick()
        compose.onNodeWithTag("ChiefTextEffectsPalette").assertDoesNotExist()
        compose.waitUntil(10_000) {
            var visible = false
            compose.runOnUiThread { visible = compose.activity.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            visible
        }
        compose.runOnUiThread { assertEquals(TextRange(0, 5), rich.field.selection); assertEquals("مرحبا", rich.field.text.toString()) }
    }
}
