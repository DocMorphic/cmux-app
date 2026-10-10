/* Scoped port of cmux TaskComposerPromptEditorCoordinator.swift / PromptTextView
 * at f4b1509. Copyright (c) 2024-present Manaflow, Inc. GPL-3.0-or-later.
 * See NOTICE.md. */
package io.github.docmorphic.cmuxapp

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.semantics.SemanticsPropertyKey

/** Observable idle state for runtime checks; adds no spoken accessibility label. */
internal val TaskPromptScrolling = SemanticsPropertyKey<Boolean>("TaskPromptScrolling")

/** A dragged viewport outranks automatic caret layout until text/selection changes. */
internal class TaskComposerPromptViewport(offset: Int? = null) {
    var manualOffset by mutableStateOf(offset); private set
    var tracking by mutableStateOf(false); private set
    private var dragEnded = false
    private var previousText: String? = null
    private var previousSelection: TextRange? = null

    private fun trace(event: String) {
        if (BuildConfig.DEBUG && java.lang.Boolean.getBoolean("cmux.prompt.viewport.trace"))
            android.util.Log.d("cmux.prompt.viewport", "$event manual=$manualOffset tracking=$tracking")
    }

    fun editor(text: String, selection: TextRange) {
        if (previousText != null && (previousText != text || previousSelection != selection)) {
            trace("release textChanged=${previousText != text} selection=$previousSelection->$selection")
            manualOffset = null; tracking = false; dragEnded = false
        }
        previousText = text; previousSelection = selection
    }
    fun beginDrag(offset: Int) {
        manualOffset = offset.coerceAtLeast(0); tracking = true; dragEnded = false
        trace("drag start=$offset")
    }
    fun scroll(offset: Int, inProgress: Boolean) {
        if (!tracking) return
        manualOffset = offset.coerceAtLeast(0)
        if (!inProgress && dragEnded) { tracking = false; trace("scroll ended=$offset") }
    }
    fun endDrag(offset: Int, inProgress: Boolean) {
        if (!tracking) return
        manualOffset = offset.coerceAtLeast(0)
        dragEnded = true
        if (!inProgress) tracking = false
        trace("drag stop=$offset scrolling=$inProgress")
    }
    fun target(maximum: Int): Int? = if (tracking) null else manualOffset?.coerceIn(0, maximum.coerceAtLeast(0))

    companion object {
        val Saver = listSaver<TaskComposerPromptViewport, Int>(
            save = { listOf(it.manualOffset ?: -1) },
            restore = { TaskComposerPromptViewport(it.single().takeIf { value -> value >= 0 }) })
    }
}
