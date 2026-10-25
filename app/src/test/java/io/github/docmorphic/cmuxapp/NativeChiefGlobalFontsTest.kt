package io.github.docmorphic.cmuxapp

import androidx.compose.ui.text.TextRange
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/** Actual global UIKit trace is the oracle; the same Unicode policy uses the checked JVM ICU adapter. */
class NativeChiefGlobalFontsTest {
    private fun range(value: JSONObject) = TextRange(value.getInt("location"), value.getInt("location") + value.getInt("length"))
    @Test fun allGlobalCategoryEditingFontAndHistoryStatesMatchTheOriginalEditor() {
        val bytes = checkNotNull(javaClass.getResourceAsStream("/GlobalFontObservations.json")).use { it.readBytes() }
        assertEquals("027c3a5ce706548777e490e0ebc9614715bb0efe7cc675a0d820884b0d9c9602",
            java.security.MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) })
        val cases = JSONObject(bytes.toString(Charsets.UTF_8)).getJSONArray("cases")
        var states = 0
        repeat(cases.length()) { c ->
            val case = cases.getJSONObject(c); val records = case.getJSONArray("records")
            val editor = NativeChiefRichEditing(emojiFont = { NativeChiefEmojiPolicy.composerFontSize(it, NativeChiefTestUnicode) })
            editor.initializeTypography(17.0)
            repeat(records.length()) { i ->
                val row = records.getJSONObject(i); val action = row.getJSONObject("action")
                val label = case.getString("id") + " step " + row.getInt("step")
                when (action.getString("kind")) {
                    "initial", "focus", "openEffects", "closeEffects" -> Unit
                    "insert" -> editor.commitText(action.getString("text"))
                    "select" -> editor.select(when (action.optString("selection")) {
                        "all" -> TextRange(0, editor.draft.text.length)
                        "end" -> TextRange(editor.draft.text.length)
                        else -> TextRange(action.getInt("start"), action.getInt("start") + action.getInt("length"))
                    })
                    "toggle" -> {
                        val target = action.getString("target")
                        val style = NativeConversationTextStyle.entries.firstOrNull { it.wireName == target }
                        if (style != null) editor.toggle(style) else editor.toggle(NativeConversationTextEffect.entries.single { it.wireName == target })
                    }
                    "marked" -> {
                        val start = (editor.draft.composition ?: editor.draft.selection).min
                        editor.commitText(action.getString("text"), composing = true)
                        editor.select(TextRange(start + action.getInt("start"), start + action.getInt("start") + action.getInt("length")), manual = false)
                    }
                    "unmark" -> editor.composition(null)
                    "systemMetricsCategory" -> editor.textMetricsChanged(if (action.getString("target") == "large") 17.0 else 28.0)
                    "deleteBackward" -> if (editor.draft.selection.collapsed) editor.deleteSurrounding(1, 0) else editor.replaceSelection("")
                    "undo" -> assertEquals(label, row.getBoolean("undoOrRedoPerformed"), editor.undo())
                    "redo" -> assertEquals(label, row.getBoolean("undoOrRedoPerformed"), editor.redo())
                    "send" -> {
                        val nativeDraft = row.getJSONObject("state").getJSONArray("backendDrafts").getJSONObject(0)
                        assertEquals(label, NativeChiefTextPolicy.trim(editor.draft.text), nativeDraft.getString("text"))
                        assertTrue("$label native fixture send is plain", editor.draft.runs.isEmpty() && nativeDraft.getJSONArray("runs").length() == 0)
                        editor.reset("")
                    }
                    else -> error("Unmapped global font operation")
                }
                val state = row.getJSONObject("state"); val actual = editor.draft
                assertEquals("$label text", state.getString("text"), actual.text)
                assertEquals("$label selection", range(state.getJSONObject("selection")), actual.selection)
                assertEquals("$label marked", if (state.isNull("marked")) null else range(state.getJSONObject("marked")), actual.composition)
                val runs = state.getJSONArray("runs")
                val expected = List(runs.length()) { n -> runs.getJSONObject(n).let {
                    NativeConversationTextRun(it.getInt("location"), it.getInt("length"), it.getLong("style"),
                        if (it.isNull("effect")) null else NativeConversationTextEffect.entries.single { e -> e.wireName == it.getString("effect") })
                } }
                assertEquals("$label runs", expected, actual.runs)
                assertEquals("$label canUndo", state.getBoolean("canUndo"), editor.canUndo)
                assertEquals("$label canRedo", state.getBoolean("canRedo"), editor.canRedo)
                val typing = state.getJSONObject("typing")
                assertEquals("$label typing style", typing.getLong("style"), actual.typingStyle)
                assertEquals("$label typing effect", if (typing.isNull("effect")) null else typing.getString("effect"), actual.typingEffect?.wireName)
                assertEquals("$label typing font", typing.getDouble("fontSize"), editor.typography.typingPoints *
                    (actual.typingEffect?.let(NativeConversationTextEffectMotion::fontScale) ?: 1.0), 1e-8)
                if (!state.isNull("inputViewIdentifier")) {
                    val selected = buildList {
                        NativeConversationTextStyle.entries.filter { editor.activeStyle and it.mask != 0L }.forEach { add("conversation.textEffects." + it.wireName) }
                        editor.activeEffect?.let { add("conversation.textEffects." + it.wireName) }
                    }
                    val controls = state.getJSONArray("selectedControls")
                    assertEquals("$label palette selection", List(controls.length()) { controls.getString(it) }, selected)
                }
                val spans = state.getJSONArray("attributes")
                repeat(spans.length()) { n ->
                    val span = spans.getJSONObject(n); val offset = span.getInt("location")
                    val effect = actual.runs.firstOrNull { offset >= it.location && offset < it.location + it.length }?.effect
                    assertEquals("$label text font at $offset", span.getJSONObject("attributes").getDouble("fontSize"),
                        editor.typography.textPoints * (effect?.let(NativeConversationTextEffectMotion::fontScale) ?: 1.0), 1e-8)
                }
                states++
            }
        }
        assertEquals(53, states)
    }

    @Test fun speculativeTypingDoesNotChangeTheAcceptedPostMetricsFont() {
        val editor = NativeChiefRichEditing(); editor.commitText("base"); editor.textMetricsChanged(28.0)
        editor.deleteSurrounding(0, 0)
        assertEquals("No document change must not restyle", 28.0, editor.typography.textPoints, 0.0)
        val candidate = editor.fork(); candidate.commitText("X")
        assertEquals(28.0, editor.typography.textPoints, 0.0)
        assertEquals(17.0, candidate.typography.textPoints, 0.0)
        editor.adopt(candidate); assertEquals(17.0, editor.typography.textPoints, 0.0)
    }
}
