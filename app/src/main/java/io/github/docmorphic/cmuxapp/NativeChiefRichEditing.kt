package io.github.docmorphic.cmuxapp

import androidx.compose.ui.text.TextRange

internal data class NativeChiefRichDraft(val text: String = "", val runs: List<NativeConversationTextRun> = emptyList(),
    val selection: TextRange = TextRange.Zero, val composition: TextRange? = null,
    val typingStyle: Long = 0, val typingEffect: NativeConversationTextEffect? = null)

/** Memory-only semantic document. The platform field remains authoritative for accepted text.
 * IME batches work on a fork and are admitted only when the real field agrees. Replacements
 * retain their exact ranges even when their old/new characters are identical. */
internal class NativeChiefRichEditing(initial: NativeChiefRichDraft = NativeChiefRichDraft()) {
    var draft = initial.copy(runs = NativeConversationRichText.normalized(initial.runs, initial.text.length))
        private set
    private data class Slice(val text: String, val runs: List<NativeConversationTextRun>)
    private data class Replace(val start: Int, val expected: String, val restore: Slice)
    private data class History(val target: TextRange, val reverseTarget: TextRange, val operations: List<Replace>)
    private var undo = emptyList<History>()
    private var redo = emptyList<History>()
    private var newGroup = true
    val canUndo get() = undo.isNotEmpty()
    val canRedo get() = redo.isNotEmpty()

    fun fork() = NativeChiefRichEditing(draft).also { it.undo = undo; it.redo = redo; it.newGroup = newGroup }
    fun adopt(other: NativeChiefRichEditing) {
        draft = other.draft; undo = other.undo; redo = other.redo; newGroup = other.newGroup
    }
    fun reset(text: String, runs: List<NativeConversationTextRun> = emptyList()) {
        draft = NativeChiefRichDraft(text, NativeConversationRichText.normalized(runs, text.length), TextRange(text.length))
        undo = emptyList(); redo = emptyList(); newGroup = true
    }
    fun breakUndoGroup() { newGroup = true }
    fun select(range: TextRange, manual: Boolean = true) {
        if (range.min < 0 || range.max > draft.text.length) return
        if (manual && range != draft.selection) {
            breakUndoGroup()
            draft = draft.copy(selection = range, typingStyle = 0, typingEffect = null)
        } else draft = draft.copy(selection = range)
    }
    fun composition(range: TextRange?) {
        if (range == null && draft.composition != null) draft = draft.copy(typingStyle = 0, typingEffect = null)
        draft = draft.copy(composition = range)
    }
    val activeStyle: Long get() = if (draft.selection.collapsed) draft.typingStyle else
        NativeConversationTextStyle.entries.fold(0L) { mask, entry ->
            if (NativeConversationRichText.hasAll(entry.mask, draft.selection.min, draft.selection.length, draft.runs, draft.text.length)) mask or entry.mask else mask
        }
    val activeEffect: NativeConversationTextEffect? get() {
        val range = if (draft.selection.collapsed) TextRange(0, draft.text.length) else draft.selection
        return if (range.collapsed) draft.typingEffect else NativeConversationRichText.commonEffect(
            range.min, range.length, draft.runs, draft.text.length)
    }
    fun toggle(style: NativeConversationTextStyle) {
        if (draft.selection.collapsed) draft = draft.copy(typingStyle = draft.typingStyle xor style.mask)
        else draft = draft.copy(runs = NativeConversationRichText.toggleStyle(style.mask, draft.selection.min,
            draft.selection.length, draft.runs, draft.text.length).runs)
    }
    fun toggle(effect: NativeConversationTextEffect) {
        val range = if (draft.selection.collapsed) TextRange(0, draft.text.length) else draft.selection
        if (range.collapsed) draft = draft.copy(typingEffect = if (draft.typingEffect == effect) null else effect)
        else draft = draft.copy(runs = NativeConversationRichText.toggleEffect(effect, range.min,
            range.length, draft.runs, draft.text.length).runs)
    }
    private fun slice(start: Int, end: Int) = Slice(draft.text.substring(start, end),
        NativeConversationRichText.normalized(draft.runs.map { it.copy(location = it.location - start) }, end - start))

    private fun replace(start: Int, end: Int, inserted: Slice): Replace {
        require(start in 0..end && end <= draft.text.length)
        val previous = slice(start, end)
        val delta = inserted.text.length - (end - start)
        val carried = buildList {
            for (run in draft.runs) {
                val upper = run.location + run.length
                val headEnd = minOf(start, upper)
                if (headEnd > run.location) add(run.copy(length = headEnd - run.location))
                val tailStart = maxOf(end, run.location)
                if (upper > tailStart) add(run.copy(location = tailStart + delta, length = upper - tailStart))
            }
            addAll(inserted.runs.map { it.copy(location = it.location + start) })
        }
        val text = draft.text.replaceRange(start, end, inserted.text)
        draft = draft.copy(text = text, runs = NativeConversationRichText.normalized(carried, text.length))
        return Replace(start, inserted.text, previous)
    }
    private fun record(operation: Replace, before: TextRange, after: TextRange) {
        redo = emptyList()
        if (newGroup || undo.isEmpty()) undo = undo + History(before, after, listOf(operation))
        else {
            val group = undo.last()
            undo = undo.dropLast(1) + group.copy(reverseTarget = after, operations = listOf(operation) + group.operations)
        }
        newGroup = false
    }
    private fun replaceTyped(start: Int, end: Int, text: String, caret: TextRange, marked: TextRange?, composing: Boolean) {
        val before = draft.selection
        val run = NativeConversationTextRun(0, text.length, draft.typingStyle, draft.typingEffect)
        val inverse = replace(start, end, Slice(text, if (run.isPlain || text.isEmpty()) emptyList() else listOf(run)))
        draft = draft.copy(selection = caret, composition = marked,
            typingStyle = if (composing) draft.typingStyle else 0,
            typingEffect = if (composing) draft.typingEffect else null)
        record(inverse, before, caret)
    }
    fun commitText(text: String, cursor: Int = 1, composing: Boolean = false) {
        val range = draft.composition ?: draft.selection
        if (range.collapsed && text.isEmpty()) return
        val count = draft.text.length - range.length + text.length
        val insertedEnd = range.min + text.length
        val caret = (if (cursor > 0) insertedEnd + cursor - 1 else insertedEnd + cursor - text.length).coerceIn(0, count)
        replaceTyped(range.min, range.max, text, TextRange(caret),
            if (composing && text.isNotEmpty()) TextRange(range.min, range.min + text.length) else null, composing)
    }
    fun replaceSelection(text: String, boundary: Boolean = false) {
        if (boundary) breakUndoGroup()
        val range = draft.selection
        replaceTyped(range.min, range.max, text, TextRange(range.min + text.length), null, false)
        if (boundary) breakUndoGroup()
    }
    fun deleteSurrounding(before: Int, after: Int, codePoints: Boolean = false) {
        if (before < 0 || after < 0) return
        val range = draft.selection
        val text = draft.text
        val beforeUnits = if (codePoints) range.start - text.offsetByCodePoints(range.start, -minOf(before, text.codePointCount(0, range.start))) else before
        val afterUnits = if (codePoints) text.offsetByCodePoints(range.end, minOf(after, text.codePointCount(range.end, text.length))) - range.end else after
        fun delete(a: Int, b: Int) {
            val start = minOf(a, b); val end = maxOf(a, b)
            if (start == end) return
            val selection = draft.selection; val marked = draft.composition
            fun adjust(range: TextRange): TextRange {
                fun offset(value: Int) = if (value <= start) value else if (value >= end) value - (end - start) else start
                return TextRange(offset(range.start), offset(range.end))
            }
            val op = replace(start, end, Slice("", emptyList()))
            val nextSelection = adjust(selection); val nextMarked = marked?.let(::adjust)?.takeUnless { it.collapsed }
            draft = draft.copy(selection = nextSelection, composition = nextMarked,
                typingStyle = if (nextMarked != null) draft.typingStyle else 0,
                typingEffect = if (nextMarked != null) draft.typingEffect else null)
            record(op, selection, nextSelection)
        }
        delete(range.end, (range.end.toLong() + afterUnits).coerceAtMost(text.length.toLong()).toInt())
        delete((draft.selection.start.toLong() - beforeUnits).coerceAtLeast(0).toInt(), draft.selection.start)
    }
    data class Change(val oldStart: Int, val oldEnd: Int, val newStart: Int, val newEnd: Int)
    fun acceptedChanges(text: String, changes: List<Change>, selection: TextRange, composing: Boolean) {
        if (changes.isEmpty()) { select(selection, manual = !composing); return }
        val before = draft.selection
        for (change in changes.asReversed()) {
            val value = text.substring(change.newStart, change.newEnd)
            val run = NativeConversationTextRun(0, value.length, draft.typingStyle, draft.typingEffect)
            record(replace(change.oldStart, change.oldEnd, Slice(value,
                if (run.isPlain || value.isEmpty()) emptyList() else listOf(run))), before, selection)
        }
        require(draft.text == text)
        draft = draft.copy(selection = selection, typingStyle = if (composing) draft.typingStyle else 0,
            typingEffect = if (composing) draft.typingEffect else null)
    }
    private fun applyHistory(entry: History): History {
        val inverses = mutableListOf<Replace>()
        for (op in entry.operations) {
            require(draft.text.regionMatches(op.start, op.expected, 0, op.expected.length))
            inverses.add(0, replace(op.start, op.start + op.expected.length, op.restore))
        }
        draft = draft.copy(selection = entry.target, composition = null, typingStyle = 0, typingEffect = null)
        return History(entry.reverseTarget, entry.target, inverses)
    }
    fun undo(): Boolean {
        if (undo.isEmpty()) return false
        val entry = undo.last(); undo = undo.dropLast(1); redo = redo + applyHistory(entry); newGroup = true
        return true
    }
    fun redo(): Boolean {
        if (redo.isEmpty()) return false
        val entry = redo.last(); redo = redo.dropLast(1); undo = undo + applyHistory(entry); newGroup = true
        return true
    }
}
