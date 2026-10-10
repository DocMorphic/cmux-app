package io.github.docmorphic.cmuxapp

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.text.TextRange

/** Bridges the synchronous native editor to durable drafts without replaying unchanged text. */
internal class TaskComposerPromptBinding(val state: TextFieldState, initialDraftText: String) {
    private var publishedText = initialDraftText

    /** Called on observed edits and again before an action can save or submit the draft. */
    fun sync(draftText: String?, publish: (String) -> Boolean) {
        if (draftText == null) return
        if (draftText != publishedText) {
            replaceExternal(draftText)
            publishedText = draftText
            return
        }
        val edited = state.text.toString()
        if (edited == draftText) return
        if (publish(edited)) publishedText = edited else replaceExternal(draftText)
    }

    private fun replaceExternal(text: String) {
        if (state.text.toString() == text) return
        val previous = state.selection
        state.edit {
            replace(0, length, text)
            selection = TextRange(previous.start.coerceIn(0, length), previous.end.coerceIn(0, length))
        }
    }
}
