package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

/** The caller reserves/persists the operation before invoking this; nothing is auto-retried. */
suspend fun deliverTerminalComposer(
    client: MobileRpcClient,
    drafts: TerminalDrafts,
    send: TerminalDrafts.Send,
    submit: Boolean,
    supportsFiles: Boolean,
    read: suspend (ComposerAttachment) -> ByteArray,
    persist: suspend () -> Unit,
    isCurrent: () -> Boolean
): Set<String> {
    fun checkCurrent() {
        check(isCurrent() && drafts.state.value[send.target]?.operation == send.operation) { "Connection changed" }
    }
    // Fail before delivering any images if the same message contains unsupported files.
    require(supportsFiles || send.attachments.none { it.imageFormat == null }) {
        "Update cmux on your Mac to send files"
    }
    val uploaded = mutableListOf<Pair<String, String>>()
    for (attachment in send.attachments) {
        coroutineContext.ensureActive()
        checkCurrent()
        if (!drafts.contains(send, attachment)) continue
        val bytes = read(attachment)
        checkCurrent()
        if (!drafts.contains(send, attachment)) continue
        if (attachment.imageFormat != null) {
            client.pasteImage(send.target.workspace, send.target.surface, bytes, attachment.imageFormat)
            checkCurrent()
            drafts.acknowledgeImage(send, attachment)
            persist()
        } else {
            val path = client.uploadAttachment(attachment, bytes, ::checkCurrent)
            uploaded += attachment.id to path
        }
    }
    checkCurrent()
    // Removing a chip while an earlier upload waits must prevent its path being sent.
    val retained = uploaded.filter { (id, _) -> drafts.state.value[send.target]?.attachments?.any { it.id == id } == true }
    val text = ComposerAttachment.withPaths(retained.map { it.second }, send.text)
    if (text.isNotEmpty()) client.paste(send.target.workspace, send.target.surface, text, submit)
    checkCurrent()
    return retained.map { it.first }.toSet()
}
