package io.github.docmorphic.cmuxapp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** One view's input lane; draft lifetime belongs to the account/provider instead.
 * The terminal and upload callback are captured once, never looked up after await. */
internal class SshTerminalInput(
    private val terminal: SshTerminal,
    private val composer: SshComposerPool.Draft,
    scope: CoroutineScope,
    private val current: () -> Boolean,
    private val onUserInput: () -> Unit = {},
    private val inputGeneration: () -> Int = { 0 },
    private val prepare: suspend (android.net.Uri) -> AttachmentFiles.Prepared,
) : AutoCloseable {
    private val upload = terminal.imageUpload
    val supportsImages get() = upload != null
    private var closed = false
    private val failure = MutableStateFlow<String?>(null)
    val message = failure.asStateFlow()
    val queue = TerminalInputQueue(scope) { entry ->
        guard()
        check(entry.rawBytes?.let(terminal::sendBytes) ?: terminal.send(entry.text, entry.paste))
        // Mouse/wheel packets share ordering, but must not cancel a running fling.
        if (entry.rawBytes == null && entry.text.isNotEmpty()) revealInput()
    }
    private fun allowed() = !closed && current() && composer.isActive() && terminal.acceptsUserInput()
    private fun revealInput(generation: Int = inputGeneration()) {
        if (allowed() && generation == inputGeneration()) onUserInput()
    }
    private fun guard() { check(allowed()) { "The paste target changed. Paste again in the intended terminal." } }
    private fun report(error: Exception) {
        failure.value = when (error) {
            is SshUploadUnconfirmed -> "The image upload could not be confirmed. Check the computer before pasting again."
            else -> "Could not finish the image paste. Check the terminal before trying again."
        }
    }
    fun send(text: String, paste: Boolean = false) = allowed() && queue.offer(text, paste)
    fun sendBytes(bytes: ByteArray) = allowed() && queue.offerBytes(bytes)
    fun resume(): Boolean = allowed() && queue.resume().also { if (it) failure.value = null }

    /** Takes ownership of content even on rejection. All preparation starts only
     * after reserving its place in the lane, including picker/composer staging. */
    fun paste(content: TerminalPasteContent, direct: Boolean): Boolean {
        if (!allowed() || content.items.size !in 1..10 || content.items.any {
                it is TerminalPasteContent.Item.Attachment && (!supportsImages || (direct && !it.image))
            }) {
            content.close()
            failure.value = when {
                !allowed() -> "Open an active terminal before pasting."
                content.items.size !in 1..10 -> "Paste up to 10 items at a time."
                else -> "Use Files to upload a document. This terminal accepts images and text."
            }
            return false
        }
        if (content.items.filterIsInstance<TerminalPasteContent.Item.Text>().sumOf { it.value.toByteArray().size.toLong() } > TerminalInputQueue.MAX_PENDING_BYTES) {
            content.close(); failure.value = "Paste up to 64 KiB of text at a time."; return false
        }
        failure.value = null
        return queue.offerAction(content::close) {
            try {
                for (item in content.items) {
                    guard()
                    when (item) {
                        is TerminalPasteContent.Item.Text -> if (direct) {
                            check(terminal.send(item.value, paste = true))
                            if (item.value.isNotEmpty()) revealInput()
                        } else composer.edit(composer.current.text + item.value)
                        is TerminalPasteContent.Item.Attachment -> {
                            if (!item.image) {
                                failure.value = "This SSH composer accepts images only. Add documents using Files."
                                continue
                            }
                            // Recheck the current draft before opening a provider. Earlier queued
                            // staging may have consumed the remaining slots since the UI frame.
                            if (!direct) require(composer.current.attachments.size < 10) { "Each terminal can hold up to 10 attachments" }
                            val prepared = if (direct) prepare(item.uri) else
                                readComposerAttachment(::guard, { failure.value = it }) { prepare(item.uri) } ?: continue
                            try {
                                guard()
                                if (direct) insert(prepared.bytes, checkNotNull(prepared.attachment.imageFormat))
                                else composer.attach(prepared.attachment, prepared.bytes)
                            } finally { prepared.bytes.fill(0) }
                        }
                    }
                }
            } catch (error: Exception) {
                // Interrupted materialization cannot revive a retired account.
                currentCoroutineContext().ensureActive()
                if (direct) { report(error); throw error }
                failure.value = error.message ?: "Could not prepare the image"
                // Staging has sent no remote input. Its failure does not make
                // subsequent independent keyboard input uncertain.
            }
        }.also { if (!it) failure.value = "Could not queue the paste. Wait for pending input and try again." }
    }

    private suspend fun insert(bytes: ByteArray, format: String, separate: Boolean = false) {
        guard()
        val path = checkNotNull(upload) { "Image paste is unavailable" }(bytes, format)
        guard()
        check(terminal.send(SshFilePaths.shellWord(path) + if (separate) " " else "")) { "Image path delivery was not confirmed" }
        revealInput()
    }

    fun submit(): Boolean {
        // Button state may be a frame behind URI preparation. Capture the draft
        // only after staging is idle, never ahead of an image the user just added.
        val pending = queue.status.value
        if (!allowed() || pending.closed || pending.error != null || pending.pendingBytes > 0) return false
        val submitted = composer.begin() ?: return false
        failure.value = null
        var completed = false
        return queue.offerAction(release = {
            composer.finish(submitted, if (completed) null else TerminalDrafts.DELIVERY_UNCONFIRMED)
        }) {
            try {
                guard()
                composer.persistPending(submitted)
                for (attachment in submitted.attachments) {
                    guard()
                    if (!composer.contains(submitted, attachment)) continue
                    val bytes = composer.read(attachment)
                    try { insert(bytes, checkNotNull(attachment.imageFormat), separate = true) }
                    finally { bytes.fill(0) }
                    composer.accepted(submitted, attachment)
                }
                guard()
                // Images-only sends never execute a shell command.
                if (submitted.text.isNotEmpty()) {
                    // A Cloud acknowledgement must not undo a newer local scroll.
                    val generation = inputGeneration()
                    check(terminal.submitText(
                        TerminalKeyEncoding.paste(submitted.text, terminal.display.bracketedPaste) + "\r"))
                    revealInput(generation)
                }
                completed = true
            } catch (error: Exception) {
                if (error !is CancellationException) failure.value = "Could not send the draft. Check the terminal before sending again."
                throw error
            }
        }
    }

    override fun close() { closed = true; queue.close() }
}
