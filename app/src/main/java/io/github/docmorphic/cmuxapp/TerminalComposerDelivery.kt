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
    isCurrent: () -> Boolean,
    resolveClient: (suspend () -> MobileRpcClient)? = null
): Set<String> {
    fun checkCurrent() {
        check(isCurrent() && drafts.state.value[send.target]?.operation == send.operation) { "Connection changed" }
    }
    suspend fun currentClient(): MobileRpcClient {
        val current = resolveClient?.invoke() ?: client
        checkCurrent()
        return current
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
            currentClient().pasteImage(send.target.workspace, send.target.surface, bytes, attachment.imageFormat)
            checkCurrent()
            drafts.acknowledgeImage(send, attachment)
            persist()
        } else {
            val path = currentClient().uploadAttachment(attachment, bytes, ::checkCurrent)
            uploaded += attachment.id to path
        }
    }
    checkCurrent()
    // Removing a chip while an earlier upload waits must prevent its path being sent.
    val retained = uploaded.filter { (id, _) -> drafts.state.value[send.target]?.attachments?.any { it.id == id } == true }
    val text = ComposerAttachment.withPaths(retained.map { it.second }, send.text)
    if (text.isNotEmpty()) {
        val response = currentClient().paste(send.target.workspace, send.target.surface, text, submit)
        // Pasting and pressing Enter are separate host operations. RPC success alone
        // must not clear the draft when the requested submit key was not accepted.
        // Never retry here: the text may already be present in the terminal.
        check(!submit || response.opt("submitted") == true) {
            "Submission was not confirmed. Check the terminal before sending again."
        }
    }
    checkCurrent()
    return retained.map { it.first }.toSet()
}

/** The retained queue owns this send and its settlement, independent of the observing Activity. */
internal fun queueTerminalComposer(
    queue: TerminalInputQueue, drafts: TerminalDrafts, send: TerminalDrafts.Send,
    persist: suspend () -> Unit, deliver: suspend () -> Set<String>
): Boolean = queue.offerAction(release = {
    // Discard/retirement must release the reservation too. A completed operation is a no-op.
    drafts.finish(send, TerminalDrafts.DELIVERY_UNCONFIRMED)
}) {
    try {
        persist()
        val delivered = deliver()
        drafts.finish(send, deliveredFiles = delivered)
        persist()
    } catch (failure: Exception) {
        drafts.finish(send, TerminalDrafts.DELIVERY_UNCONFIRMED)
        throw failure
    }
}
