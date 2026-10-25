package io.github.docmorphic.cmuxapp

import androidx.compose.ui.text.TextRange
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Native UIKit outputs are the oracle; the adapter only supplies public operation intent. */
class NativeChiefRichEditingTest {
    private fun reference(): JSONObject {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/NativeObservations.json")).use { it.readBytes() }
        assertEquals("Actual native trace identity", "a1120ee89edc40e661efa0086f398516545c47321cc91dd15b3827a2c7a19b9d",
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        return JSONObject(bytes.toString(Charsets.UTF_8))
    }
    private fun range(value: JSONObject) = TextRange(value.getInt("location"), value.getInt("location") + value.getInt("length"))
    @Test fun sourceCaretSelectionCompositionEqualTextAndRichUndoTracesMatchNativeObservations() {
        val cases = reference().getJSONArray("cases")
        repeat(cases.length()) { c ->
            val case = cases.getJSONObject(c); val records = case.getJSONArray("records")
            val editor = NativeChiefRichEditing()
            repeat(records.length()) { index ->
                val row = records.getJSONObject(index); val action = row.getJSONObject("action")
                when (action.getString("kind")) {
                    "initial", "focus", "openEffects", "closeEffects" -> Unit
                    "resign" -> editor.breakUndoGroup()
                    "insert" -> editor.commitText(action.getString("text"))
                    "select" -> editor.select(when (action.optString("selection")) {
                        "all" -> TextRange(0, editor.draft.text.length)
                        "end" -> TextRange(editor.draft.text.length)
                        "start" -> TextRange.Zero
                        else -> TextRange(action.getInt("start"), action.getInt("start") + action.getInt("length"))
                    })
                    "toggle" -> {
                        val name = action.getString("target")
                        val style = NativeConversationTextStyle.entries.firstOrNull { it.wireName == name }
                        if (style != null) editor.toggle(style) else editor.toggle(NativeConversationTextEffect.entries.single { it.wireName == name })
                    }
                    "deleteBackward" -> if (editor.draft.selection.collapsed) editor.deleteSurrounding(1, 0) else editor.replaceSelection("")
                    "replace" -> editor.replaceSelection(action.getString("text"))
                    "paste" -> editor.replaceSelection(action.getString("text"), boundary = true)
                    "marked" -> {
                        val start = (editor.draft.composition ?: editor.draft.selection).min
                        editor.commitText(action.getString("text"), composing = true)
                        editor.select(TextRange(start + action.getInt("start"), start + action.getInt("start") + action.getInt("length")), manual = false)
                    }
                    "unmark" -> editor.composition(null)
                    "undo" -> assertEquals(row.getBoolean("undoOrRedoPerformed"), editor.undo())
                    "redo" -> assertEquals(row.getBoolean("undoOrRedoPerformed"), editor.redo())
                    "send" -> editor.reset("")
                    else -> error("Unmapped native operation")
                }
                val expected = row.getJSONObject("state"); val actual = editor.draft
                val label = case.getString("id") + " step " + row.getInt("step")
                assertEquals("$label text", expected.getString("text"), actual.text)
                assertEquals("$label selection", range(expected.getJSONObject("selection")), actual.selection)
                assertEquals("$label composition", if (expected.isNull("marked")) null else range(expected.getJSONObject("marked")), actual.composition)
                val runs = expected.getJSONArray("runs")
                val expectedRuns = (0 until runs.length()).map { i -> runs.getJSONObject(i).let {
                    NativeConversationTextRun(it.getInt("location"), it.getInt("length"), it.getLong("style"),
                        if (it.isNull("effect")) null else NativeConversationTextEffect.entries.single { e -> e.wireName == it.getString("effect") })
                } }
                assertEquals("$label runs", expectedRuns, actual.runs)
                val typing = expected.getJSONObject("typing")
                assertEquals("$label typing style", typing.getLong("style"), actual.typingStyle)
                assertEquals("$label typing effect", if (typing.isNull("effect")) null else typing.getString("effect"), actual.typingEffect?.wireName)
                assertEquals("$label canUndo", expected.getBoolean("canUndo"), editor.canUndo)
                assertEquals("$label canRedo", expected.getBoolean("canRedo"), editor.canRedo)
                if (!expected.isNull("inputViewIdentifier")) {
                    val selected = buildList {
                        NativeConversationTextStyle.entries.filter { editor.activeStyle and it.mask != 0L }.forEach { add("conversation.textEffects." + it.wireName) }
                        editor.activeEffect?.let { add("conversation.textEffects." + it.wireName) }
                    }
                    val want = expected.getJSONArray("selectedControls").let { a -> (0 until a.length()).map(a::getString) }
                    assertEquals("$label selected controls", want, selected)
                }
            }
        }
    }

    @Test fun undoKeepsFormattingOutsideAnEditAndRedoCapturesFormattingChangedAfterTyping() {
        val model = NativeChiefRichEditing(NativeChiefRichDraft("base", selection = TextRange(4)))
        model.commitText("X")
        model.select(TextRange(0, 4)); model.toggle(NativeConversationTextStyle.BOLD)
        model.select(TextRange(4, 5)); model.toggle(NativeConversationTextStyle.ITALIC)
        assertTrue(model.undo())
        assertEquals("base", model.draft.text)
        assertEquals(listOf(NativeConversationTextRun(0, 4, 1)), model.draft.runs)
        assertTrue(model.redo())
        assertEquals("baseX", model.draft.text)
        assertEquals(listOf(NativeConversationTextRun(0, 4, 1), NativeConversationTextRun(4, 1, 2)), model.draft.runs)
    }

    @Test fun speculativeBatchDoesNotMutateAcceptedDocumentBeforeAdmission() {
        val model = NativeChiefRichEditing(NativeChiefRichDraft("aa", selection = TextRange(1)))
        model.toggle(NativeConversationTextStyle.BOLD)
        val batch = model.fork()
        batch.commitText("X"); batch.commitText("X")
        assertEquals("aa", model.draft.text)
        assertTrue(model.draft.runs.isEmpty())
        model.adopt(batch)
        assertEquals("aXXa", model.draft.text)
        assertEquals(listOf(NativeConversationTextRun(1, 1, 1)), model.draft.runs)
    }
}
