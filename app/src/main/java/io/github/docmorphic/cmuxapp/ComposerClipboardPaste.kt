package io.github.docmorphic.cmuxapp

import android.content.ClipboardManager
import android.content.Context
import android.view.KeyEvent

/** One explicit paste action. Metadata probes never open providers or stage content. */
internal class ComposerClipboardPaste(
    private val context: Context,
    private val current: () -> Boolean,
    private val enabled: () -> Boolean,
    private val receive: (TerminalPasteContent) -> Boolean,
    private val report: (String) -> Unit
) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)

    fun mayHaveAttachments(): Boolean {
        val description = clipboard.primaryClipDescription ?: return false
        return description.hasMimeType("image/*") || description.hasMimeType("text/uri-list") ||
            (0 until description.mimeTypeCount).any { !description.getMimeType(it).startsWith("text/") }
    }

    /** False leaves ordinary text to the native editor, including its current selection. */
    fun paste(): Boolean {
        if (!current()) return true
        return try {
            val clip = clipboard.primaryClip ?: return false
            val items = (0 until clip.itemCount).map { clip.getItemAt(it) }
            val attachments = items.mapNotNull { it.uri?.takeIf { uri -> uri.scheme == "content" } }
            if (attachments.isEmpty()) return false
            require(enabled()) { "Attachments aren't available in this composer right now." }
            require(clip.itemCount <= 10) { "Paste up to 10 items at a time" }
            val content = TerminalPasteContent(attachments.map { uri ->
                TerminalPasteContent.Item.Attachment(uri,
                    context.contentResolver.getType(uri)?.startsWith("image/") == true)
            })
            var accepted = false
            try {
                // iOS consumes an attachment paste as a whole; captions/URI fallback text
                // must not also enter the prompt or become terminal commands.
                accepted = current() && enabled() && receive(content)
                if (!accepted && current()) report("Attachments couldn't be added. Please try again.")
            } finally { if (!accepted) content.close() }
            true
        } catch (failure: Exception) {
            if (current()) report(failure.message ?: "Could not open the copied attachment")
            true // Never fall back to inserting provider URIs after a failed attachment paste.
        }
    }

    fun key(event: KeyEvent): Boolean = event.action == KeyEvent.ACTION_DOWN &&
        ((event.keyCode == KeyEvent.KEYCODE_V && (event.isCtrlPressed || event.isMetaPressed) && !event.isAltPressed) ||
            (event.keyCode == KeyEvent.KEYCODE_INSERT && event.isShiftPressed)) && paste()
}
