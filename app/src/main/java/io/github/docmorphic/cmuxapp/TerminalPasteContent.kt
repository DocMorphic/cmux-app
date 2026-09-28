package io.github.docmorphic.cmuxapp

import android.content.ClipData
import android.content.Context
import android.net.Uri
import java.util.concurrent.atomic.AtomicBoolean

/** Owns a temporary IME grant until the provider bytes have been consumed or discarded. */
class TerminalPasteContent(val items: List<Item>, private val release: () -> Unit = {}) : AutoCloseable {
    sealed interface Item {
        data class Text(val value: String) : Item
        data class Attachment(val uri: Uri, val image: Boolean) : Item
    }
    private val closed = AtomicBoolean()
    override fun close() { if (closed.compareAndSet(false, true)) runCatching(release) }

    companion object {
        /** Never coerce a local content URI or Intent into a shell command. */
        fun fromClipboard(context: Context, clip: ClipData): TerminalPasteContent {
            require(clip.itemCount <= 10) { "Paste up to 10 items at a time" }
            val items = (0 until clip.itemCount).map { index ->
                val item = clip.getItemAt(index)
                val uri = item.uri
                val image = uri?.scheme == "content" && context.contentResolver.getType(uri)?.startsWith("image/") == true
                when {
                    image -> Item.Attachment(uri!!, true)
                    item.text != null -> Item.Text(item.text.toString())
                    uri?.scheme == "content" -> Item.Attachment(uri, false)
                    uri?.scheme == "https" || uri?.scheme == "http" -> Item.Text(uri.toString())
                    else -> error("This clipboard item cannot be pasted into a terminal")
                }
            }
            return TerminalPasteContent(items)
        }
    }
}
