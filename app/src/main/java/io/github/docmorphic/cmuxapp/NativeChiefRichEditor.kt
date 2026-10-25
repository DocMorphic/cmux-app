package io.github.docmorphic.cmuxapp

import android.os.Handler
import android.os.Looper
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputConnectionWrapper
import android.view.View
import android.view.inputmethod.InputMethodManager
import java.lang.ref.WeakReference
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange

/** The retained editor joins accepted platform text with memory-only semantic metadata.
 * No Snapshot or TextFieldState writes run inside the input transformation. IME commands
 * use a speculative document, preserving operation intent lost by the field's batch/minimal
 * text diff. It is admitted only when the actual platform field agrees. */
@OptIn(ExperimentalFoundationApi::class)
internal class NativeChiefRichEditor(initial: String, private val permits: () -> Boolean = { true }) {
    val field = TextFieldState(initial, TextRange(initial.length))
    private val handler = Handler(Looper.getMainLooper())
    private var posted = false
    private var closed = false
    private var generation = 0L
    private var host: WeakReference<View>? = null
    private var editing = NativeChiefRichEditing(NativeChiefRichDraft(initial, selection = TextRange(initial.length)))
    private var pending: NativeChiefRichEditing? = null
    var revision by mutableLongStateOf(0)
        private set
    var coherent = true
        private set
    val snapshot get() = editing.draft
    val activeStyle get() = editing.activeStyle
    val activeEffect get() = editing.activeEffect
    val canUndo get() = editing.canUndo
    val canRedo get() = editing.canRedo
    private fun changed() {
        if (posted) return
        posted = true
        handler.post { posted = false; revision++ }
    }
    private fun allowed() = !closed && permits()
    val input = InputTransformation {
        if (!allowed()) { revertAllChanges(); handler.post { retire() }; return@InputTransformation }
        val before = originalText.toString(); val after = asCharSequence().toString()
        val candidate = pending
        if (candidate != null && candidate.draft.text == after && candidate.draft.selection == selection) {
            editing.adopt(candidate)
        } else {
            if (editing.draft.text != before) { coherent = false; editing.reset(before) }
            val deltas = (0 until changes.changeCount).map { index ->
                val old = changes.getOriginalRange(index); val new = changes.getRange(index)
                NativeChiefRichEditing.Change(old.min, old.max, new.min, new.max)
            }
            if (candidate != null) coherent = false
            editing.acceptedChanges(after, deltas, selection, field.composition != null)
        }
        changed()
    }
    /** Composition-only changes intentionally bypass InputTransformation in Foundation. */
    fun synchronizeSelectionAndComposition() {
        if (!allowed() || editing.draft.text != field.text.toString()) return
        val before = editing.draft
        if (editing.draft.selection != field.selection) editing.select(field.selection)
        if (editing.draft.composition != field.composition) editing.composition(field.composition)
        if (before != editing.draft) changed()
    }
    fun attach(view: View) { host = WeakReference(view) }
    fun detach(view: View) { if (host?.get() === view) host = null }
    private fun invalidateConnections() {
        generation++
        val view = host?.get() ?: return
        handler.post {
            if (host?.get() === view && view.isAttachedToWindow && !closed)
                view.context.getSystemService(InputMethodManager::class.java)?.restartInput(view)
        }
    }
    fun reset(text: String, runs: List<NativeConversationTextRun> = emptyList()) {
        invalidateConnections()
        pending = null; editing.reset(text, runs)
        field.edit { replace(0, length, text); selection = TextRange(text.length) }
        field.undoState.clearHistory(); changed()
    }
    fun toggle(style: NativeConversationTextStyle) {
        if (!allowed()) return
        synchronizeSelectionAndComposition(); editing.toggle(style); changed()
    }
    fun toggle(effect: NativeConversationTextEffect) {
        if (!allowed()) return
        synchronizeSelectionAndComposition(); editing.toggle(effect); changed()
    }
    fun history(redo: Boolean): Boolean {
        if (!allowed()) return false
        synchronizeSelectionAndComposition()
        if (!(if (redo) editing.redo() else editing.undo())) return false
        val value = editing.draft
        invalidateConnections()
        field.edit { replace(0, length, value.text); selection = value.selection }
        field.undoState.clearHistory(); changed(); return true
    }
    fun breakUndoGroup() = editing.breakUndoGroup()
    fun retire() {
        if (closed) return
        closed = true; reset("")
    }

    fun connection(delegate: InputConnection, sessionActive: () -> Boolean): InputConnection = object : InputConnectionWrapper(delegate, false) {
        private val connectionGeneration = generation
        private var depth = 0
        private val batchOperations = mutableListOf<(NativeChiefRichEditing) -> Unit>()
        private var connectionClosed = false
        private fun current() = !connectionClosed && connectionGeneration == generation && sessionActive() && allowed()
        private fun prediction() = editing.fork().also { model ->
            model.composition(field.composition); model.select(field.selection, manual = false)
            batchOperations.forEach { it(model) }
        }
        private fun operation(predict: (NativeChiefRichEditing) -> Unit, actual: () -> Boolean): Boolean {
            if (!current()) return false
            if (depth > 0) {
                // Foundation queues operations until the outer batch ends. Replay them
                // against the then-current accepted document, so a palette/selection
                // change made while the batch is open is not overwritten by an old fork.
                return actual().also { if (it) batchOperations += predict }
            }
            val predicted = prediction(); predict(predicted)
            val prior = pending; pending = predicted
            return try {
                val result = actual()
                if (result) admit(predicted)
                result
            } finally { pending = prior }
        }
        private fun admit(predicted: NativeChiefRichEditing) {
            // An invalidated/stale platform connection can truthfully acknowledge an ignored
            // command. Never adopt its prediction unless the real field committed it.
            if (current() && predicted.draft.text == field.text.toString() && predicted.draft.selection == field.selection &&
                predicted.draft.composition == field.composition) {
                editing.adopt(predicted); changed()
            }
        }
        override fun beginBatchEdit(): Boolean {
            if (!current()) return false
            if (depth++ == 0) batchOperations.clear()
            return super.beginBatchEdit().also { if (!it && --depth == 0) batchOperations.clear() }
        }
        override fun endBatchEdit(): Boolean {
            if (!current()) return false
            val value = if (depth > 0 && batchOperations.isNotEmpty()) prediction() else null
            val prior = pending; pending = value
            return try {
                val result = super.endBatchEdit()
                if (depth > 0) depth--
                if (depth == 0) { value?.let(::admit); batchOperations.clear() }
                result
            } finally { pending = prior }
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString() ?: return super.commitText(text, newCursorPosition)
            return operation({ it.commitText(value, newCursorPosition) }, { super.commitText(text, newCursorPosition) })
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            val value = text?.toString() ?: return super.setComposingText(text, newCursorPosition)
            return operation({ it.commitText(value, newCursorPosition, composing = true) }, { super.setComposingText(text, newCursorPosition) })
        }
        @androidx.annotation.RequiresApi(33)
        override fun commitText(text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean {
            val value = text.toString()
            return operation({ it.commitText(value, newCursorPosition) }, { super.commitText(text, newCursorPosition, textAttribute) })
        }
        @androidx.annotation.RequiresApi(33)
        override fun setComposingText(text: CharSequence, newCursorPosition: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean {
            val value = text.toString()
            return operation({ it.commitText(value, newCursorPosition, composing = true) }, { super.setComposingText(text, newCursorPosition, textAttribute) })
        }
        @androidx.annotation.RequiresApi(33)
        override fun setComposingRegion(start: Int, end: Int, textAttribute: android.view.inputmethod.TextAttribute?): Boolean = operation({ model ->
            val a = start.coerceIn(0, model.draft.text.length); val b = end.coerceIn(0, model.draft.text.length)
            model.composition(if (a == b) null else TextRange(minOf(a, b), maxOf(a, b)))
        }, { super.setComposingRegion(start, end, textAttribute) })
        @androidx.annotation.RequiresApi(34)
        override fun replaceText(start: Int, end: Int, text: CharSequence, newCursorPosition: Int,
            textAttribute: android.view.inputmethod.TextAttribute?): Boolean {
            val value = text.toString()
            return operation({ model ->
            model.composition(null)
            model.select(TextRange(start.coerceIn(0, model.draft.text.length), end.coerceIn(0, model.draft.text.length)))
            model.commitText(value, newCursorPosition)
        }, { super.replaceText(start, end, text, newCursorPosition, textAttribute) })
        }
        override fun setComposingRegion(start: Int, end: Int): Boolean = operation({ model ->
            val a = start.coerceIn(0, model.draft.text.length); val b = end.coerceIn(0, model.draft.text.length)
            model.composition(if (a == b) null else TextRange(minOf(a, b), maxOf(a, b)))
        }, { super.setComposingRegion(start, end) })
        override fun finishComposingText(): Boolean = operation({ it.composition(null) }, { super.finishComposingText() })
        override fun setSelection(start: Int, end: Int): Boolean = operation({ model ->
            model.select(TextRange(start.coerceIn(0, model.draft.text.length), end.coerceIn(0, model.draft.text.length)), manual = model.draft.composition == null)
        }, { super.setSelection(start, end) })
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean =
            if (beforeLength < 0 || afterLength < 0) false else operation({ it.deleteSurrounding(beforeLength, afterLength) },
                { super.deleteSurroundingText(beforeLength, afterLength) })
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
            if (beforeLength < 0 || afterLength < 0) false else operation({ it.deleteSurrounding(beforeLength, afterLength, true) },
                { super.deleteSurroundingTextInCodePoints(beforeLength, afterLength) })
        override fun performContextMenuAction(id: Int): Boolean {
            if (!current()) return false
            if (id == android.R.id.undo) return history(false)
            if (id == android.R.id.redo) return history(true)
            if (id == android.R.id.paste || id == android.R.id.cut) breakUndoGroup()
            return super.performContextMenuAction(id)
        }
        override fun closeConnection() {
            connectionClosed = true; depth = 0; batchOperations.clear(); super.closeConnection()
        }
    }
}
