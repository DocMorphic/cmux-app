package io.github.docmorphic.cmuxapp

/** Cached controls describe an intent; only the newest capabilities can admit it. */
internal data class WorkspaceRowActionPolicy(val read: Boolean, val close: Boolean,
    val edit: Boolean, val customize: Boolean, val groups: Boolean, val changes: Boolean) {
    fun permits(action: String): Boolean = when (action) {
        "mark_read", "mark_unread" -> read
        "close" -> close
        "pin", "unpin", "rename" -> edit
        "customize" -> customize
        "changes" -> changes
        else -> action.startsWith("move:") && groups
    }
}
