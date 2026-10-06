package io.github.docmorphic.cmuxapp

import android.net.Uri
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/** Real Ghostty display with controlled preparation/upload suspension points. */
class SshTerminalInputTest {
    private class Terminal(pool: SshComposerPool, override val imageUpload: SshImageUpload) : SshTerminal {
        override val id = "image-test"
        override val title = "Image test"
        override val composer = pool.open(id)
        override val state = MutableStateFlow(SshShellState(SshShellPhase.RUNNING))
        override val display = GhosttyVtTerminal(80, 24)
        val writes = mutableListOf<ByteArray>()
        override fun send(text: String, paste: Boolean) = sendBytes((if (paste) TerminalKeyEncoding.paste(text, display.bracketedPaste) else text).toByteArray())
        override fun sendBytes(bytes: ByteArray): Boolean { writes += bytes.copyOf(); return true }
        override fun resize(columns: Int, rows: Int, cells: TerminalCellMetrics) {}
        override fun close() { state.value = state.value.copy(phase = SshShellPhase.ENDED); display.close() }
    }
    private fun content(release: () -> Unit = {}) = TerminalPasteContent(
        listOf(TerminalPasteContent.Item.Attachment(Uri.parse("content://fixture/image.png"), true)), release)
    private fun prepared(value: Int = 7) = AttachmentFiles.Prepared(
        ComposerAttachment(name = "image.png", size = 2, imageFormat = "png"), byteArrayOf(value.toByte(), 9))

    @Test fun composerSkipsUnreadableMiddleImageAndUploadsReadableImagesInOrder() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = SshComposerPool(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val uploads = mutableListOf<Int>(); var releases = 0
            val terminal = Terminal(pool) { bytes, _ -> uploads += bytes[0].toInt(); "/image-${bytes[0]}.png" }
            val input = SshTerminalInput(terminal, terminal.composer, scope, { true }) { uri ->
                val number = uri.lastPathSegment!!.toInt()
                if (number == 2) throw IOException("Provider no longer permits reading")
                prepared(number)
            }
            try {
                terminal.composer.edit("Keep my prompt")
                val content = TerminalPasteContent((1..3).map {
                    TerminalPasteContent.Item.Attachment(Uri.parse("content://fixture/$it"), true)
                }) { releases++ }
                assertTrue(input.paste(content, direct = false)); input.queue.awaitIdle()
                assertEquals(1, releases); assertEquals(2, terminal.composer.current.attachments.size)
                assertEquals("Keep my prompt", terminal.composer.current.text)
                assertEquals("Provider no longer permits reading", input.message.value)
                assertTrue(uploads.isEmpty()); assertTrue(terminal.writes.isEmpty())
                assertTrue(input.submit()); input.queue.awaitIdle()
                assertEquals(listOf(1, 3), uploads)
                assertEquals(listOf("'/image-1.png' ", "'/image-3.png' ", "Keep my prompt\r"), terminal.writes.map { it.decodeToString() })
            } finally { input.close(); terminal.close(); pool.close(); scope.cancel() }
        }
    }

    @Test fun composerSkipsUnsupportedFilesWithoutOpeningThemButDirectPasteRejectsWholeBatch() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = SshComposerPool(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val terminal = Terminal(pool) { _, _ -> "/image.png" }
            val opened = mutableListOf<String>(); var released = 0
            val input = SshTerminalInput(terminal, terminal.composer, scope, { true }) { uri ->
                opened += uri.lastPathSegment!!; prepared()
            }
            fun mixed() = TerminalPasteContent(listOf(
                TerminalPasteContent.Item.Attachment(Uri.parse("content://fixture/private.pdf"), false),
                TerminalPasteContent.Item.Attachment(Uri.parse("content://fixture/readable.png"), true)
            )) { released++ }
            try {
                assertFalse(input.paste(mixed(), direct = true))
                assertTrue(opened.isEmpty()); assertTrue(terminal.writes.isEmpty()); assertEquals(1, released)
                assertTrue(input.paste(mixed(), direct = false)); input.queue.awaitIdle()
                assertEquals(listOf("readable.png"), opened)
                assertEquals(1, terminal.composer.current.attachments.size); assertEquals(2, released)
                assertNull(input.queue.status.value.error); assertTrue(terminal.writes.isEmpty())
                assertEquals("This SSH composer accepts images only. Add documents using Files.", input.message.value)
                pool.close()
                assertFalse(input.paste(content { released++ }, direct = false))
                assertEquals(3, released); assertEquals(listOf("readable.png"), opened)
                assertFalse(input.send("retired account")); assertTrue(terminal.writes.isEmpty())
            } finally { input.close(); terminal.close(); pool.close(); scope.cancel() }
        }
    }

    @Test fun fullDraftRejectsNextImageBeforeOpeningItsProvider() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = SshComposerPool(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val terminal = Terminal(pool) { _, _ -> "/image.png" }
            var opened = 0; var released = 0
            val input = SshTerminalInput(terminal, terminal.composer, scope, { true }) { opened++; prepared() }
            try {
                repeat(10) { val image = prepared(it); terminal.composer.attach(image.attachment, image.bytes) }
                assertTrue(input.paste(content { released++ }, direct = false)); input.queue.awaitIdle()
                assertEquals(0, opened); assertEquals(1, released)
                assertEquals(10, terminal.composer.current.attachments.size)
                assertEquals("Each terminal can hold up to 10 attachments", input.message.value)
                assertNull(input.queue.status.value.error); assertTrue(terminal.writes.isEmpty())
            } finally { input.close(); terminal.close(); pool.close(); scope.cancel() }
        }
    }

    @Test fun imageReservesPreparationAndUploadBeforeTextAndBinaryMouseThenReleasesGrant() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = SshComposerPool(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val decoding = CompletableDeferred<Unit>(); val uploading = CompletableDeferred<Unit>()
            val decoded = prepared(); var uploaded: ByteArray? = null; var released = 0
            val path = "/home/a'b/.cmux/uploads/image.png"
            val terminal = Terminal(pool) { bytes, _ -> uploaded = bytes.copyOf(); uploading.await(); path }
            val input = SshTerminalInput(terminal, terminal.composer, scope, { true }) { decoding.await(); decoded }
            try {
                assertTrue(input.send("before"))
                assertTrue(input.paste(content { released++ }, direct = true))
                assertTrue(input.send("later"))
                val mouse = byteArrayOf(27, 91, 77, 32, 183.toByte(), 35)
                assertTrue(input.sendBytes(mouse)); mouse.fill(0)
                assertEquals(listOf("before"), terminal.writes.map { it.decodeToString() })
                decoding.complete(Unit); yield()
                assertArrayEquals(byteArrayOf(7, 9), uploaded); assertEquals(0, released)
                uploading.complete(Unit); withTimeout(3000) { input.queue.awaitIdle() }
                assertEquals(listOf("before", "'/home/a'\\''b/.cmux/uploads/image.png'", "later"), terminal.writes.take(3).map { it.decodeToString() })
                assertArrayEquals(byteArrayOf(27, 91, 77, 32, 183.toByte(), 35), terminal.writes.last())
                assertEquals(1, released); assertTrue(decoded.bytes.all { it == 0.toByte() })
                assertTrue(terminal.writes.none { it.contains(13.toByte()) })
            } finally { input.close(); terminal.close(); pool.close(); scope.cancel() }
        }
    }

    @Test fun sendBeforeTheNextUiFrameCannotSkipAnImageStillBeingPrepared() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = SshComposerPool(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val decoding = CompletableDeferred<Unit>(); var releases = 0
            val terminal = Terminal(pool) { _, _ -> "/prepared.png" }
            val input = SshTerminalInput(terminal, terminal.composer, scope, { true }) { decoding.await(); prepared() }
            try {
                terminal.composer.edit("caption")
                assertTrue(input.paste(content { releases++ }, direct = false))
                assertFalse(input.submit())
                assertNull(terminal.composer.current.operation); assertTrue(terminal.writes.isEmpty())
                decoding.complete(Unit); input.queue.awaitIdle()
                assertEquals(1, releases); assertEquals(1, terminal.composer.current.attachments.size)
                assertTrue(input.submit()); input.queue.awaitIdle()
                assertEquals(listOf("'/prepared.png' ", "caption\r"), terminal.writes.map { it.decodeToString() })
            } finally { input.close(); terminal.close(); pool.close(); scope.cancel() }
        }
    }

    @Test fun failedUploadDropsLaterKeysAndGrantsAndResumeDoesNotReplayImages() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = SshComposerPool(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val release = CompletableDeferred<Unit>(); var attempts = 0; var grants = 0
            val terminal = Terminal(pool) { _, _ -> attempts++; release.await(); throw IOException("Lost reply") }
            val input = SshTerminalInput(terminal, terminal.composer, scope, { true }) { prepared() }
            try {
                input.paste(content { grants++ }, true); input.paste(content { grants++ }, true); input.send("must not run")
                release.complete(Unit)
                withTimeout(3000) { while (input.queue.status.value.error == null) yield() }
                assertEquals(1, attempts); assertEquals(2, grants); assertTrue(terminal.writes.isEmpty())
                assertTrue(input.resume()); input.send("new explicit typing"); input.queue.awaitIdle()
                assertEquals(1, attempts); assertEquals("new explicit typing", terminal.writes.single().decodeToString())
            } finally { input.close(); terminal.close(); pool.close(); scope.cancel() }
        }
    }

    @Test fun composerSnapshotsTextSkipsRemovedWaitingImagesAndPreservesNewEdits() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = SshComposerPool(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val release = CompletableDeferred<Unit>(); var uploads = 0
            val terminal = Terminal(pool) { _, _ -> uploads++; release.await(); "/first.png" }
            terminal.display.append("\u001b[?2004h".toByteArray())
            val input = SshTerminalInput(terminal, terminal.composer, scope, { true }) { prepared() }
            val first = prepared(1); val second = prepared(2)
            try {
                terminal.composer.attach(first.attachment, first.bytes); terminal.composer.attach(second.attachment, second.bytes)
                terminal.composer.edit("original λ")
                assertTrue(input.submit()); assertFalse(input.submit())
                terminal.composer.remove(second.attachment.id); terminal.composer.edit("new draft")
                release.complete(Unit); input.queue.awaitIdle()
                assertEquals(1, uploads)
                assertEquals(listOf("'/first.png' ", "\u001b[200~original λ\u001b[201~\r"), terminal.writes.map { it.decodeToString() })
                assertEquals("new draft", terminal.composer.current.text); assertTrue(terminal.composer.current.attachments.isEmpty())
            } finally { input.close(); terminal.close(); pool.close(); scope.cancel() }
        }
    }

    @Test fun partialComposerFailureRetainsOnlyUnsentImageAndExplicitRetrySendsNoEnterForImagesOnly() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = SshComposerPool(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val attempts = mutableListOf<Int>()
            val terminal = Terminal(pool) { bytes, _ ->
                attempts += bytes[0].toInt()
                if (attempts.size == 2) throw IOException("Denied")
                "/image-${bytes[0]}.png"
            }
            val input = SshTerminalInput(terminal, terminal.composer, scope, { true }) { prepared() }
            val first = prepared(1); val second = prepared(2)
            try {
                terminal.composer.attach(first.attachment, first.bytes); terminal.composer.attach(second.attachment, second.bytes)
                input.submit()
                withTimeout(3000) { while (input.queue.status.value.error == null) yield() }
                assertEquals(listOf(second.attachment), terminal.composer.current.attachments)
                assertEquals(listOf(1, 2), attempts)
                assertTrue(input.resume()); assertTrue(input.submit()); input.queue.awaitIdle()
                assertEquals(listOf(1, 2, 2), attempts)
                assertEquals(listOf("'/image-1.png' ", "'/image-2.png' "), terminal.writes.map { it.decodeToString() })
                assertTrue(terminal.composer.current.attachments.isEmpty())
            } finally { input.close(); terminal.close(); pool.close(); scope.cancel() }
        }
    }

    @Test fun viewOrAccountRetirementCannotInsertAnUploadResultOrRestoreStagedBytes() = runBlocking {
        withContext(Dispatchers.Main) {
            val pool = SshComposerPool(); val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
            val pending = CompletableDeferred<Unit>(); var grants = 0; var current = true
            val terminal = Terminal(pool) { _, _ -> pending.await(); "/stale.png" }
            val input = SshTerminalInput(terminal, terminal.composer, scope, { current }) { prepared() }
            try {
                input.paste(content { grants++ }, true); current = false; pending.complete(Unit)
                withTimeout(3000) { while (input.queue.status.value.error == null) yield() }
                assertTrue(terminal.writes.isEmpty()); assertEquals(1, grants)
                input.close(); pool.close()
                assertFalse(input.paste(content { grants++ }, false))
                assertEquals(2, grants); assertTrue(pool.state.value.isEmpty())
            } finally { input.close(); terminal.close(); pool.close(); scope.cancel() }
        }
    }
}
