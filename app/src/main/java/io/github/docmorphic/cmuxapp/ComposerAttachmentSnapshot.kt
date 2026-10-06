package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

/** A preview owns staged bytes independently of a send; retiring its source revokes the lease. */
internal interface ComposerAttachmentSnapshot : AutoCloseable {
    val attachment: ComposerAttachment
    val active: StateFlow<Boolean>
    suspend fun read(): ByteArray
}

internal data class ComposerAttachmentSelection(val identity: ComposerAttachmentPreviewIdentity,
    val snapshot: ComposerAttachmentSnapshot)

/** UI-dispatcher confined. Selection metadata and payload leases never enter a saved Bundle. */
internal class ComposerAttachmentSelections : AutoCloseable {
    private var closed = false
    private val mutable = MutableStateFlow<ComposerAttachmentSelection?>(null)
    val state = mutable.asStateFlow()
    fun select(owner: ComposerAttachmentPreviewOwner, snapshot: ComposerAttachmentSnapshot,
        presentation: String = UUID.randomUUID().toString()): ComposerAttachmentSelection? {
        if (closed || !snapshot.active.value) { snapshot.close(); return null }
        release()
        return ComposerAttachmentSelection(ComposerAttachmentPreviewIdentity(presentation, owner, snapshot.attachment), snapshot)
            .also { mutable.value = it }
    }
    fun clear(identity: ComposerAttachmentPreviewIdentity) {
        if (mutable.value?.identity == identity) release()
    }
    override fun close() { closed = true; release() }
    private fun release() {
        val previous = mutable.value; mutable.value = null; previous?.snapshot?.close()
    }
}
