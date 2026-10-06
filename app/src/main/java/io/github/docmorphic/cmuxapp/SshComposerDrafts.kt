package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Account-owned composer bindings with transient images or an injected persistent text store.
 * Renderers may come and go; providers prune bindings when live topology proves removal.
 * Closing a persistent pool retains text. Image payloads never enter saved instance state. */
internal class SshComposerPool(
    private val drafts: TerminalDrafts = TerminalDrafts(),
    private val targetFor: (String) -> TerminalDrafts.Target = { TerminalDrafts.Target("ssh", "ssh", it) },
    private val persist: (suspend () -> Unit)? = null,
    private val currentOwner: () -> Boolean = { true }
) : AutoCloseable {
    private val payloads = mutableMapOf<String, ByteArray>()
    private val bindings = mutableMapOf<String, Draft>()
    private var closed = false
    val state = drafts.state

    @Synchronized fun open(id: String, route: Any? = null): Draft {
        check(!closed) { "SSH account ended" }
        if (bindings[id]?.route != route || bindings[id]?.isActive() == false) bindings[id]?.close()
        return bindings.getOrPut(id) { Draft(id, route) }
    }

    private fun retainPayloads() {
        val retained = drafts.state.value.values.flatMap { it.attachments }.mapTo(hashSetOf()) { it.id } + drafts.previewAttachmentIds()
        payloads.keys.filter { it !in retained }.forEach { payloads.remove(it)?.fill(0) }
    }

    inner class Draft internal constructor(private val id: String, internal val route: Any?) : AutoCloseable {
        val previewBinding = java.util.UUID.randomUUID().toString()
        val target = targetFor(id)
        private val generation = drafts.generation
        val state get() = this@SshComposerPool.state
        val current get() = state.value[target] ?: TerminalDrafts.Draft()
        private fun valid() = !closed && currentOwner() && drafts.generation == generation && bindings[id] === this
        private fun guard() { check(valid()) { "SSH draft retired" } }
        fun edit(text: String): Boolean = synchronized(this@SshComposerPool) {
            if (!valid()) false else { drafts.edit(target, text); true }
        }
        fun attach(attachment: ComposerAttachment, bytes: ByteArray) = synchronized(this@SshComposerPool) {
            guard()
            check(persist == null) { "This persistent composer accepts text only" }
            require(attachment.imageFormat in listOf("png", "jpg") && attachment.size == bytes.size)
            require(attachment.id !in payloads) { "Image already staged" }
            drafts.attach(target, attachment, drafts.generation)
            payloads[attachment.id] = bytes.copyOf()
        }
        fun remove(id: String) = synchronized(this@SshComposerPool) {
            if (valid() && current.attachments.any { it.id == id }) {
                drafts.removeAttachment(target, id); retainPayloads()
            }
        }
        fun isActive(): Boolean = synchronized(this@SshComposerPool) { valid() }
        fun ownsAttachment(attachment: ComposerAttachment): Boolean = synchronized(this@SshComposerPool) {
            valid() && attachment in current.attachments && payloads.containsKey(attachment.id)
        }
        fun read(attachment: ComposerAttachment): ByteArray = synchronized(this@SshComposerPool) {
            guard(); check(attachment in current.attachments)
            checkNotNull(payloads[attachment.id]) { "Image data is unavailable" }.copyOf()
        }
        fun preview(attachment: ComposerAttachment): ComposerAttachmentSnapshot? = synchronized(this@SshComposerPool) {
            if (!ownsAttachment(attachment)) return@synchronized null
            val lease = drafts.preview(target, attachment, drafts.generation) ?: return@synchronized null
            object : ComposerAttachmentSnapshot {
                override val attachment = lease.attachment
                override val active = lease.active
                override suspend fun read(): ByteArray = withContext(Dispatchers.Default) {
                    synchronized(this@SshComposerPool) {
                        guard(); check(drafts.ownsPreview(lease)) { "The SSH preview was closed." }
                        checkNotNull(payloads[attachment.id]).copyOf()
                    }
                }
                override fun close() = synchronized(this@SshComposerPool) {
                    drafts.releasePreview(lease); retainPayloads()
                }
            }
        }
        /** Journal pending delivery before any bytes leave the composer. */
        suspend fun persistPending(send: TerminalDrafts.Send) {
            if (persist == null) return
            synchronized(this@SshComposerPool) { guard(); check(current.operation == send.operation && send.target == target) }
            persist.invoke()
            synchronized(this@SshComposerPool) { guard(); check(current.operation == send.operation && send.target == target) }
        }
        internal fun interruptPending() {
            if (drafts.generation != generation) return
            val draft = current
            draft.operation?.let { operation -> drafts.finish(TerminalDrafts.Send(target, draft.text, draft.revision, operation, draft.attachments),
                TerminalDrafts.DELIVERY_UNCONFIRMED) }
        }
        fun begin(): TerminalDrafts.Send? = synchronized(this@SshComposerPool) { if (valid()) drafts.begin(target) else null }
        fun contains(send: TerminalDrafts.Send, attachment: ComposerAttachment) = synchronized(this@SshComposerPool) {
            valid() && drafts.contains(send, attachment)
        }
        fun accepted(send: TerminalDrafts.Send, attachment: ComposerAttachment) = synchronized(this@SshComposerPool) {
            if (contains(send, attachment)) remove(attachment.id)
        }
        fun finish(send: TerminalDrafts.Send, error: String? = null) = synchronized(this@SshComposerPool) {
            if (valid()) drafts.finish(send, error)
        }
        override fun close() = synchronized(this@SshComposerPool) {
            if (bindings[id] === this) {
                if (drafts.generation == generation) drafts.discard(target)
                bindings.remove(id); retainPayloads()
            }
        }
    }

    @Synchronized fun discardWhere(removed: (String) -> Boolean) {
        bindings.keys.filter(removed).forEach { bindings[it]?.close() }
    }

    @Synchronized override fun close() {
        closed = true; payloads.values.forEach { it.fill(0) }; payloads.clear()
        if (persist == null) drafts.clear() else bindings.values.forEach { it.interruptPending() }
        bindings.clear()
    }
}
