package io.github.docmorphic.cmuxapp

import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Observe the stock field's clipboard transactions without replacing its menu,
 * text coercion, selection behavior or platform clipboard. Foundation 1.9.1's
 * Android paste reads the first item's plain text synchronously after getClipEntry;
 * cut writes that text immediately before deleting its selection. Availability
 * queries use nativeClipboard metadata and do not arm an edit. */
internal class NativeChiefClipboard(private val delegate: Clipboard, private val editor: NativeChiefRichEditor,
    private val mounted: () -> Boolean) :
    Clipboard by delegate, AutoCloseable {
    private val token = Any()
    private var open = true
    private fun current() = open && mounted() && editor.acceptsInput
    override suspend fun getClipEntry(): ClipEntry? {
        if (!current()) return null
        val entry = delegate.getClipEntry()
        currentCoroutineContext().ensureActive()
        if (!current()) return null
        val text = entry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        editor.prepareClipboardEdit(token, text)
        return entry
    }
    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        if (!current()) return
        delegate.setClipEntry(clipEntry)
        currentCoroutineContext().ensureActive()
        if (!current()) return
        val text = clipEntry?.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
        val range = editor.field.selection
        val selected = editor.field.text.toString().substring(range.min, range.max)
        // Copy uses the same write, but its subsequent selection-only transaction
        // cannot match the armed deletion. It never changes rich undo grouping.
        editor.prepareClipboardEdit(token, "".takeIf { !range.collapsed && text == selected })
    }
    override fun close() { open = false; editor.forgetClipboardEdit(token) }
}
