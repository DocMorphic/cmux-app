package io.github.docmorphic.cmuxapp

/** Account-owned, bounded in-memory drafts, like the iOS pending-image store.
 * Renderers may come and go; providers prune bindings when live topology proves removal.
 * A disconnected transport does not discard a persistent terminal draft. Payloads never enter saved instance state. */
internal class SshComposerPool : AutoCloseable {
    private val drafts = TerminalDrafts()
    private val payloads = mutableMapOf<String, ByteArray>()
    private val bindings = mutableMapOf<String, Draft>()
    private var closed = false
    val state = drafts.state

    @Synchronized fun open(id: String, route: Any? = null): Draft {
        check(!closed) { "SSH account ended" }
        if (bindings[id]?.route != route) bindings[id]?.close()
        return bindings.getOrPut(id) { Draft(id, route) }
    }

    inner class Draft internal constructor(private val id: String, internal val route: Any?) : AutoCloseable {
        val target = TerminalDrafts.Target("ssh", "ssh", id)
        val state get() = this@SshComposerPool.state
        val current get() = state.value[target] ?: TerminalDrafts.Draft()
        private fun valid() = !closed && bindings[id] === this
        private fun guard() { check(valid()) { "SSH draft retired" } }
        fun edit(text: String): Boolean = synchronized(this@SshComposerPool) {
            if (!valid()) false else { drafts.edit(target, text); true }
        }
        fun attach(attachment: ComposerAttachment, bytes: ByteArray) = synchronized(this@SshComposerPool) {
            guard()
            require(attachment.imageFormat in listOf("png", "jpg") && attachment.size == bytes.size)
            require(attachment.id !in payloads) { "Image already staged" }
            drafts.attach(target, attachment, drafts.generation)
            payloads[attachment.id] = bytes.copyOf()
        }
        fun remove(id: String) = synchronized(this@SshComposerPool) {
            if (valid() && current.attachments.any { it.id == id }) {
                drafts.removeAttachment(target, id); payloads.remove(id)?.fill(0)
            }
        }
        fun read(attachment: ComposerAttachment): ByteArray = synchronized(this@SshComposerPool) {
            guard(); check(attachment in current.attachments)
            checkNotNull(payloads[attachment.id]) { "Image data is unavailable" }.copyOf()
        }
        fun begin(): TerminalDrafts.Send? = synchronized(this@SshComposerPool) { if (valid()) drafts.begin(target) else null }
        fun contains(send: TerminalDrafts.Send, attachment: ComposerAttachment) = synchronized(this@SshComposerPool) {
            !closed && bindings[id] === this && drafts.contains(send, attachment)
        }
        fun accepted(send: TerminalDrafts.Send, attachment: ComposerAttachment) = synchronized(this@SshComposerPool) {
            if (contains(send, attachment)) remove(attachment.id)
        }
        fun finish(send: TerminalDrafts.Send, error: String? = null) = synchronized(this@SshComposerPool) {
            if (!closed && bindings[id] === this) drafts.finish(send, error)
        }
        override fun close() = synchronized(this@SshComposerPool) {
            if (bindings[id] === this) {
                current.attachments.forEach { payloads.remove(it.id)?.fill(0) }
                drafts.discard(target); bindings.remove(id)
            }
        }
    }

    @Synchronized fun discardWhere(removed: (String) -> Boolean) {
        bindings.keys.filter(removed).forEach { bindings[it]?.close() }
    }

    @Synchronized override fun close() {
        closed = true; payloads.values.forEach { it.fill(0) }; payloads.clear()
        bindings.clear(); drafts.clear()
    }
}
