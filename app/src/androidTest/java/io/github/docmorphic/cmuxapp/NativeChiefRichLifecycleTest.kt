package io.github.docmorphic.cmuxapp

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.input.key.Key
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

/** Real Android Activity/configuration lifecycle with the production retained computer.
 * The isolated read API supplies no account, host or persistent-draft acceptance. */
@OptIn(ExperimentalTestApi::class)
class NativeChiefRichLifecycleTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrument = InstrumentationRegistry.getInstrumentation()
    private val device = UiDevice.getInstance(instrument)
    private var scenario: ActivityScenario<NativeChiefLifecycleTestActivity>? = null
    private lateinit var scope: CoroutineScope
    private lateinit var computer: NativeChiefComputerSession
    private var current = true
    private val api = Api()
    @Before fun launch() {
        check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk"))
        instrument.runOnMainSync {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            computer = NativeChiefComputerSession(scope, api) { current }
            NativeChiefLifecycleTestActivity.computer = computer

        }
        scenario = ActivityScenario.launch(NativeChiefLifecycleTestActivity::class.java)
        compose.onNodeWithTag("ChiefPinnedConversation").performClick()
        compose.waitUntil(10_000) { computer.transcript()?.state?.value?.hasLoadedInfo == true }
    }
    @After fun close() {
        scenario?.close()
        if (::scope.isInitialized) instrument.runOnMainSync { computer.close(); scope.cancel() }
        NativeChiefLifecycleTestActivity.computer = null
        device.unfreezeRotation()
    }
    private fun styleAndOpenPalette(): NativeChiefRichEditor {
        val editor = computer.transcript()!!.composerEditor
        compose.onNodeWithTag("ChiefComposer").performTextInput("base")
        compose.onNodeWithTag("ChiefComposer").performTextInputSelection(TextRange(0, 4))
        val action = compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config[SemanticsActions.CustomActions]
            .single { it.label == instrument.targetContext.getString(R.string.chief_rich_title) }
        compose.runOnUiThread { assertTrue(action.action()) }
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("ChiefTextEffectsPalette").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("ChiefTextStyle:bold").performClick()
        compose.onNodeWithTag("ChiefTextEffect:big").performClick()
        compose.waitForIdle()
        assertEquals(listOf(NativeConversationTextRun(0, 4, 1, NativeConversationTextEffect.BIG)), editor.snapshot.runs)
        return editor
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        val dir = java.io.File(instrument.targetContext.getExternalFilesDir(null), "rich-composer").apply { mkdirs() }
        val bitmap = instrument.uiAutomation.takeScreenshot()
        java.io.File(dir, "$name.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }; bitmap.recycle()
        java.io.File(dir, "$name.txt").writeText(compose.onNodeWithTag("ChiefComposerBar").printToString())
    }

    @Test fun keyboardFormattingIsRejectedWhilePausedOrOwnerObsoleteAndWorksAfterResume() {
        val editor = styleAndOpenPalette(); val session = computer.transcript()!!
        val before = editor.snapshot
        fun rawItalic() {
            scenario!!.onActivity { activity ->
                val time = android.os.SystemClock.uptimeMillis()
                for (action in listOf(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.ACTION_UP)) {
                    activity.dispatchKeyEvent(android.view.KeyEvent(time, time, action, android.view.KeyEvent.KEYCODE_I, 0, android.view.KeyEvent.META_CTRL_ON))
                }
            }
            compose.waitForIdle()
        }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.STARTED)
        rawItalic()
        compose.runOnUiThread { assertEquals(before, editor.snapshot); assertEquals(before.runs, session.state.value.draftRuns) }
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("ChiefComposer").assertIsFocused()
        compose.onNodeWithTag("ChiefComposer").performKeyInput { keyDown(Key.CtrlLeft); pressKey(Key.I); keyUp(Key.CtrlLeft) }
        compose.onNodeWithTag("ChiefTextStyle:italic").assertIsSelected()
        compose.runOnUiThread {
            assertEquals("base", editor.snapshot.text); assertEquals(TextRange(0, 4), editor.field.selection)
            assertEquals(listOf(NativeConversationTextRun(0, 4, 3, NativeConversationTextEffect.BIG)), session.state.value.draftRuns)
            current = false
        }
        val obsolete = editor.snapshot
        rawItalic()
        compose.runOnUiThread { assertEquals(obsolete, editor.snapshot); assertEquals(obsolete.runs, session.state.value.draftRuns) }
        assertEquals(0, api.writes)
    }

    @Test fun activityRecreationRetainsRichSelectionHistoryPaletteAndFocus() {
        val editor = styleAndOpenPalette(); val session = computer.transcript()!!; val draft = editor.snapshot
        var old: NativeChiefLifecycleTestActivity? = null
        scenario!!.onActivity { old = it }; scenario!!.recreate()
        scenario!!.onActivity { assertNotSame(old, it) }
        compose.onNodeWithTag("ChiefComposer").assertTextContains("base")
        capture("rich-activity-recreated")
        compose.onNodeWithTag("ChiefTextEffectsPalette").assertIsDisplayed()
        compose.onNodeWithTag("ChiefTextStyle:bold").assertIsSelected()
        compose.onNodeWithTag("ChiefTextEffect:big").assertIsSelected()
        compose.onNodeWithTag("ChiefComposer").assertIsFocused()
        compose.runOnUiThread {
            assertSame(session, computer.transcript()); assertSame(editor, session.composerEditor)
            assertEquals(draft, editor.snapshot); assertEquals(draft.runs, session.state.value.draftRuns)
            assertTrue(editor.history(false)); assertEquals("", editor.snapshot.text)
            assertTrue(editor.history(true)); assertEquals(draft.text, editor.snapshot.text); assertEquals(draft.runs, editor.snapshot.runs)
        }
        assertEquals(0, api.writes)
    }

    @Test fun actualRotationAndBackgroundRetainTheDraftAndRevocationClearsTheOldEditor() {
        val editor = styleAndOpenPalette(); val draft = editor.snapshot
        device.setOrientationLeft()
        compose.waitUntil(15_000) { device.displayWidth > device.displayHeight }
        compose.onNodeWithTag("ChiefComposer").assertTextContains("base")
        compose.onNodeWithTag("ChiefTextEffectsPalette").assertExists()
        capture("rich-live-rotation")
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.onNodeWithTag("ChiefComposer").assertTextContains("base")
        compose.runOnUiThread { assertEquals(draft, editor.snapshot); current = false }
        scenario!!.recreate()
        compose.runOnUiThread { assertFalse(editor.acceptsInput); assertEquals("", editor.field.text.toString()); assertTrue(editor.snapshot.runs.isEmpty()) }
        assertEquals(0, api.writes)
    }

    @Test fun normalKeyboardRecreationAndLaterResumeKeepTheLatestRichSelection() {
        fun waitForKeyboard() = compose.waitUntil(10_000) {
            var visible = false
            scenario!!.onActivity { visible = it.window.decorView.rootWindowInsets.isVisible(android.view.WindowInsets.Type.ime()) }
            visible
        }
        val editor = computer.transcript()!!.composerEditor
        compose.onNodeWithTag("ChiefComposer").performTextInput("alpha")
        compose.onNodeWithTag("ChiefComposer").performTextInputSelection(TextRange(0, 5))
        compose.runOnUiThread { editor.toggle(NativeConversationTextStyle.BOLD) }
        compose.onNodeWithTag("ChiefComposer").performTextInputSelection(TextRange(1, 3))
        compose.waitForIdle(); val runs = editor.snapshot.runs
        scenario!!.recreate()
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused]
        }
        compose.runOnUiThread { assertEquals(TextRange(1, 3), editor.field.selection); assertEquals(runs, editor.snapshot.runs) }
        waitForKeyboard()
        compose.onNodeWithTag("ChiefTextEffectsPalette").assertDoesNotExist()
        compose.onNodeWithTag("ChiefComposer").performTextInputSelection(TextRange(2, 4))
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        scenario!!.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.waitUntil(10_000) {
            compose.onNodeWithTag("ChiefComposer").fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsProperties.Focused]
        }
        compose.runOnUiThread { assertEquals(TextRange(2, 4), editor.field.selection); assertEquals(runs, editor.snapshot.runs) }
        waitForKeyboard()
        compose.onNodeWithTag("ChiefTextEffectsPalette").assertDoesNotExist()
        capture("rich-normal-keyboard-resumed")
        assertEquals(0, api.writes)
    }

    @Test fun actualSystemFontChangesClearRichHistoryAndFormattingButKeepOwnerTextAndSelection() {
        val original = device.executeShellCommand("settings get system font_scale").trim()
        val editor = styleAndOpenPalette(); val session = computer.transcript()!!
        val records = org.json.JSONArray()
        fun record(name: String, expectedText: String = "base", expectedSelection: TextRange = TextRange(0, 4)): Float {
            compose.waitForIdle()
            val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
            compose.onNodeWithTag("ChiefComposer").performSemanticsAction(SemanticsActions.GetTextLayoutResult) { assertTrue(it(layouts)) }
            val layout = layouts.single(); val height = layout.getLineBottom(0) - layout.getLineTop(0)
            val fontPoints = with(layout.layoutInput.density) { layout.layoutInput.style.fontSize.toPx() / density }
            if (name == "enlarged-typed") assertEquals("Retained original base after real system change", 17f, fontPoints, .01f)
            var scale = 0f; scenario!!.onActivity { scale = it.resources.configuration.fontScale }
            compose.runOnUiThread {
                assertSame(session, computer.transcript()); assertSame(editor, session.composerEditor)
                assertEquals(expectedText, editor.field.text.toString()); assertEquals(expectedSelection, editor.field.selection)
                val draft = editor.snapshot
                assertEquals("Published owner formatting", draft.runs, session.state.value.draftRuns)
                if (name == "enlarged" || name == "returned") {
                    assertTrue(draft.runs.isEmpty()); assertNull(draft.composition)
                    assertEquals(0L, draft.typingStyle); assertNull(draft.typingEffect)
                    assertFalse(editor.canUndo); assertFalse(editor.canRedo)
                }
                records.put(org.json.JSONObject().put("phase", name).put("fontScale", scale).put("lineHeightPx", height).put("baseFontPoints", fontPoints)
                    .put("text", draft.text).put("selectionStart", draft.selection.start).put("selectionEnd", draft.selection.end)
                    .put("typingStyle", draft.typingStyle).put("typingEffect", draft.typingEffect?.wireName ?: org.json.JSONObject.NULL)
                    .put("canUndo", editor.canUndo).put("canRedo", editor.canRedo).put("runs", org.json.JSONArray(draft.runs.map { run ->
                        org.json.JSONObject().put("location", run.location).put("length", run.length).put("style", run.style)
                            .put("effect", run.effect?.wireName ?: org.json.JSONObject.NULL)
                    })))
            }
            if (name != "initial") {
                // The configuration can arrive before the new window gains focus;
                // production restores the retained range after that window event.
                compose.waitUntil(10_000) {
                    compose.onAllNodes(hasTestTag("ChiefComposer") and isFocused()).fetchSemanticsNodes().size == 1
                }
                compose.onNodeWithTag("ChiefTextStyle:bold").assertIsNotSelected()
                compose.onNodeWithTag("ChiefTextEffect:big").assertIsNotSelected()
                compose.onNodeWithTag("ChiefComposer").assertIsFocused()
            }
            capture("rich-system-font-$name")
            return height
        }
        fun waitForScale(scale: Float) = compose.waitUntil(15_000) {
            var actual = 0f; scenario!!.onActivity { actual = it.resources.configuration.fontScale }
            kotlin.math.abs(actual - scale) < .01f
        }
        try {
            val initial = record("initial")
            device.executeShellCommand("settings put system font_scale 2.0"); waitForScale(2f)
            val enlarged = record("enlarged")
            assertTrue("Actual system text metrics change: $initial -> $enlarged", enlarged > initial + 1f)
            compose.onNodeWithTag("ChiefComposer").performTextInputSelection(TextRange(4))
            compose.onNodeWithTag("ChiefComposer").performTextInput("X")
            record("enlarged-typed", "baseX", TextRange(5))
            compose.runOnUiThread { assertTrue(editor.canUndo); assertTrue(editor.snapshot.runs.isEmpty()) }
            device.executeShellCommand(if (original == "null") "settings delete system font_scale" else "settings put system font_scale $original")
            waitForScale(original.toFloatOrNull() ?: 1f); record("returned", "baseX", TextRange(5))
            assertEquals(0, api.writes)
        } finally {
            device.executeShellCommand(if (original == "null") "settings delete system font_scale" else "settings put system font_scale $original")
            val directory = java.io.File(instrument.targetContext.getExternalFilesDir(null), "rich-composer").apply { mkdirs() }
            java.io.File(directory, "rich-system-font-records.json").writeText(records.toString(2) + "\n")
        }
    }

    private class Api : NativeChiefApi {
        var writes = 0
        private val now = Instant.parse("2026-10-25T00:00:00Z")
        override suspend fun status() = NativeChiefStatus(true, null, null, "chief", "Chief", "", "", 1, 1, emptyList(), "chief-main")
        override suspend fun conversations(afterCursor: String?, waitForChange: Boolean): NativeChiefSnapshot {
            if (waitForChange) awaitCancellation()
            return NativeChiefSnapshot(listOf(NativeChiefConversation("chief-main", "Chief", "Rich lifecycle", 0, true, false, now)), "home")
        }
        override suspend fun read(conversation: String?, beforeSeq: ULong?, limit: Int, afterSeq: ULong?, afterCursor: String?, waitForChange: Boolean): NativeChiefRead {
            if (waitForChange) awaitCancellation()
            return NativeChiefRead("chief-main", false, listOf(NativeChiefMessage("hello", 1uL, "chief", "Chief", "chief", "Lifecycle fixture", now, emptyList(), emptyList(), null, null)), false, 1uL, 0, 1uL, "read")
        }
        override suspend fun markRead(conversation: String?, upToSeq: ULong) = NativeChiefMarked("chief-main", upToSeq, true)
        private fun write(): Nothing { writes++; error("Lifecycle fixture attempted a write") }
        override suspend fun send(text: String, conversation: String?, from: String?, startTurn: Boolean, clientMessageId: String?): NativeChiefSent = write()
        override suspend fun stop(conversation: String?): NativeChiefStopped = write()
        override suspend fun approve(conversation: String?, request: String?, choice: String): NativeChiefApproved = write()
        override suspend fun answer(conversation: String?, message: String?, choices: List<String>, text: String?): NativeChiefAnswered = write()
        override suspend fun open(windowId: String?, workspaceId: String?): NativeChiefOpened = write()
    }
}
