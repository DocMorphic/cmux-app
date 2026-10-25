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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.toPixelMap
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Before
import org.junit.After
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
    private var limitHeight by mutableStateOf(false)
    private val sent = mutableListOf<Pair<String, List<NativeConversationTextRun>>>()
    @After fun clearFixtureClipboard() {
        compose.runOnUiThread { compose.activity.getSystemService(android.content.ClipboardManager::class.java).clearPrimaryClip() }
    }

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
                            Box(if (limitHeight) Modifier.height(260.dp) else Modifier) {
                            NativeChiefComposer(ownerText, ownerRevision, false, { publish(it, emptyList()) }, { _, _ -> false },
                                richEditorState = rich, draftRuns = ownerRuns, onRichDraft = ::publish,
                                onRichSend = { text, _, runs -> sent += text to runs; publish("", emptyList()); true })
                            }
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

    private fun dismissClipboardPreview() {
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val preview = androidx.test.uiautomator.By.res("com.android.systemui", "clipboard_ui")
        if (device.wait(androidx.test.uiautomator.Until.findObject(preview), 5000) != null) {
            // The dismiss button is absent on some API36 preview layouts. Touch the
            // fixture's empty upper area, outside the OS preview and the composer.
            device.waitForIdle(); device.click(device.displayWidth / 2, device.displayHeight / 3)
            assertTrue("The clipboard preview window must leave the touch region",
                device.wait(androidx.test.uiautomator.Until.gone(preview), 5000))
            device.waitForIdle()
        }
        compose.waitForIdle()
    }
    private fun putClipboard(text: String) {
        compose.runOnUiThread {
            compose.activity.getSystemService(android.content.ClipboardManager::class.java)
                .setPrimaryClip(android.content.ClipData.newPlainText("Chief test", text))
        }
        // Android's clipboard preview can physically cover the floating Paste item.
        dismissClipboardPreview()
    }
    private fun paste() {
        compose.onNodeWithTag("ChiefComposer").performSemanticsAction(SemanticsActions.PasteText) { assertTrue(it()) }
        compose.waitForIdle()
    }

    @Test fun stockClipboardPasteAndCutKeepSeparateRichUndoGroupsAndExactEqualTextRanges() {
        edit { it.commitText("a", 1) }; putClipboard("X"); paste(); edit { it.commitText("b", 1) }
        compose.runOnUiThread {
            assertEquals("aXb", rich.snapshot.text)
            assertTrue(rich.history(false)); assertEquals("aX", rich.snapshot.text)
            assertTrue(rich.history(false)); assertEquals("a", rich.snapshot.text)
            assertTrue(rich.history(false)); assertEquals("", rich.snapshot.text)
            rich.reset("aaaa", listOf(NativeConversationTextRun(0, 2, 1)))
        }
        compose.waitForIdle(); edit { it.setSelection(1, 3) }; putClipboard("aa"); paste()
        compose.runOnUiThread {
            assertEquals("aaaa", rich.snapshot.text)
            assertEquals(listOf(NativeConversationTextRun(0, 1, 1)), rich.snapshot.runs)
            assertTrue(rich.history(false)); assertEquals(listOf(NativeConversationTextRun(0, 2, 1)), rich.snapshot.runs)
            rich.reset("baseX", listOf(NativeConversationTextRun(0, 4, 1), NativeConversationTextRun(4, 1, 2)))
        }
        compose.waitForIdle(); edit { it.setSelection(4, 5) }
        compose.onNodeWithTag("ChiefComposer").performSemanticsAction(SemanticsActions.CutText) { assertTrue(it()) }
        compose.waitForIdle(); dismissClipboardPreview(); edit { it.commitText("Y", 1) }
        compose.runOnUiThread {
            assertEquals("baseY", rich.snapshot.text)
            assertTrue(rich.history(false)); assertEquals("base", rich.snapshot.text)
            assertTrue(rich.history(false)); assertEquals("baseX", rich.snapshot.text)
            assertEquals(listOf(NativeConversationTextRun(0, 4, 1), NativeConversationTextRun(4, 1, 2)), rich.snapshot.runs)
            assertTrue(rich.coherent)
        }
    }

    @Test fun stockToolbarPasteSeparatesTypingBeforeAndAfterItsActualMenuTap() {
        edit { it.commitText("aa", 1) }; putClipboard("X")
        compose.onNodeWithTag("ChiefComposer").performSemanticsAction(SemanticsActions.SetSelection) { assertTrue(it(1, 2, false)) }
        compose.waitForIdle(); capture("stock-toolbar-before-tap")
        val device = androidx.test.uiautomator.UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val paste = device.wait(androidx.test.uiautomator.Until.findObject(androidx.test.uiautomator.By.text(compose.activity.getString(android.R.string.paste))), 5000)
        assertNotNull("Actual stock toolbar Paste", paste); paste.click()
        compose.waitForIdle(); capture("stock-toolbar-after-tap")
        compose.waitUntil(5000) { rich.field.text.toString() == "aX" }
        edit { it.commitText("b", 1) }
        compose.runOnUiThread {
            assertTrue(rich.history(false)); assertEquals("aX", rich.snapshot.text)
            assertTrue(rich.history(false)); assertEquals("aa", rich.snapshot.text)
        }
        capture("stock-toolbar-paste-undo")
    }

    @Test fun internallyScrollingAnEffectDraftKeepsActualInkAtBothEndsAndItsSemanticRuns() {
        val text = "one two three four five\n".repeat(30)
        val runs = listOf(NativeConversationTextRun(0, text.length, effect = NativeConversationTextEffect.RIPPLE))
        compose.runOnUiThread { limitHeight = true; rich.reset(text, runs) }
        compose.waitForIdle()
        val node = compose.onNodeWithTag("ChiefComposer")
        val axis = node.fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.VerticalScrollAxisRange]
        assertTrue(axis.maxValue() > 0)
        fun ink(): Int {
            val bitmap = compose.onNodeWithTag("ChiefComposerTextViewport", useUnmergedTree = true).captureToImage()
            val pixels = bitmap.toPixelMap(); var count = 0
            for (y in 0 until pixels.height) for (x in 0 until pixels.width) if (pixels[x, y].red > .5f) count++
            return count
        }
        node.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, -1_000_000f)) }
        compose.waitForIdle(); assertEquals(0f, axis.value(), 1f); assertTrue("Visible effect ink at head", ink() > 500)
        capture("rich-scroll-head")
        node.performSemanticsAction(SemanticsActions.ScrollBy) { assertTrue(it(0f, 1_000_000f)) }
        compose.waitForIdle(); assertEquals(axis.maxValue(), axis.value(), 1f); assertTrue("Visible effect ink at tail", ink() > 500)
        capture("rich-scroll-tail")
        compose.runOnUiThread { assertEquals(text, rich.snapshot.text); assertEquals(runs, rich.snapshot.runs); assertEquals(runs, ownerRuns) }
    }

    @Test fun endingTheVisitRejectsItsOldInputConnectionWithoutClearingTheRichDraft() {
        edit { it.commitText("base", 1); it.setSelection(0, 4) }
        compose.runOnUiThread { rich.toggle(NativeConversationTextStyle.BOLD) }
        val old = connection()
        compose.runOnUiThread {
            val draft = rich.snapshot
            rich.endVisit()
            assertFalse(old.commitText("late", 1))
            assertEquals(draft, rich.snapshot); assertTrue(rich.acceptsInput)
        }
    }

    @Test fun aClipboardActionCapturedBeforeLeavingCannotPasteIntoTheRetainedDraft() {
        edit { it.commitText("base", 1) }; putClipboard("late")
        val old = compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config[SemanticsActions.PasteText].action!!
        compose.runOnUiThread { rich.endVisit(); old() }
        compose.waitForIdle()
        compose.runOnUiThread { assertEquals("base", rich.field.text.toString()); assertEquals("base", ownerText) }
    }

    @Test fun anEditActionFromTheOldMountedFieldCannotMutateTheRetainedDocument() {
        edit { it.commitText("base", 1) }
        val old = compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config[SemanticsActions.SetText].action!!
        compose.runOnUiThread { rich.endVisit(); old(androidx.compose.ui.text.AnnotatedString("late")) }
        compose.waitForIdle()
        compose.runOnUiThread { assertEquals("base", rich.field.text.toString()); assertEquals("base", ownerText) }
    }
}
