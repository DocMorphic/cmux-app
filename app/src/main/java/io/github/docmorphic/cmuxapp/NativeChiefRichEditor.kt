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
    private var inputMount: Any? = null
    private var editing = NativeChiefRichEditing(NativeChiefRichDraft(initial, selection = TextRange(initial.length)))
    private var pending: NativeChiefRichEditing? = null
    private data class ClipboardEdit(val owner: Any, val before: String, val range: TextRange, val replacement: String) {
        fun matches(text: String, after: String, selection: TextRange, changes: List<NativeChiefRichEditing.Change>): Boolean =
            text == before && after == before.replaceRange(range.min, range.max, replacement) &&
                selection == TextRange(range.min + replacement.length) && changes.singleOrNull()?.let {
                    it.oldStart == range.min && it.oldEnd == range.max && it.newStart == range.min && it.newEnd == range.min + replacement.length
                } == true
    }
    private var clipboardEdit: ClipboardEdit? = null
    var visitGeneration by mutableLongStateOf(0)
        private set
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
    val acceptsInput get() = allowed()
    fun prepareClipboardEdit(owner: Any, replacement: String?) {
        clipboardEdit = if (allowed() && replacement != null)
            ClipboardEdit(owner, field.text.toString(), field.selection, replacement) else null
    }
    fun forgetClipboardEdit(owner: Any) { if (clipboardEdit?.owner === owner) clipboardEdit = null }
    fun isInputMountCurrent(owner: Any, visit: Long) = inputMount === owner && visitGeneration == visit && allowed()
    fun input(owner: Any, visit: Long) = InputTransformation {
        if (!allowed()) { revertAllChanges(); handler.post { retire() }; return@InputTransformation }
        if (!isInputMountCurrent(owner, visit)) { revertAllChanges(); return@InputTransformation }
        val before = originalText.toString(); val after = asCharSequence().toString()
        val deltas = (0 until changes.changeCount).map { index ->
            val old = changes.getOriginalRange(index); val new = changes.getRange(index)
            NativeChiefRichEditing.Change(old.min, old.max, new.min, new.max)
        }
        val isolate = clipboardEdit?.matches(before, after, selection, deltas) == true
        clipboardEdit = null
        if (isolate) editing.breakUndoGroup()
        val candidate = pending
        if (candidate != null && candidate.draft.text == after && candidate.draft.selection == selection) {
            editing.adopt(candidate)
        } else {
            if (editing.draft.text != before) { coherent = false; editing.reset(before) }
            if (candidate != null) coherent = false
            editing.acceptedChanges(after, deltas, selection, field.composition != null)
        }
        if (isolate) editing.breakUndoGroup()
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
    fun attach(view: View, owner: Any, visit: Long) {
        if (visit == visitGeneration && allowed()) { host = WeakReference(view); inputMount = owner }
    }
    fun detach(view: View, owner: Any) {
        if (host?.get() === view && inputMount === owner) { host = null; inputMount = null }
    }
    fun suspendInput(owner: Any, visit: Long) {
        if (inputMount === owner && visitGeneration == visit) {
            // Pause gates commands without retiring the same node's connection.
            // Replaced mounts and ended visits remain permanently fenced.
            inputMount = null; clipboardEdit = null
        }
    }
    private fun invalidateConnections(restartInput: Boolean = true) {
        generation++
        clipboardEdit = null
        if (!restartInput) return
        val view = host?.get() ?: return
        val expected = generation
        handler.post {
            if (generation == expected && inputMount != null && host?.get() === view && view.isAttachedToWindow && !closed)
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
    fun endVisit() {
        visitGeneration++; inputMount = null; host = null
        invalidateConnections(restartInput = false); editing.breakUndoGroup()
    }
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
