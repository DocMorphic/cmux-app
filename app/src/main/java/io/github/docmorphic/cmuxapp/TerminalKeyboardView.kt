package io.github.docmorphic.cmuxapp

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.text.Editable
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.view.inputmethod.InputContentInfo
import android.widget.TextView

/** An IME endpoint, not a local copy of the remote terminal's editable contents. */
class TerminalKeyboardView(context: Context) : TextView(context) {
    var onText: (String) -> Unit = {}
    var onKey: (KeyEvent) -> Boolean = { false }
    var onPaste: (String) -> Unit = {}
    /** A true result transfers ownership, including releasing any temporary provider grant. */
    var onContent: ((TerminalPasteContent) -> Boolean)? = null
    var onContentError: (String) -> Unit = {}
    var onDelete: (Int, Int) -> Unit = { before, after ->
        if (before > 0) onText("\u007f".repeat(before))
        if (after > 0) onText("\u001b[3~".repeat(after))
    }
    var onReturn: () -> Unit = { onText("\r") }
    var imeAction: Int = EditorInfo.IME_ACTION_NONE
    private var connection: TerminalConnection? = null

    init {
        isFocusable = true; isFocusableInTouchMode = true
        gravity = Gravity.CENTER_VERTICAL
        maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
        setPadding(16, 0, 16, 0)
        setTextColor(Color.rgb(155, 159, 168)); textSize = 13f
        contentDescription = "Direct terminal input"
        showComposition("")
        setOnClickListener { showKeyboard() }
    }

    fun showKeyboard() {
        requestFocus()
        post { if (hasFocus() && isAttachedToWindow) manager().showSoftInput(this, InputMethodManager.SHOW_IMPLICIT) }
    }

    fun restartKeyboard() {
        manager().restartInput(this)
        showKeyboard()
    }

    fun finishComposition() { connection?.finishComposingText() }
    fun dispose() {
        connection?.invalidate(); connection = null
        manager().hideSoftInputFromWindow(windowToken, 0)
        clearFocus()
    }

    override fun onDetachedFromWindow() { dispose(); super.onDetachedFromWindow() }
    override fun onCheckIsTextEditor() = true
    override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection? {
        if (!isEnabled) return null
        connection?.invalidate()
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        outAttrs.imeOptions = imeAction or EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
        outAttrs.initialSelStart = 1; outAttrs.initialSelEnd = 1
        outAttrs.contentMimeTypes = if (onContent != null) arrayOf("image/*") else null
        return TerminalConnection().also { connection = it }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (!isEnabled) return false
        if ((event.isCtrlPressed && event.isShiftPressed || event.isMetaPressed) && keyCode == KeyEvent.KEYCODE_V) {
            pasteClipboard()
            return true
        }
        val composing = connection?.hasComposition() == true
        if (keyCode == KeyEvent.KEYCODE_DEL && composing) return connection?.deleteSurroundingTextInCodePoints(1, 0) == true
        if (keyCode == KeyEvent.KEYCODE_ESCAPE && composing) connection?.clearBuffer()
        else if (!KeyEvent.isModifierKey(keyCode)) finishComposition()
        return onKey(event) || super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (keyCode == KeyEvent.KEYCODE_BACK || keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
            super.onKeyUp(keyCode, event) else true

    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
        super.onInitializeAccessibilityNodeInfo(info)
        info.className = "android.widget.EditText"
        info.isEditable = true
        info.addAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_TEXT)
    }

    override fun performAccessibilityAction(action: Int, arguments: Bundle?): Boolean {
        if (action == AccessibilityNodeInfo.ACTION_SET_TEXT && isEnabled) {
            val value = arguments?.getCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE)?.toString() ?: return false
            connection?.clearBuffer(); onText(value); return true
        }
        return super.performAccessibilityAction(action, arguments)
    }

    private fun manager() = context.getSystemService(InputMethodManager::class.java)
    fun pasteClipboard(plainTextOnly: Boolean = false): Boolean {
        if (!isEnabled) return false
        finishComposition()
        return try {
            val clip = context.getSystemService(android.content.ClipboardManager::class.java).primaryClip ?: return false
            val content = TerminalPasteContent.fromClipboard(context, clip)
            if (plainTextOnly && content.items.any { it !is TerminalPasteContent.Item.Text }) {
                content.close(); false
            } else acceptContent(content)
        } catch (failure: Exception) {
            onContentError(failure.message ?: "Could not read the clipboard"); false
        }
    }

    override fun onTextContextMenuItem(id: Int): Boolean = when (id) {
        android.R.id.paste -> pasteClipboard()
        android.R.id.pasteAsPlainText -> pasteClipboard(plainTextOnly = true)
        else -> false
    }

    private fun acceptContent(content: TerminalPasteContent): Boolean {
        var accepted = false
        try {
            val receiver = onContent
            accepted = if (receiver != null) receiver(content) else {
                // Browser keyboard reuse remains text-only, without URI coercion.
                if (content.items.all { it is TerminalPasteContent.Item.Text }) {
                    onPaste(content.items.joinToString("\n") { (it as TerminalPasteContent.Item.Text).value })
                    content.close(); true
                } else false
            }
            return accepted
        } finally { if (!accepted) content.close() }
    }
    private fun showComposition(value: String) { text = value.ifEmpty { "Direct typing · tap here for keyboard" } }

    private inner class TerminalConnection : BaseInputConnection(this@TerminalKeyboardView, true) {
        private val buffer = SpannableStringBuilder("\u200b").apply { Selection.setSelection(this, 1) }
        private var live = true
        private fun ready() = live && connection === this && isEnabled
        override fun getEditable(): Editable = buffer
        fun hasComposition(): Boolean = getComposingSpanStart(buffer) >= 1 && getComposingSpanEnd(buffer) > getComposingSpanStart(buffer)
        fun invalidate() { live = false; clearBuffer() }
        fun clearBuffer() {
            removeComposingSpans(buffer)
            buffer.replace(0, buffer.length, "\u200b")
            Selection.setSelection(buffer, 1)
            if (connection === this) { showComposition(""); updateSelection() }
        }
        private fun updateSelection() {
            manager().updateSelection(this@TerminalKeyboardView, Selection.getSelectionStart(buffer), Selection.getSelectionEnd(buffer),
                getComposingSpanStart(buffer), getComposingSpanEnd(buffer))
        }
        override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!ready()) return false
            val committed = text?.toString().orEmpty()
            clearBuffer()
            if (committed.isNotEmpty()) onText(committed)
            return true
        }
        override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
            if (!ready()) return false
            if (text.isNullOrEmpty()) { clearBuffer(); return true }
            super.setComposingText(text, newCursorPosition)
            showComposition(buffer.toString().removePrefix("\u200b")); updateSelection()
            return true
        }
        override fun setSelection(start: Int, end: Int): Boolean {
            if (!ready()) return false
            return super.setSelection(start.coerceIn(1, buffer.length), end.coerceIn(1, buffer.length))
        }
        override fun setComposingRegion(start: Int, end: Int): Boolean {
            if (!ready()) return false
            return super.setComposingRegion(start.coerceIn(1, buffer.length), end.coerceIn(1, buffer.length))
        }
        override fun finishComposingText(): Boolean {
            if (!ready()) return false
            val start = getComposingSpanStart(buffer)
            val end = getComposingSpanEnd(buffer)
            val value = if (start >= 1 && end > start) buffer.subSequence(start, end).toString() else ""
            clearBuffer()
            if (value.isNotEmpty()) onText(value)
            return true
        }
        override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, false)
        override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean = delete(beforeLength, afterLength, true)
        private fun delete(before: Int, after: Int, codePoints: Boolean): Boolean {
            if (!ready() || before !in 0..4096 || after !in 0..4096) return false
            if (hasComposition()) {
                val cursor = Selection.getSelectionStart(buffer).coerceAtLeast(1)
                val end = Selection.getSelectionEnd(buffer).coerceAtLeast(cursor)
                var from = if (codePoints) Character.offsetByCodePoints(buffer, cursor,
                    -minOf(before, Character.codePointCount(buffer, 1, cursor))) else (cursor - before).coerceAtLeast(1)
                var to = if (codePoints) Character.offsetByCodePoints(buffer, end,
                    minOf(after, Character.codePointCount(buffer, end, buffer.length))) else (end + after).coerceAtMost(buffer.length)
                // Some IMEs request UTF-16 deletion one unit at a time; never retain half a code point.
                if (from > 1 && from < buffer.length && Character.isLowSurrogate(buffer[from]) && Character.isHighSurrogate(buffer[from - 1])) from--
                if (to > 1 && to < buffer.length && Character.isLowSurrogate(buffer[to]) && Character.isHighSurrogate(buffer[to - 1])) to++
                buffer.delete(from, to)
                Selection.setSelection(buffer, from)
                if (buffer.length == 1) clearBuffer()
                else { showComposition(buffer.toString().removePrefix("\u200b")); updateSelection() }
            } else {
                clearBuffer()
                onDelete(before, after)
            }
            return true
        }
        override fun sendKeyEvent(event: KeyEvent): Boolean {
            if (!ready()) return false
            return when (event.action) {
                KeyEvent.ACTION_DOWN -> this@TerminalKeyboardView.onKeyDown(event.keyCode, event)
                KeyEvent.ACTION_UP -> true
                KeyEvent.ACTION_MULTIPLE -> event.characters?.let { onText(it); true } ?: false
                else -> false
            }
        }
        override fun performEditorAction(actionCode: Int): Boolean {
            if (!ready()) return false
            finishComposingText(); onReturn(); return true
        }
        override fun performContextMenuAction(id: Int): Boolean =
            ready() && onTextContextMenuItem(id)

        override fun commitContent(info: InputContentInfo, flags: Int, opts: Bundle?): Boolean {
            if (!ready() || onContent == null) return false
            return TerminalPasteContent.receiveImage(info, flags, ::acceptContent, onContentError) { finishComposingText() }
        }
        override fun closeConnection() { invalidate(); super.closeConnection() }
    }
}
